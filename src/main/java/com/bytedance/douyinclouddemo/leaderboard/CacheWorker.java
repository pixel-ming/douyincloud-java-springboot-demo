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
    private final RetentionStore retention;
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
            CacheStateStore.Lease lease=states.acquire(owner);if(lease==null){redis.resetCleanup();return;}
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
                    if(board.ready()&&!redis.readable(board))states.requestRebuild(board);
                }
                healthAfter=System.currentTimeMillis()+5000;
                lease=states.acquire(owner);if(lease==null)return;
            }
            if(System.currentTimeMillis()>=cleanupAfter) {
                cleanupAfter=System.currentTimeMillis()+60000;
                try {
                    for(CacheBoard old:states.expire(lease)) {
                        try {
                            redis.drop(old,old.getActiveGeneration());redis.drop(old,old.getBuildingGeneration());
                            states.confirmExpiredRemoval(old,lease);
                        } catch(RuntimeException e) {log.warn("排行榜旧缓存回收延期：scopeId={}, exception={}",old.getScope().id(),e.getClass().getSimpleName());}
                    }
                    redis.repairExpirations(states.cleanupTime(lease),properties.getBatchSize(),states.enabled());
                    states.purgeExpired(lease);
                } catch(RuntimeException e) {log.warn("排行榜缓存清理延期：exception={}",e.getClass().getSimpleName());}
                lease=states.acquire(owner);if(lease==null)return;
                if(properties.isDataCleanupEnabled()) {
                    try {
                        int removed=states.cleanBusinessData(lease,retention);
                        if(removed>0)log.info("排行榜历史数据清理完成：count={}",removed);
                    } catch(RuntimeException e) {log.warn("排行榜历史数据清理延期：exception={}",e.getClass().getSimpleName());}
                    lease=states.acquire(owner);if(lease==null)return;
                }
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
            if(!board.ready() && board.getBuildingGeneration()==null)continue;
            // 由 MySQL 的旧维度比较规则判断影响范围，避免 Java 字符串比较漏同步。
            Set<String> users=ranks.affectedUsers(board,pending);
            if(users.isEmpty())continue;
            Map<String,Double> totals=ranks.scores(board,users);
            if(totals.size()!=users.size())throw new IllegalStateException("待同步用户缺少权威累计");
            if(board.ready()) {
                try{redis.write(board,board.getActiveGeneration(),totals);}catch(LeaderboardRedis.InvalidCacheException e){states.requestRebuild(board);throw e;}
            }
            if(board.getBuildingGeneration()!=null) {
                try{redis.write(board,board.getBuildingGeneration(),totals);}catch(RuntimeException e){states.fail(board,board.getBuildingGeneration(),lease);throw e;}
            }
        }
        long sequence=Math.addExact(lease.getSequence(),1);redis.mark(lease,sequence);
        if(!states.acknowledge(lease,pending,sequence) && !redis.abandonMarker(lease,sequence))throw RankException.unavailable();
    }
    private void rebuild(CacheBoard board,CacheStateStore.Lease lease) {
        String generation=UUID.randomUUID().toString();boolean published=false;long started=System.nanoTime();
        try {
            redis.initialize(board,generation);
            if(!states.begin(board,generation,lease))return;
            // 先回收失效版本，再覆盖数据库引用；失败仍保留引用供下次重试。
            redis.retire(board,board.getActiveGeneration());
            log.info("排行榜缓存构建开始：scopeId={}, rankType={}, dayStart={}, cycleStart={}, generation={}",board.getScope().id(),board.getRankType(),board.getDayStart(),board.getCycleStart(),generation);
            String first=snapshot(board,generation);
            String handoff=snapshot(board,generation);
            // Redis 完整标记先完成，数据库指针后切换；两者不假装是分布式事务。
            redis.complete(board,generation);
            published=states.publish(board,generation,lease,first+";"+handoff);
            if(!published)throw RankException.unavailable();
            log.info("排行榜缓存构建成功：scopeId={}, rankType={}, generation={}, elapsedMs={}",board.getScope().id(),board.getRankType(),generation,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        } catch(RuntimeException e) {
            log.warn("排行榜缓存构建失败：scopeId={}, rankType={}, generation={}, elapsedMs={}, exception={}",board.getScope().id(),board.getRankType(),generation,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),e.getClass().getSimpleName());
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
        long started=System.nanoTime();int[] counts={0,0};
        String evidence=tx.execute(status->{
            String[] digest={""};String snapshotId=UUID.randomUUID().toString();
            ranks.streamScores(board,properties.getBatchSize(),properties.getSnapshotTimeoutSeconds(),page->{
                counts[0]+=page.size();if(counts[0]>properties.getMaxMembers())throw new RankException(503,50304,"榜单成员超出已配置容量");
                redis.write(board,generation,page);
                counts[1]++;
                for(Map.Entry<String,Double> row:page.entrySet())digest[0]=ScoreRules.sha256(digest[0]+":"+ScoreRules.sha256(row.getKey())+":"+row.getValue());
            });
            return snapshotId+":"+counts[0]+":"+digest[0];
        });
        log.info("排行榜快照读取完成：scopeId={}, rankType={}, generation={}, members={}, batches={}, aggregateQueries=1, elapsedMs={}",board.getScope().id(),board.getRankType(),generation,counts[0],counts[1],TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        return evidence;
    }
    @PreDestroy
    public void stop() { builder.shutdownNow();if(properties.isWorkerEnabled()){try{states.release(owner);}catch(RuntimeException ignored){log.warn("排行榜关闭时负责人释放延期：ownerId={}",owner);}} }
    String ownerId() { return owner; }
    boolean canCoordinate() { return System.currentTimeMillis()>=errorAfter; }
}
