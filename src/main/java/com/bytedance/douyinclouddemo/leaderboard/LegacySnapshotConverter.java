package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.time.LocalDateTime;
import java.time.format.*;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.regex.*;

/** 离线读取旧快照；不执行 SQL，不连接迁移接口。 */
public final class LegacySnapshotConverter {
    static final List<String> GAMES=Collections.unmodifiableList(Arrays.asList("BJGYB2","BJNC","BYJJ","GiveUpPro","掘地求升","跳上天庭官游","跳上天庭1","欢乐球球成品","爬个杆杆","马里奥求升1","跃城超级玛丽测试版","排行榜测试游戏","临时测试"));
    private static final List<String> COLUMNS=Arrays.asList("created_at","updated_at","deleted_at","user_id","game","platform","faction","nickname","avatar","kill_sum","points1_sum","points2_sum","points3_sum","daily_kill_sum","daily_points1_sum","daily_points2_sum","daily_points3_sum","zhubo","value1","value2","value3");
    private static final String INSERT="INSERT INTO `t_user_kill_stats` VALUES ";
    private static final Pattern COLUMN=Pattern.compile("^  `([a-z0-9_]+)` .*");
    private static final DateTimeFormatter SQL_TIME=new DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd HH:mm:ss").appendFraction(ChronoField.NANO_OF_SECOND,0,9,true).toFormatter().withResolverStyle(ResolverStyle.STRICT);
    private final ObjectMapper mapper;
    public LegacySnapshotConverter(ObjectMapper mapper){this.mapper=mapper;}

    public static void main(String[] args)throws Exception {
        if(args.length<1||args.length>2)throw new IllegalArgumentException("用法：LegacySnapshotConverter 输入.sql [新输出目录]");
        LegacySnapshotConverter converter=new LegacySnapshotConverter(new ObjectMapper());
        Map<String,Object> report;
        if(args.length==1)report=converter.convert(Paths.get(args[0]),null,null);
        else {
            Path output=Paths.get(args[1]).toAbsolutePath().normalize();
            if(Files.exists(output))throw new IllegalArgumentException("输出目录已存在，禁止覆盖");
            Path staging=Files.createTempDirectory(output.getParent(),"leaderboard-snapshot-partial-");
            try {
                try(BufferedWriter pages=Files.newBufferedWriter(staging.resolve("pages.jsonl"),StandardCharsets.UTF_8);
                    BufferedWriter excluded=Files.newBufferedWriter(staging.resolve("excluded.jsonl"),StandardCharsets.UTF_8)) {
                    report=converter.convert(Paths.get(args[0]),pages,excluded);
                }
                Files.write(staging.resolve("summary.json"),converter.mapper.writeValueAsBytes(report));
                Files.move(staging,output);
            } finally {
                // 暂存目录只含本次生成的三个文件，失败时不留下可误用的半成品。
                if(Files.exists(staging)) {
                    for(String name:Arrays.asList("pages.jsonl","excluded.jsonl","summary.json"))Files.deleteIfExists(staging.resolve(name));
                    Files.delete(staging);
                }
            }
        }
        System.out.println(converter.mapper.writeValueAsString(report));
    }

