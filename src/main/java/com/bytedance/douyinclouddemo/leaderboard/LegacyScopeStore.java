package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@RequiredArgsConstructor
public class LegacyScopeStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Map<String,RankScope> resolved=new ConcurrentHashMap<>();
    private Object[] values(RankScope scope){return new Object[]{empty(scope.getGame()),empty(scope.getPlatform()),empty(scope.getFaction()),empty(scope.getZhubo())};}
    private String empty(String value){return value==null?"":value;}
    public RankScope find(RankScope scope) {
        RankScope cached=resolved.get(scope.id());if(cached!=null)return cached;
        List<String> rows=jdbc.query("SELECT scope_json FROM legacy_rank_scope WHERE game=? AND platform=? AND faction=? AND zhubo=?",(rs,n)->rs.getString(1),values(scope));
        if(rows.isEmpty())return null;
        if(rows.size()!=1)throw new IllegalStateException("旧查询范围目录不唯一");
        try {
            RankScope canonical=mapper.readValue(rows.get(0),RankScope.class);
            if(TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){remember(scope,canonical);}});
            } else remember(scope,canonical);
            return canonical;
        }catch(java.io.IOException e){throw new IllegalStateException("旧查询范围目录损坏",e);}
    }
    private void remember(RankScope scope,RankScope canonical){if(resolved.size()>=4096)resolved.clear();resolved.put(scope.id(),canonical);}
    public RankScope register(RankScope scope) {
        RankScope cached=resolved.get(scope.id());if(cached!=null)return cached;
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("范围登记必须与迁移在同一事务");
        Object[] fields=values(scope);
        try {
            jdbc.update("INSERT INTO legacy_rank_scope(scope_id,game,platform,faction,zhubo,scope_json) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE scope_id=scope_id",scope.id(),fields[0],fields[1],fields[2],fields[3],mapper.writeValueAsString(scope));
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
        RankScope canonical=find(scope);if(canonical==null)throw new IllegalStateException("范围登记未返回结果");return canonical;
    }
    public void registerApp(LeaderboardProperties.App app) {
        for(int mask=0;mask<8;mask++) {
            String game=(mask&4)==0?null:app.getGame(),platform=(mask&2)==0?null:app.getPlatform(),faction=(mask&1)==0?null:app.getFaction();
            register(new RankScope(game,platform,faction,null));
            if(!app.getZhubo().isEmpty())register(new RankScope(game,platform,faction,app.getZhubo()));
        }
    }
}
