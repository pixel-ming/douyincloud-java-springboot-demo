package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Repository
@RequiredArgsConstructor
public class RankDataStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final DateTimeFormatter LEGACY_TIME=new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.MILLI_OF_SECOND,0,3,true).appendOffsetId().toFormatter();

    private MapSqlParameterSource parameters(RankScope scope,long day,long cycle){return new MapSqlParameterSource().addValue("scope",scope.id()).addValue("day",day).addValue("cycle",cycle);}
    private String filters(RankScope scope,MapSqlParameterSource parameters) {
        StringBuilder sql=new StringBuilder();String[] names={"game","platform","faction","zhubo"};String[] values={scope.getGame(),scope.getPlatform(),scope.getFaction(),scope.getZhubo()};
        for(int i=0;i<names.length;i++)if(values[i]!=null){sql.append(" AND ").append(names[i]).append("=:").append(names[i]);parameters.addValue(names[i],values[i]);}
        return sql.toString();
    }
    String totalsSql(RankScope scope,MapSqlParameterSource parameters,String userFilter) {
        String actual="SELECT user_id,SUM(CASE WHEN rank_type=1 THEN score_sum ELSE 0 END) kill_sum,SUM(CASE WHEN rank_type=0 THEN score_sum ELSE 0 END) daily_kill_sum,0 points1_sum,0 points2_sum,0 points3_sum,0 daily_points1_sum,0 daily_points2_sum,0 daily_points3_sum FROM score_total WHERE ((rank_type=0 AND period_start=:day) OR (rank_type=1 AND period_start=:cycle))"+filters(scope,parameters)+userFilter+" GROUP BY user_id";
        StringBuilder legacy=new StringBuilder("SELECT user_id");
        for(String name:new String[]{"kill_sum","daily_kill_sum","points1_sum","points2_sum","points3_sum","daily_points1_sum","daily_points2_sum","daily_points3_sum"})legacy.append(",CASE WHEN ").append(name.startsWith("daily")?"day_start=:day":"cycle_start=:cycle").append(" THEN ").append(name).append(" ELSE 0 END ").append(name);
        legacy.append(" FROM legacy_rank_snapshot s JOIN legacy_import_batch b ON s.batch_id=b.batch_id WHERE s.scope_id=:scope AND b.status='IMPORTED'").append(userFilter);
        StringBuilder sum=new StringBuilder("SELECT user_id");StringBuilder having=new StringBuilder();
        for(RankMetric metric:RankMetric.values()){sum.append(",SUM(").append(metric.column).append(") ").append(metric.column);if(having.length()>0)having.append(" OR ");having.append(metric.column).append("<>0");}
        return sum+" FROM ("+actual+" UNION ALL "+legacy+") amounts GROUP BY user_id HAVING "+having;
    }
    public void streamScores(CacheBoard board,int size,int timeoutSeconds,Consumer<Map<String,Double>> batch) {
        MapSqlParameterSource parameters=parameters(board.getScope(),board.getDayStart(),board.getCycleStart());
        String sql=totalsSql(board.getScope(),parameters,"")+" ORDER BY user_id";
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds);
        jdbc.execute(sql,parameters,statement->{
            // Connector/J 的前向只读流式结果不要求全局开启 useCursorFetch。
            statement.setFetchSize(Integer.MIN_VALUE);
            int queryTimeout=statement.getQueryTimeout();
            statement.setQueryTimeout(queryTimeout>0?Math.min(queryTimeout,timeoutSeconds):timeoutSeconds);
            Connection connection=statement.getConnection();int previousTimeout=connection.getNetworkTimeout();
            int readTimeout=Math.toIntExact(TimeUnit.SECONDS.toMillis(timeoutSeconds));
            if(previousTimeout>0)readTimeout=Math.min(previousTimeout,readTimeout);
            connection.setNetworkTimeout(Runnable::run,readTimeout);
            try(ResultSet rs=statement.executeQuery()) {
                Map<String,Double> scores=new LinkedHashMap<>();String column=RankMetric.of(board.getRankType()).column;
                while(rs.next()) {
                    checkDeadline(deadline);
                    double score=rs.getDouble(column);check(score,board.getRankType());scores.put(rs.getString("user_id"),score);
                    if(scores.size()==size){batch.accept(scores);scores=new LinkedHashMap<>();}
                }
                checkDeadline(deadline);
                if(!scores.isEmpty())batch.accept(scores);
                checkDeadline(deadline);
            } finally {connection.setNetworkTimeout(Runnable::run,previousTimeout);}
            return null;
        });
    }
    private void checkDeadline(long deadline){if(Thread.currentThread().isInterrupted()||System.nanoTime()-deadline>=0)throw RankException.unavailable();}
    public Map<String,Double> scores(CacheBoard board,Collection<String> users) {
        if(users.isEmpty())return Collections.emptyMap();
        MapSqlParameterSource parameters=parameters(board.getScope(),board.getDayStart(),board.getCycleStart()).addValue("users",users);
        Map<String,Double> scores=new LinkedHashMap<>();
        jdbc.query(totalsSql(board.getScope(),parameters," AND user_id IN (:users)"),parameters,rs->{double score=rs.getDouble(RankMetric.of(board.getRankType()).column);check(score,board.getRankType());scores.put(rs.getString("user_id"),score);});return scores;
    }
    public Set<String> affectedUsers(CacheBoard board,List<LedgerStore.Counter> counters) {
        List<Long> ids=new ArrayList<>();
        for(LedgerStore.Counter counter:counters)if((counter.getRankType()==0&&counter.getPeriodStart()==board.getDayStart())||(counter.getRankType()==1&&counter.getPeriodStart()==board.getCycleStart()))ids.add(counter.getId());
        if(ids.isEmpty())return Collections.emptySet();
        MapSqlParameterSource parameters=parameters(board.getScope(),board.getDayStart(),board.getCycleStart()).addValue("ids",ids);
        return new LinkedHashSet<>(jdbc.queryForList("SELECT DISTINCT user_id FROM score_total WHERE id IN (:ids)"+filters(board.getScope(),parameters),parameters,String.class));
    }
    public Map<String,RankEntry> entries(RankScope scope,Collection<String> users,long day,long cycle) {
        if(users.isEmpty())return Collections.emptyMap();
        if(users.size()>1000)throw RankException.badRequest("查询用户集合过大");
        MapSqlParameterSource parameters=parameters(scope,day,cycle).addValue("users",users);
        Map<String,RankEntry> history=new HashMap<>();
        jdbc.query("SELECT s.entry_json FROM legacy_rank_snapshot s JOIN legacy_import_batch b ON s.batch_id=b.batch_id WHERE scope_id=:scope AND user_id IN (:users) AND b.status='IMPORTED'",parameters,rs->{
            try{RankEntry entry=mapper.readValue(rs.getString(1),RankEntry.class);history.put(entry.getUserId(),entry);}catch(java.io.IOException e){throw new IllegalStateException("历史展示字段损坏",e);}
        });
        Map<String,RankEntry> result=new HashMap<>();
        jdbc.query(totalsSql(scope,parameters," AND user_id IN (:users)"),parameters,rs->{
            String user=rs.getString("user_id");RankEntry entry=history.get(user);
            if(entry==null){entry=new RankEntry();entry.setUserId(user);entry.setGame(empty(scope.getGame()));entry.setPlatform(empty(scope.getPlatform()));entry.setFaction(empty(scope.getFaction()));entry.setZhubo(empty(scope.getZhubo()));entry.setNickname("");entry.setAvatar("");entry.setValue1(0D);entry.setValue2(0D);entry.setValue3(0D);}
            for(RankMetric metric:RankMetric.values()){double score=rs.getDouble(metric.column);check(score,metric.code);entry.score(metric.code,score);}
            result.put(user,entry);
        });
        Set<String> displaySeen=new HashSet<>(),scopeSeen=new HashSet<>();
        jdbc.query("SELECT * FROM user_profile WHERE user_id IN (:users)"+filters(scope,parameters)+" ORDER BY profile_at DESC,BINARY game DESC",parameters,rs->{
            String user=rs.getString("user_id");RankEntry entry=result.get(user);if(entry==null)return;
            if(!history.containsKey(user)&&scopeSeen.add(user)) {
                entry.setGame(scope.getGame()==null?"":rs.getString("game"));entry.setPlatform(scope.getPlatform()==null?"":rs.getString("platform"));
                entry.setFaction(scope.getFaction()==null?"":rs.getString("faction"));entry.setZhubo(scope.getZhubo()==null?"":rs.getString("zhubo"));
            }
            long created=rs.getLong("created_at"),updated=rs.getLong("updated_at");
            if(entry.getCreatedAt()==null||created<epoch(entry.getCreatedAt()))entry.setCreatedAt(time(created));
            if(entry.getUpdatedAt()==null||updated>epoch(entry.getUpdatedAt()))entry.setUpdatedAt(time(updated));
            if(rs.getLong("profile_at")>0&&displaySeen.add(user)){String nickname=rs.getString("nickname"),avatar=rs.getString("avatar");if(nickname!=null)entry.setNickname(nickname);if(avatar!=null)entry.setAvatar(avatar);}
        });
        for(RankEntry entry:result.values())if(entry.getCreatedAt()==null||entry.getUpdatedAt()==null)throw new IllegalStateException("缺少可证明的统计创建时间");
        return result;
    }
    private void check(double value,int metric){if(!Double.isFinite(value)||value<0||(RankMetric.of(metric).integer&&(value>ScoreRules.MAX_SCORE||value!=Math.rint(value))))throw new RankException(409,40902,"累计指标超出可精确缓存范围");}
    private String empty(String value){return value==null?"":value;}
    private long epoch(String value){return OffsetDateTime.parse(value).toInstant().toEpochMilli();}
    static String time(long value){return LEGACY_TIME.format(Instant.ofEpochMilli(value).atZone(Periods.ZONE));}
}
