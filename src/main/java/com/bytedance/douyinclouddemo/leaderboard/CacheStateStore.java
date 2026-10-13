package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class CacheStateStore {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final ObjectMapper mapper;
    private final LeaderboardProperties properties;
    private final LedgerStore ledger;
    private volatile boolean environmentValidated;
    @Value
    public static class Lease { String owner; long token,registry,sequence,until; }

    private long now() { return jdbc.queryForObject("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED)",Long.class); }
    private Lease lockControl() {
        // 一个物理数据库只服务一个环境，防止 dev/prod 对同一事实和缓存登记分别领取负责人。
        jdbc.update("INSERT INTO cache_sync_control(environment) VALUES(?) ON DUPLICATE KEY UPDATE environment=environment",properties.getEnvironment());
        return jdbc.queryForObject("SELECT * FROM cache_sync_control WHERE environment=? FOR UPDATE",(rs,n)->new Lease(rs.getString("owner_id"),rs.getLong("fence_token"),rs.getLong("registry_revision"),rs.getLong("confirmed_batch_seq"),rs.getLong("lease_until")),properties.getEnvironment());
    }
    public void validateEnvironment() {
        if(environmentValidated)return;
        synchronized(this) {
            if(environmentValidated)return;
            new TransactionTemplate(transactions).execute(tx->{lockControl();return null;});
            environmentValidated=true;
        }
    }
    public void release(String owner) {
        jdbc.update("UPDATE cache_sync_control SET lease_until=0 WHERE environment=? AND owner_id=?",properties.getEnvironment(),owner);
    }
    public Lease acquire(String owner) {
        return new TransactionTemplate(transactions).execute(tx->{
            Lease old=lockControl();long now=now();
            if(old.getUntil()>now && !old.getOwner().equals(owner)) return null;
            boolean takeover=old.getUntil()<=now || !old.getOwner().equals(owner);
            long token=takeover?Math.addExact(old.getToken(),1):old.getToken();
            long until=old.getUntil();
            if(takeover || old.getUntil()-now<=20000) {
                until=now+30000;jdbc.update("UPDATE cache_sync_control SET owner_id=?,fence_token=?,lease_until=? WHERE environment=?",owner,token,until,properties.getEnvironment());
            }
            if(takeover) {
                int abandoned=jdbc.update("UPDATE rank_cache_state SET status='REQUESTED',building_generation=NULL,build_token=NULL,retry_after=0 WHERE building_generation IS NOT NULL");
                if(abandoned>0) {
                    jdbc.update("UPDATE cache_sync_control SET registry_revision=registry_revision+1 WHERE environment=?",properties.getEnvironment());
                    return new Lease(owner,token,old.getRegistry()+1,old.getSequence(),until);
                }
            }
            return new Lease(owner,token,old.getRegistry(),old.getSequence(),until);
        });
    }
    public CacheBoard ensure(RankScope scope,long day,long cycle,int metric) {
        List<CacheBoard> existing=find(scope,day,cycle,metric);
        if(!existing.isEmpty())return existing.get(0);
        new TransactionTemplate(transactions).execute(tx->{
            lockControl();
            if(!find(scope,day,cycle,metric).isEmpty())return null;
            Integer boards=jdbc.queryForObject("SELECT COUNT(*) FROM rank_cache_state WHERE status<>'EXPIRED' AND period_end>?",Integer.class,now());
            if(boards!=null&&boards>=properties.getMaxBoards())throw RankException.unavailable();
            long start=RankMetric.of(metric).daily?day:cycle;
            long end=Math.min(Periods.end(0,day),Periods.end(1,cycle));
            jdbc.update("INSERT INTO rank_cache_state(scope_id,scope_json,rank_type,period_start,period_end,day_start,cycle_start,status) VALUES(?,?,?,?,?,?,?,'REQUESTED')",scope.id(),json(scope),metric,start,end,day,cycle);
            jdbc.update("UPDATE cache_sync_control SET registry_revision=registry_revision+1 WHERE environment=?",properties.getEnvironment());
            return null;
        });
        return find(scope,day,cycle,metric).get(0);
    }
    public List<CacheBoard> find(RankScope scope,long day,long cycle,int metric) {
        return jdbc.query("SELECT * FROM rank_cache_state WHERE scope_id=? AND day_start=? AND cycle_start=? AND rank_type=?",this::board,scope.id(),day,cycle,metric);
    }
    public List<CacheBoard> enabled() { return jdbc.query("SELECT * FROM rank_cache_state WHERE status<>'EXPIRED'",this::board); }
    public Optional<CacheBoard> candidate() {
        List<CacheBoard> rows=jdbc.query("SELECT * FROM rank_cache_state WHERE status='REQUESTED' AND retry_after<=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED) ORDER BY (rank_type IN (0,1)) DESC,period_start DESC,rank_type LIMIT 1",this::board);
        return rows.isEmpty()?Optional.empty():Optional.of(rows.get(0));
    }
    public boolean begin(CacheBoard board,String generation,Lease lease) {
        Boolean result=new TransactionTemplate(transactions).execute(tx->{
            Lease current=lockControl();if(!valid(current,lease))return false;
            int rows=jdbc.update("UPDATE rank_cache_state SET building_generation=?,build_token=?,status='BUILDING',snapshot_evidence=NULL WHERE scope_id=? AND rank_type=? AND day_start=? AND cycle_start=? AND status='REQUESTED'",generation,lease.getToken(),board.getScope().id(),board.getRankType(),board.getDayStart(),board.getCycleStart());
            if(rows==1)jdbc.update("UPDATE cache_sync_control SET registry_revision=registry_revision+1 WHERE environment=?",properties.getEnvironment());return rows==1;
        });return Boolean.TRUE.equals(result);
    }
    public boolean publish(CacheBoard board,String generation,Lease lease,String evidence) {
        Boolean result=new TransactionTemplate(transactions).execute(tx->{
            Lease current=lockControl();if(!valid(current,lease))return false;
            int rows=jdbc.update("UPDATE rank_cache_state SET active_generation=?,building_generation=NULL,build_token=NULL,status='READY',snapshot_evidence=? WHERE scope_id=? AND rank_type=? AND period_start=? AND building_generation=? AND build_token=?",generation,evidence,board.getScope().id(),board.getRankType(),board.getPeriodStart(),generation,lease.getToken());
            if(rows==1)jdbc.update("UPDATE cache_sync_control SET registry_revision=registry_revision+1 WHERE environment=?",properties.getEnvironment());return rows==1;
        });return Boolean.TRUE.equals(result);
    }
    public void fail(CacheBoard board,String generation,Lease lease) {
        new TransactionTemplate(transactions).execute(tx->{
            Lease current=lockControl();if(!valid(current,lease))return null;
            int rows=jdbc.update("UPDATE rank_cache_state SET building_generation=NULL,build_token=NULL,status='REQUESTED',retry_after=? WHERE scope_id=? AND rank_type=? AND period_start=? AND building_generation=? AND build_token=?",now()+5000,board.getScope().id(),board.getRankType(),board.getPeriodStart(),generation,lease.getToken());
            if(rows>0)jdbc.update("UPDATE cache_sync_control SET registry_revision=registry_revision+1 WHERE environment=?",properties.getEnvironment());return null;
        });
    }
    public boolean acknowledge(Lease lease,List<LedgerStore.Counter> counters,long sequence) {
        Boolean result=new TransactionTemplate(transactions).execute(tx->{
            Lease current=lockControl();if(!valid(current,lease)||current.getRegistry()!=lease.getRegistry()||current.getSequence()!=lease.getSequence())return false;
            ledger.acknowledge(counters);
            jdbc.update("UPDATE cache_sync_control SET confirmed_batch_seq=? WHERE environment=?",sequence,properties.getEnvironment());return true;
        });return Boolean.TRUE.equals(result);
    }
    public List<CacheBoard> invalidateAll(Lease lease) {
        return new TransactionTemplate(transactions).execute(tx->{
            Lease current=lockControl();if(!valid(current,lease))throw RankException.unavailable();
            List<CacheBoard> previous=enabled();
            jdbc.update("UPDATE rank_cache_state SET active_generation=NULL,building_generation=NULL,build_token=NULL,status='REQUESTED',retry_after=0 WHERE status<>'EXPIRED'");
            jdbc.update("UPDATE cache_sync_control SET registry_revision=registry_revision+1 WHERE environment=?",properties.getEnvironment());return previous;
        });
    }
    public void requestRebuild(CacheBoard board) {
        new TransactionTemplate(transactions).execute(tx->{
            lockControl();
            int changed=jdbc.update("UPDATE rank_cache_state SET active_generation=NULL,status=IF(building_generation IS NULL,'REQUESTED','BUILDING'),retry_after=0 WHERE scope_id=? AND rank_type=? AND period_start=? AND active_generation=?",
                    board.getScope().id(),board.getRankType(),board.getPeriodStart(),board.getActiveGeneration());
            if(changed>0)jdbc.update("UPDATE cache_sync_control SET registry_revision=registry_revision+1 WHERE environment=?",properties.getEnvironment());return null;
        });
    }
    public boolean reserveOpenapi(Lease lease) {
        Boolean result=new TransactionTemplate(transactions).execute(tx->{
            Lease current=lockControl();if(!valid(current,lease))return false;
            return jdbc.update("UPDATE cache_sync_control SET next_openapi_at=? WHERE environment=? AND next_openapi_at<=?",now()+110,properties.getEnvironment(),now())==1;
        });return Boolean.TRUE.equals(result);
    }
    public boolean confirmReplay(Lease lease,Runnable update) {
        Boolean result=new TransactionTemplate(transactions).execute(tx->{
            Lease current=lockControl();if(!valid(current,lease))return false;update.run();return true;
        });return Boolean.TRUE.equals(result);
    }
    public List<CacheBoard> expire(Lease lease) {
        List<CacheBoard> result=new TransactionTemplate(transactions).execute(tx->{
            Lease current=lockControl();if(!valid(current,lease))return Collections.<CacheBoard>emptyList();
            long cutoff=now()-30000;
            List<CacheBoard> old=jdbc.query("SELECT * FROM rank_cache_state WHERE period_end<? AND status<>'EXPIRED'",this::board,cutoff);
            if(!old.isEmpty()) {
                jdbc.update("UPDATE rank_cache_state SET status='EXPIRED',active_generation=NULL,building_generation=NULL,build_token=NULL WHERE period_end<? AND status<>'EXPIRED'",cutoff);
                jdbc.update("UPDATE cache_sync_control SET registry_revision=registry_revision+1 WHERE environment=?",properties.getEnvironment());
            }return old;
        });return result==null?Collections.emptyList():result;
    }
    private boolean valid(Lease current,Lease expected) {
        return current.getOwner().equals(expected.getOwner()) && current.getToken()==expected.getToken() && current.getUntil()>now();
    }
    private CacheBoard board(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        CacheBoard b=new CacheBoard();
        try {b.setScope(mapper.readValue(rs.getString("scope_json"),RankScope.class));}
        catch(java.io.IOException e){throw new IllegalStateException("榜单范围存储损坏",e);}
        b.setRankType(rs.getInt("rank_type"));b.setPeriodStart(rs.getLong("period_start"));b.setPeriodEnd(rs.getLong("period_end"));
        b.setDayStart(rs.getLong("day_start"));b.setCycleStart(rs.getLong("cycle_start"));
        b.setActiveGeneration(rs.getString("active_generation"));b.setBuildingGeneration(rs.getString("building_generation"));
        b.setBuildToken((Long)rs.getObject("build_token"));b.setStatus(rs.getString("status"));return b;
    }
    private String json(Object value) {try{return mapper.writeValueAsString(value);}catch(java.io.IOException e){throw new IllegalStateException(e);}}
}
