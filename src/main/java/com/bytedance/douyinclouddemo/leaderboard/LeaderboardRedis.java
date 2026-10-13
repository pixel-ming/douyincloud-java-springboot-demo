package com.bytedance.douyinclouddemo.leaderboard;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class LeaderboardRedis {
    private final StringRedisTemplate redis;
    private final LeaderboardProperties properties;
    private static final String CHECK="if redis.call('HGET',KEYS[2],'status')~='COMPLETE' then return {'ERROR'} end "
            +"if redis.call('HGET',KEYS[2],'empty')~='1' and redis.call('EXISTS',KEYS[1])==0 then return {'ERROR'} end ";
    private static final DefaultRedisScript<Long> WRITE=new DefaultRedisScript<>(
            "local status=redis.call('HGET',KEYS[2],'status'); if status~='BUILDING' and status~='COMPLETE' then return -1 end "
            +"if status=='COMPLETE' and redis.call('HGET',KEYS[2],'empty')~='1' and redis.call('EXISTS',KEYS[1])==0 then return -1 end "
            +"if (#ARGV-2)%2~=0 then return -1 end; local needed=0; for i=3,#ARGV,2 do local score=tonumber(ARGV[i+1]); if not score or score<0 or score==math.huge or score~=score or (ARGV[2]=='1' and (score>9007199254740991 or score~=math.floor(score))) then return -1 end; if not redis.call('ZSCORE',KEYS[1],ARGV[i]) then needed=needed+1 end end "
            +"if redis.call('ZCARD',KEYS[1])+needed>tonumber(ARGV[1]) then return -2 end "
            +"for i=3,#ARGV,2 do local old=redis.call('ZSCORE',KEYS[1],ARGV[i]); if not old or tonumber(ARGV[i+1])>tonumber(old) then redis.call('ZADD',KEYS[1],ARGV[i+1],ARGV[i]) end end "
            +"if redis.call('ZCARD',KEYS[1])>0 then redis.call('HSET',KEYS[2],'empty','0') end "
            +"if status=='BUILDING' then redis.call('EXPIRE',KEYS[1],360); redis.call('EXPIRE',KEYS[2],360) end; return 1",Long.class);
    private static final DefaultRedisScript<Long> COMPLETE=new DefaultRedisScript<>(
            "if redis.call('HGET',KEYS[2],'status')~='BUILDING' then return 0 end "
            +"redis.call('HSET',KEYS[2],'status','COMPLETE','empty',redis.call('ZCARD',KEYS[1])==0 and '1' or '0'); return 1",Long.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> TOP=new DefaultRedisScript<>(CHECK+"local result={'OK'}; local rows=redis.call('ZREVRANGE',KEYS[1],0,tonumber(ARGV[1])-1,'WITHSCORES'); for i=1,#rows do result[#result+1]=rows[i] end; return result",List.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SCORES=new DefaultRedisScript<>(CHECK+"local result={'OK'}; for i=1,#ARGV do result[#result+1]=redis.call('ZSCORE',KEYS[1],ARGV[i]) or '' end; return result",List.class);
    private static final DefaultRedisScript<String> INSTALL_MARKER=new DefaultRedisScript<>(
            "local oldtoken=redis.call('HGET',KEYS[1],'token'); if oldtoken and tonumber(oldtoken)>tonumber(ARGV[1]) then return 'STALE' end "
            +"local oldseq=redis.call('HGET',KEYS[1],'sequence'); redis.call('HSET',KEYS[1],'token',ARGV[1],'sequence',ARGV[2]); if not oldseq then return 'MISSING' elseif oldseq~=ARGV[2] then return 'MISMATCH' else return 'MATCH' end",String.class);
    private static final DefaultRedisScript<Long> MARK=new DefaultRedisScript<>(
            "if redis.call('HGET',KEYS[1],'token')~=ARGV[1] then return 0 end; redis.call('HSET',KEYS[1],'sequence',ARGV[2]);return 1",Long.class);
    private static final DefaultRedisScript<Long> ABANDON_MARKER=new DefaultRedisScript<>(
            "if redis.call('HGET',KEYS[1],'token')~=ARGV[1] or redis.call('HGET',KEYS[1],'sequence')~=ARGV[2] then return 0 end;redis.call('HSET',KEYS[1],'sequence',ARGV[3]);return 1",Long.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> READABLE=new DefaultRedisScript<>(CHECK+"return {'OK'}",List.class);
    public String key(CacheBoard board,String generation) {
        return "lb:v2:"+properties.getEnvironment()+":"+board.identity()+":g"+generation;
    }
    private List<String> keys(CacheBoard board,String generation) { String key=key(board,generation);return Arrays.asList(key,key+":meta"); }
    private String markerKey() { return "lb:v2:"+properties.getEnvironment()+":sync-marker"; }
    public void initialize(CacheBoard board,String generation) {
        Map<String,String> meta=new HashMap<>();meta.put("status","BUILDING");meta.put("empty","1");
        redis.opsForHash().putAll(key(board,generation)+":meta",meta);
        redis.expire(key(board,generation)+":meta",360,TimeUnit.SECONDS);
    }
    public void write(CacheBoard board,String generation,Map<String,? extends Number> scores) {
        if(scores.isEmpty())return;
        List<String> args=new ArrayList<>(scores.size()*2+2);args.add(Integer.toString(properties.getMaxMembers()));args.add(RankMetric.of(board.getRankType()).integer?"1":"0");
        scores.forEach((user,score)->{
            double value=score.doubleValue();
            if(!Double.isFinite(value)||value<0||(RankMetric.of(board.getRankType()).integer&&(value>ScoreRules.MAX_SCORE||value!=Math.rint(value))))throw new RankException(409,40902,"积分超出精确范围");
            args.add(user);args.add(Double.toString(value));
        });
        Long result=redis.execute(WRITE,keys(board,generation),args.toArray());
        if(result==null || result!=1)throw RankException.unavailable();
    }
    public void complete(CacheBoard board,String generation) {
        Long result=redis.execute(COMPLETE,keys(board,generation));
        if(result==null||result!=1)throw RankException.unavailable();
    }
    public void keep(CacheBoard board,String generation) {for(String key:keys(board,generation))redis.persist(key);}
    public boolean readable(CacheBoard board) {
        List<?> result=redis.execute(READABLE,keys(board,board.getActiveGeneration()));
        return result!=null&&!result.isEmpty()&&"OK".equals(result.get(0));
    }
    public Map<String,Double> top(CacheBoard board,int limit) {
        if(limit<=0||limit>1000)throw RankException.badRequest("limit必须在1到1000之间");
        List<?> rows=redis.execute(TOP,keys(board,board.getActiveGeneration()),Integer.toString(limit));
        check(rows);
        Map<String,Double> result=new LinkedHashMap<>();
        for(int i=1;i<rows.size();i+=2)result.put(rows.get(i).toString(),Double.valueOf(rows.get(i+1).toString()));
        return result;
    }
    public Map<String,Double> scores(CacheBoard board,Collection<String> users) {
        if(users.size()>1000)throw RankException.badRequest("用户集合过大");
        List<?> rows=redis.execute(SCORES,keys(board,board.getActiveGeneration()),users.toArray());check(rows);
        Map<String,Double> result=new LinkedHashMap<>();int i=1;
        for(String user:users){String value=rows.get(i++).toString();result.put(user,value.isEmpty()?0D:Double.valueOf(value));}return result;
    }
    private void check(List<?> rows) { if(rows==null||rows.isEmpty()||!"OK".equals(rows.get(0)))throw RankException.unavailable(); }
    public boolean healthy(CacheStateStore.Lease lease) {
        List<Object> values=redis.opsForHash().multiGet(markerKey(),Arrays.asList("token","sequence"));
        return values.size()==2 && Long.toString(lease.getToken()).equals(values.get(0)) && Long.toString(lease.getSequence()).equals(values.get(1));
    }
    public String installMarker(CacheStateStore.Lease lease) {
        return redis.execute(INSTALL_MARKER,Collections.singletonList(markerKey()),Long.toString(lease.getToken()),Long.toString(lease.getSequence()));
    }
    public void mark(CacheStateStore.Lease lease,long sequence) {
        Long result=redis.execute(MARK,Collections.singletonList(markerKey()),Long.toString(lease.getToken()),Long.toString(sequence));
        if(result==null||result!=1)throw RankException.unavailable();
    }
    public boolean abandonMarker(CacheStateStore.Lease lease,long sequence) {
        Long result=redis.execute(ABANDON_MARKER,Collections.singletonList(markerKey()),Long.toString(lease.getToken()),Long.toString(sequence),Long.toString(lease.getSequence()));
        return result!=null&&result==1;
    }
    public void drop(CacheBoard board,String generation) { if(generation!=null)redis.unlink(keys(board,generation)); }
    public void retire(CacheBoard board,String generation) {
        if(generation==null)return;
        String key=key(board,generation);redis.opsForHash().put(key+":meta","status","RETIRED");
        redis.expire(key,30,TimeUnit.SECONDS);redis.expire(key+":meta",30,TimeUnit.SECONDS);
    }
}