    public Map<String,Object> convert(Path input,Writer pages,Writer exclusions)throws IOException,NoSuchAlgorithmException {
        MessageDigest sourceHash=MessageDigest.getInstance("SHA-256");
        Set<String> seen=new HashSet<>(),observedGames=new TreeSet<>();
        Map<RankScope,String> scopes=new HashMap<>();
        Map<String,String> testNegative=new HashMap<>(),allNegative=new HashMap<>();
        List<RankEntry> page=new ArrayList<>(200);
        long sourceRows=0,included=0,negative=0,deleted=0,zero=0;
        int pageNumber=0,lineNumber=0;String digest=ScoreRules.sha256("");
        boolean schema=false,inSchema=false,complete=false;List<String> columns=new ArrayList<>();
        CharsetDecoder utf8=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new DigestInputStream(Files.newInputStream(input),sourceHash),utf8))) {
            String line;
            while((line=reader.readLine())!=null) {
                lineNumber++;
                if(line.startsWith("CREATE TABLE ")) {
                    if(schema||inSchema||!line.equals("CREATE TABLE `t_user_kill_stats` ("))throw invalid(lineNumber);
                    inSchema=true;continue;
                }
                if(inSchema) {
                    Matcher match=COLUMN.matcher(line);if(match.matches())columns.add(match.group(1));
                    if(line.startsWith(") ENGINE=")){if(!columns.equals(COLUMNS))throw invalid(lineNumber);schema=true;inSchema=false;}
                    continue;
                }
                if(line.startsWith("-- Dump completed on "))complete=true;
                if(!line.startsWith("INSERT INTO "))continue;
                if(!schema||!line.startsWith(INSERT)||complete)throw invalid(lineNumber);
                int[] position={INSERT.length()};
                while(true) {
                    List<String> values=parseRow(line,position,lineNumber);sourceRows++;
                    if(values.get(2)!=null){deleted++;audit(exclusions,sourceRows,"DELETED",null,null);}
                    else {
                        RankEntry entry;
                        try{entry=entry(values);}catch(RuntimeException e){throw invalid(lineNumber);}
                        if(!entry.getGame().isEmpty())observedGames.add(entry.getGame());
                        RankScope scope=new RankScope(entry.getGame(),entry.getPlatform(),entry.getFaction(),entry.getZhubo());
                        String scopeId=scopes.computeIfAbsent(scope,RankScope::id);
                        String userHash=ScoreRules.sha256(entry.getUserId());
                        if(!seen.add(ScoreRules.sha256(scopeId+":"+userHash)))throw invalid(lineNumber);
                        if(entry.getKillSum()<0) {
                            if(!entry.getGame().isEmpty()&&!"临时测试".equals(entry.getGame()))throw invalid(lineNumber);
                            String key=ScoreRules.sha256(mapper.writeValueAsString(Arrays.asList(userHash,entry.getPlatform(),entry.getFaction(),entry.getZhubo())));
                            String metrics=mapper.writeValueAsString(Arrays.asList(entry.getKillSum(),entry.getDailyKillSum(),entry.getPoints1Sum(),entry.getPoints2Sum(),entry.getPoints3Sum(),entry.getDailyPoints1Sum(),entry.getDailyPoints2Sum(),entry.getDailyPoints3Sum()));
                            (entry.getGame().isEmpty()?allNegative:testNegative).put(key,metrics);
                            negative++;audit(exclusions,sourceRows,"CONFIRMED_NEGATIVE_TEST",scopeId,userHash);
                        } else if(allZero(entry)){zero++;audit(exclusions,sourceRows,"ALL_RANK_METRICS_ZERO",scopeId,userHash);}
                        else {
                            // 与 MigrationStore.append 共用 RankEntry 序列化，避免另一套数字格式改变摘要。
                            digest=ScoreRules.sha256(digest+":"+ScoreRules.sha256(mapper.writeValueAsString(entry)));
                            included++;page.add(entry);
                            if(page.size()==200){writePage(pages,++pageNumber,page);page.clear();}
                        }
                    }
                    char separator=next(line,position,lineNumber);
                    if(separator==';'){if(position[0]!=line.length())throw invalid(lineNumber);break;}
                    if(separator!=',')throw invalid(lineNumber);
                }
            }
        }
        if(!schema||!complete||sourceRows==0||!testNegative.equals(allNegative))throw new IllegalArgumentException("快照不完整，或负分测试与 ALL 汇总无法逐范围对应");
        if(!page.isEmpty())writePage(pages,++pageNumber,page);
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("sourceSha256",hex(sourceHash.digest()));report.put("sourceTimezone","Asia/Shanghai");
        report.put("games",GAMES);report.put("observedGames",observedGames);report.put("sourceRows",sourceRows);
        report.put("expectedRows",included);report.put("excludedNegativeTestRows",negative);report.put("excludedDeletedRows",deleted);report.put("excludedAllZeroRows",zero);
        report.put("pageSize",200);report.put("pages",pageNumber);report.put("expectedDigest",digest);
        report.put("legacyFrozen",false);report.put("readyForImport",false);
        return report;
    }

    private void writePage(Writer writer,int number,List<RankEntry> rows)throws IOException {
        if(writer==null)return;
        Map<String,Object> page=new LinkedHashMap<>();page.put("page",number);page.put("rows",rows);writer.write(mapper.writeValueAsString(page));writer.write('\n');
    }
    private void audit(Writer writer,long row,String reason,String scope,String user)throws IOException {
        if(writer==null)return;
        Map<String,Object> audit=new LinkedHashMap<>();audit.put("sourceRow",row);audit.put("reason",reason);audit.put("scopeId",scope);audit.put("userHash",user);writer.write(mapper.writeValueAsString(audit));writer.write('\n');
    }
    private RankEntry entry(List<String> v) {
        RankEntry e=new RankEntry();e.setUserId(ScoreRules.identifier(v.get(3),128,false));
        e.setGame(ScoreRules.identifier(v.get(4),128,true));if(!e.getGame().isEmpty()&&!GAMES.contains(e.getGame()))throw new IllegalArgumentException();
        e.setPlatform(ScoreRules.identifier(v.get(5),128,true));e.setFaction(ScoreRules.identifier(v.get(6),128,true));e.setZhubo(ScoreRules.identifier(v.get(17),128,true));
        if(v.get(7)==null||v.get(8)==null)throw new IllegalArgumentException();e.setNickname(v.get(7));e.setAvatar(v.get(8));
        e.setKillSum(integer(v.get(9)));e.setDailyKillSum(integer(v.get(13)));if(e.getDailyKillSum()<0)throw new IllegalArgumentException();
        e.setPoints1Sum(number(v.get(10),true));e.setPoints2Sum(number(v.get(11),true));e.setPoints3Sum(number(v.get(12),true));
        e.setDailyPoints1Sum(number(v.get(14),true));e.setDailyPoints2Sum(number(v.get(15),true));e.setDailyPoints3Sum(number(v.get(16),true));
        e.setValue1(number(v.get(18),false));e.setValue2(number(v.get(19),false));e.setValue3(number(v.get(20),false));
        e.setCreatedAt(time(v.get(0)));e.setUpdatedAt(time(v.get(1)));return e;
    }
    private long integer(String value){long result=new BigDecimal(value).longValueExact();if(result>ScoreRules.MAX_SCORE||result<-ScoreRules.MAX_SCORE)throw new IllegalArgumentException();return result;}
    private double number(String value,boolean nonnegative){double result=Double.parseDouble(value);if(!Double.isFinite(result)||(nonnegative&&result<0))throw new IllegalArgumentException();return result;}
    static String time(String value){return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(LocalDateTime.parse(value,SQL_TIME).atZone(Periods.ZONE));}
    private boolean allZero(RankEntry entry){for(RankMetric metric:RankMetric.values())if(entry.score(metric.code)!=0)return false;return true;}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder(64);for(byte b:bytes)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();}
    private static IllegalArgumentException invalid(int line){return new IllegalArgumentException("快照格式或字段不符合已确认契约，行号="+line);}
    private static char next(String line,int[] pos,int number){while(pos[0]<line.length()&&Character.isWhitespace(line.charAt(pos[0])))pos[0]++;if(pos[0]>=line.length())throw invalid(number);return line.charAt(pos[0]++);}
    private static List<String> parseRow(String line,int[] pos,int number) {
        if(next(line,pos,number)!='(')throw invalid(number);List<String> row=new ArrayList<>(21);
        while(true) {
            char first=next(line,pos,number);String value;
            if(first=='\'') {
                StringBuilder text=new StringBuilder();boolean closed=false;
                while(pos[0]<line.length()) {
                    char ch=line.charAt(pos[0]++);
                    if(ch=='\''){closed=true;break;}
                    if(ch=='\\') {
                        if(pos[0]>=line.length())throw invalid(number);ch=line.charAt(pos[0]++);
                        switch(ch){case '0':ch='\0';break;case 'n':ch='\n';break;case 'r':ch='\r';break;case 't':ch='\t';break;case 'b':ch='\b';break;case 'Z':ch=26;break;case '%':case '_':text.append('\\');break;default:break;}
                    }
                    text.append(ch);
                }
                if(!closed)throw invalid(number);value=text.toString();
            } else {
                int start=pos[0]-1;while(pos[0]<line.length()&&line.charAt(pos[0])!=','&&line.charAt(pos[0])!=')')pos[0]++;
                String literal=line.substring(start,pos[0]).trim();if(literal.equals("NULL"))value=null;
                else {try{new BigDecimal(literal);}catch(NumberFormatException e){throw invalid(number);}value=literal;}
            }
            row.add(value);char delimiter=next(line,pos,number);
            if(delimiter==')')break;if(delimiter!=',')throw invalid(number);
        }
        if(row.size()!=21)throw invalid(number);return row;
    }
}
