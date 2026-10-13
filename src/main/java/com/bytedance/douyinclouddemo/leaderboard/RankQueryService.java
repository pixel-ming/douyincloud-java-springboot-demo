package com.bytedance.douyinclouddemo.leaderboard;

import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
public class RankQueryService {
    private final CacheStateStore states;
    private final LeaderboardRedis redis;
    private final RankDataStore ranks;
    private final MigrationStore migrations;
    private final LegacyScopeStore scopes;
    private final Clock clock;
    private final Map<String,Saved> results=new ConcurrentHashMap<>();
    private final Map<String,CompletableFuture<List<RankEntry>>> flights=new ConcurrentHashMap<>();
    @Value
    private static class Saved { long createdAt; List<RankEntry> rows; }

    public List<RankEntry> query(RankRequest request) {
        int type=request.rankType();RankScope requested=request.scope();RankScope scope=scopes.find(requested);long now=clock.millis();
        if(scope==null){migrations.assertQueryable(requested);return Collections.emptyList();}
        long day=Periods.day(now),cycle=Periods.cycle(now);
        String key=scope.id()+":"+type+":"+request.getLimit()+":"+day+":"+cycle;
        Saved previous=results.get(key);
        if(previous!=null && now-previous.createdAt<=1000)return previous.rows;
        CompletableFuture<List<RankEntry>> own=new CompletableFuture<>();
        CompletableFuture<List<RankEntry>> existing=flights.putIfAbsent(key,own);
        if(existing!=null) {
            try{return existing.get(2,TimeUnit.SECONDS);}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw RankException.unavailable();}
            catch(ExecutionException|TimeoutException e){return fallback(previous);}
        }
        try {
            migrations.assertQueryable(scope);
            List<RankEntry> rows=fresh(scope,type,request.getLimit(),day,cycle);
            if(results.size()>=256)results.entrySet().removeIf(entry->clock.millis()-entry.getValue().createdAt>30000);
            if(results.size()>=256) {Iterator<String> oldest=results.keySet().iterator();if(oldest.hasNext())results.remove(oldest.next());}
            results.put(key,new Saved(clock.millis(),rows));own.complete(rows);return rows;
        } catch(RuntimeException e) {
            own.completeExceptionally(e);
            return fallback(previous);
        } finally {flights.remove(key,own);}
    }
    private List<RankEntry> fallback(Saved previous) {
        if(previous!=null && clock.millis()-previous.createdAt<=30000)return previous.rows;
        throw RankException.unavailable();
    }
    private List<RankEntry> fresh(RankScope scope,int type,int limit,long day,long cycle) {
        CacheBoard board=states.ensure(scope,day,cycle,type);
        if(!board.ready())throw RankException.unavailable();
        Map<String,Double> ranked;
        try {ranked=redis.top(board,limit);}catch(RuntimeException e){states.requestRebuild(board);throw e;}
        Map<String,RankEntry> entries=ranks.entries(scope,ranked.keySet(),day,cycle);
        List<RankEntry> rows=new ArrayList<>();
        for(Map.Entry<String,Double> user:ranked.entrySet()) {
            RankEntry row=entries.get(user.getKey());
            if(row==null){states.requestRebuild(board);throw RankException.unavailable();}
            row.score(type,user.getValue());
            rows.add(row);
        }
        return Collections.unmodifiableList(rows);
    }
}
