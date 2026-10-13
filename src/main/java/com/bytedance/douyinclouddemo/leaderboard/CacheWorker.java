package com.bytedance.douyinclouddemo.leaderboard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWorker {
    private final CacheStateStore states;
    private final LedgerStore ledger;
    private final RankDataStore ranks;
    private final LeaderboardRedis redis;
    private final PlatformTransactionManager transactions;
    private final LeaderboardProperties properties;
    private final CacheWarmup warmup;
    private final String owner=UUID.randomUUID().toString();
    private final ExecutorService builder=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"leaderboard-rebuild");t.setDaemon(true);return t;});
    private final AtomicBoolean building=new AtomicBoolean();
    private long cursor;
    private volatile long errorAfter;
    private long cleanupAfter;
    private long healthAfter;
    private int consecutiveFailures;

    @Scheduled(fixedDelayString="${leaderboard.poll-millis:200}")
    public void tick() {
        if(!properties.isWorkerEnabled() || System.currentTimeMillis()<errorAfter)return;
        try {
            CacheStateStore.Lease lease=states.acquire(owner);if(lease==null)return;
            if(!redis.healthy(lease)) {
                // 先持久化失效，再推进健康标记；中途退出不能把旧快照标记成最新。
                List<CacheBoard> previous=states.invalidateAll(lease);
                String marker=redis.installMarker(lease);
                if("STALE".equals(marker)||marker==null)throw RankException.unavailable();
                for(CacheBoard b:previous){redis.drop(b,b.getActiveGeneration());redis.drop(b,b.getBuildingGeneration());}
                log.warn("排行榜缓存连续性恢复：environment={}, marker={}",properties.getEnvironment(),marker);
                lease=states.acquire(owner);if(lease==null)return;
            }
            if(System.currentTimeMillis()>=healthAfter) {
                for(CacheBoard board:states.enabled()) {
                    if(board.getActiveGeneration()!=null&&!redis.readable(board))states.requestRebuild(board);
                }
                healthAfter=System.currentTimeMillis()+5000;
                lease=states.acquire(owner);if(lease==null)return;
            }
            if(System.currentTimeMillis()>=cleanupAfter) {
                for(CacheBoard old:states.expire(lease)){redis.drop(old,old.getActiveGeneration());redis.drop(old,old.getBuildingGeneration());}
                cleanupAfter=System.currentTimeMillis()+60000;
                lease=states.acquire(owner);if(lease==null)return;
            }
            if(warmup.register()) {
                // 自动登记会推进 registry，同步及构建必须重新取得协调证据。
                lease=states.acquire(owner);if(lease==null)return;
            }
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(500);
            do {
                List<LedgerStore.Counter> pending=ledger.pending(cursor,properties.getBatchSize());
                if(pending.isEmpty() && cursor!=0){cursor=0;pending=ledger.pending(0,properties.getBatchSize());}
                if(pending.isEmpty())break;
                cursor=pending.get(pending.size()-1).getId();
                synchronize(lease,pending,states.enabled());
                if(pending.size()<properties.getBatchSize())break;
                lease=states.acquire(owner);if(lease==null)return;
            } while(System.nanoTime()<deadline);
            if(building.compareAndSet(false,true)) {
                boolean submitted=false;
                try {
                    Optional<CacheBoard> candidate=states.candidate();
                    if(candidate.isPresent()) {
                        CacheStateStore.Lease acquired=states.acquire(owner);
                        if(acquired!=null) {CacheBoard board=candidate.get();builder.submit(()->rebuild(board,acquired));submitted=true;}
                    }
                } finally {if(!submitted)building.set(false);}
            }
            consecutiveFailures=0;
        } catch(RuntimeException e) {
            errorAfter=System.currentTimeMillis()+5000;
            if(++consecutiveFailures>=3) {
                try{states.release(owner);}catch(RuntimeException ignored){log.warn("排行榜负责人释放延期：ownerId={}",owner);}
                errorAfter=System.currentTimeMillis()+10000;consecutiveFailures=0;
            }
            log.warn("排行榜后台同步失败：exception={}",e.getClass().getSimpleName());
        }
    }
    void synchronize(CacheStateStore.Lease lease,List<LedgerStore.Counter> pending,List<CacheBoard> boards) {
        for(CacheBoard board:boards) {
            if(board.getActiveGeneration()==null && board.getBuildingGeneration()==null)continue;
            // 由 MySQL 的旧维度比较规则判断影响范围，避免 Java 字符串比较漏同步。
            Set<String> users=ranks.affectedUsers(board,pending);
            if(users.isEmpty())continue;
            Map<String,Double> totals=ranks.scores(board,users);
            if(totals.size()!=users.size())throw new IllegalStateException("待同步用户缺少权威累计");
            if(board.getActiveGeneration()!=null) {
                try{redis.write(board,board.getActiveGeneration(),totals);}catch(RuntimeException e){states.requestRebuild(board);throw e;}
            }
            if(board.getBuildingGeneration()!=null) {
                try{redis.write(board,board.getBuildingGeneration(),totals);}catch(RuntimeException e){states.fail(board,board.getBuildingGeneration(),lease);throw e;}
            }
        }
        long sequence=Math.addExact(lease.getSequence(),1);redis.mark(lease,sequence);
        if(!states.acknowledge(lease,pending,sequence) && !redis.abandonMarker(lease,sequence))throw RankException.unavailable();
    }
    private void rebuild(CacheBoard board,CacheStateStore.Lease lease) {
        String generation=UUID.randomUUID().toString();boolean published=false;
        try {
            redis.initialize(board,generation);
            if(!states.begin(board,generation,lease))return;
            String first=snapshot(board,generation);
            String handoff=snapshot(board,generation);
            // Redis 完整标记先完成，数据库指针后切换；两者不假装是分布式事务。
            redis.complete(board,generation);
            published=states.publish(board,generation,lease,first+";"+handoff);
            if(!published)throw RankException.unavailable();
            redis.keep(board,generation);
            redis.retire(board,board.getActiveGeneration());
            log.info("排行榜缓存构建成功：scopeId={}, rankType={}, periodStart={}",board.getScope().id(),board.getRankType(),board.getPeriodStart());
        } catch(RuntimeException e) {
            log.warn("排行榜缓存构建失败：scopeId={}, exception={}",board.getScope().id(),e.getClass().getSimpleName());
            try {states.fail(board,generation,lease);}catch(RuntimeException ignored){log.warn("排行榜构建状态恢复失败：scopeId={}",board.getScope().id());}
        } finally {
            if(!published){try{redis.drop(board,generation);}catch(RuntimeException ignored){log.warn("排行榜失败缓存清理延期：scopeId={}",board.getScope().id());}}
            building.set(false);
        }
    }
    String snapshot(CacheBoard board,String generation) {
        TransactionTemplate tx=new TransactionTemplate(transactions);tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(properties.getSnapshotTimeoutSeconds());
        return tx.execute(status->{
            String after="";int members=0;String digest="";String snapshotId=UUID.randomUUID().toString();
            while(true) {
                Map<String,Double> page=ranks.pageScores(board,after,properties.getBatchSize());
                if(page.isEmpty())break;
                members+=page.size();if(members>properties.getMaxMembers())throw new RankException(503,50304,"榜单成员超出已配置容量");
                redis.write(board,generation,page);
                for(Map.Entry<String,Double> row:page.entrySet()){after=row.getKey();digest=ScoreRules.sha256(digest+":"+ScoreRules.sha256(after)+":"+row.getValue());}
                if(page.size()<properties.getBatchSize())break;
            }
            return snapshotId+":"+members+":"+digest;
        });
    }
    @PreDestroy
    public void stop() { builder.shutdownNow();if(properties.isWorkerEnabled()){try{states.release(owner);}catch(RuntimeException ignored){log.warn("排行榜关闭时负责人释放延期：ownerId={}",owner);}} }
    String ownerId() { return owner; }
    boolean canCoordinate() { return System.currentTimeMillis()>=errorAfter; }
}
