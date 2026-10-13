package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class LegacySnapshotConverterTest {
    @TempDir Path directory;
    private final ObjectMapper mapper=Fixtures.mapper();
    private final LegacySnapshotConverter converter=new LegacySnapshotConverter(mapper);
    private static final String[] COLUMNS={"created_at","updated_at","deleted_at","user_id","game","platform","faction","nickname","avatar","kill_sum","points1_sum","points2_sum","points3_sum","daily_kill_sum","daily_points1_sum","daily_points2_sum","daily_points3_sum","zhubo","value1","value2","value3"};
    private String row(String user,String game,long kill,long daily,String points,String nickname) {
        String escaped=nickname.replace("\\","\\\\").replace("'","\\'").replace("\n","\\n");
        return "('2026-10-13 15:21:53.123','2026-10-13 15:21:54.000',NULL,'"+user+"','"+game+"','DYGYB2','红方','"+escaped+"','https://example.com/a.png',"+kill+","+points+",0,0,"+daily+",0,0,0,'',0,0,0)";
    }
    private Path dump(String... rows)throws Exception {
        StringBuilder text=new StringBuilder("CREATE TABLE `t_user_kill_stats` (\n");
        for(String column:COLUMNS)text.append("  `").append(column).append("` varchar(128),\n");
        text.append(") ENGINE=InnoDB;\nINSERT INTO `t_user_kill_stats` VALUES ").append(String.join(",",rows)).append(";\n-- Dump completed on 2026-10-13 15:22:02\n");
        Path input=directory.resolve("source.sql");Files.write(input,text.toString().getBytes(StandardCharsets.UTF_8));return input;
    }
    @Test void preservesTwentyOneFieldsOrphanAllAndServerDigest()throws Exception {
        Path input=dump(row("u1","BJGYB2",100,0,"1.25","O'Brien\\文件"),row("orphan","",443457,100559,"0","旧汇总"));
        StringWriter pages=new StringWriter();Map<String,Object> report=converter.convert(input,pages,new StringWriter());
        JsonNode page=mapper.readTree(pages.toString());assertEquals(2,page.path("rows").size());
        JsonNode first=page.path("rows").get(0);assertEquals(21,first.size());assertEquals("O'Brien\\文件",first.path("Nickname").asText());
        assertEquals("2026-10-13T15:21:53.123+08:00",first.path("CreatedAt").asText());assertEquals("2026-10-13T15:21:54+08:00",first.path("UpdatedAt").asText());
        assertEquals(0,first.path("DailyKillSum").asLong());assertEquals(1.25,first.path("Points1Sum").asDouble());
        MigrationPlan plan=new MigrationPlan();plan.setGames(new HashSet<>(LegacySnapshotConverter.GAMES));plan.setIncludesAllGames(true);
        MigrationStore store=new MigrationStore(mock(JdbcTemplate.class),mock(PlatformTransactionManager.class),mapper,new LeaderboardProperties(),new Fixtures.MutableClock(),mock(LegacyScopeStore.class));
        String digest=ScoreRules.sha256("");
        for(JsonNode node:page.path("rows"))digest=ScoreRules.sha256(digest+":"+ScoreRules.sha256(mapper.writeValueAsString(store.validateRow(node,plan))));
        assertEquals(digest,report.get("expectedDigest"));assertEquals(2L,report.get("expectedRows"));assertEquals(false,report.get("legacyFrozen"));assertEquals(false,report.get("readyForImport"));
    }
    @Test void excludesOnlyMatchedNegativeTestAndAllRows()throws Exception {
        Path input=dump(row("test","临时测试",-100,0,"0","测试"),row("test","",-100,0,"0","测试"),row("normal","临时测试",20,0,"0","正常"));
        StringWriter pages=new StringWriter(),excluded=new StringWriter();Map<String,Object> report=converter.convert(input,pages,excluded);
        assertEquals(2L,report.get("excludedNegativeTestRows"));assertEquals(1L,report.get("expectedRows"));
        assertEquals("临时测试",mapper.readTree(pages.toString()).path("rows").get(0).path("Game").asText());
        assertFalse(excluded.toString().contains("Nickname"));assertEquals(2,excluded.toString().split("\n").length);
        assertThrows(IllegalArgumentException.class,()->converter.convert(dump(row("test","临时测试",-100,0,"0","测试")),null,null));
        assertThrows(IllegalArgumentException.class,()->converter.convert(dump(row("u","BJGYB2",-100,0,"0","用户")),null,null));
    }
    @Test void pageBoundaryAndExcludedRowsDoNotEnterDigest()throws Exception {
        String[] rows=new String[202];for(int i=0;i<201;i++)rows[i]=row("u"+i,"BJGYB2",100,0,"0","用户");rows[201]=row("zero","BJGYB2",0,0,"0","零分");
        StringWriter pages=new StringWriter();Map<String,Object> report=converter.convert(dump(rows),pages,null);
        String[] lines=pages.toString().split("\n");assertEquals(2,lines.length);assertEquals(200,mapper.readTree(lines[0]).path("rows").size());assertEquals(1,mapper.readTree(lines[1]).path("rows").size());
        assertEquals(201L,report.get("expectedRows"));assertEquals(1L,report.get("excludedAllZeroRows"));assertEquals(2,report.get("pages"));
    }
    @Test void refusesDuplicatesUnknownGamesAndTruncatedFiles()throws Exception {
        String repeated=row("u1","BJGYB2",100,0,"0","用户");
        assertThrows(IllegalArgumentException.class,()->converter.convert(dump(repeated,repeated),null,null));
        assertThrows(IllegalArgumentException.class,()->converter.convert(dump(row("u1","UNKNOWN",100,0,"0","用户")),null,null));
        Path input=dump(repeated);String text=new String(Files.readAllBytes(input),StandardCharsets.UTF_8);Files.write(input,text.substring(0,text.indexOf("-- Dump completed")).getBytes(StandardCharsets.UTF_8));
        Path output=directory.resolve("output");assertThrows(IllegalArgumentException.class,()->LegacySnapshotConverter.main(new String[]{input.toString(),output.toString()}));
        assertFalse(Files.exists(output));try(java.util.stream.Stream<Path> files=Files.list(directory)){assertEquals(1,files.count());}
    }
    @Test void publishesOutputOnlyAfterSuccessfulValidationAndNeverOverwrites()throws Exception {
        Path input=dump(row("u","BJGYB2",100,0,"0","用户")),output=directory.resolve("output");
        LegacySnapshotConverter.main(new String[]{input.toString(),output.toString()});assertTrue(Files.exists(output.resolve("pages.jsonl")));assertTrue(Files.exists(output.resolve("excluded.jsonl")));
        assertEquals(false,mapper.readTree(Files.readAllBytes(output.resolve("summary.json"))).path("readyForImport").asBoolean());
        assertThrows(IllegalArgumentException.class,()->LegacySnapshotConverter.main(new String[]{input.toString(),output.toString()}));
    }
    @Test @EnabledIfSystemProperty(named="leaderboard.snapshot-path",matches=".+")
    void checksProvidedSnapshotWithoutGeneratingPersonalDataFiles()throws Exception {
        Map<String,Object> report=converter.convert(Paths.get(System.getProperty("leaderboard.snapshot-path")),null,null);
        assertEquals("c2a82850fd46b74ac8f6a4135608509abf4c5d73e81c764de030a9bfeaaefb15",report.get("sourceSha256"));
        assertEquals(381136L,report.get("sourceRows"));assertEquals(381016L,report.get("expectedRows"));assertEquals(120L,report.get("excludedNegativeTestRows"));assertEquals(1906,report.get("pages"));
        assertEquals(new TreeSet<>(LegacySnapshotConverter.GAMES),report.get("observedGames"));System.out.println("REAL_SNAPSHOT_CHECK "+mapper.writeValueAsString(report));
    }
}
