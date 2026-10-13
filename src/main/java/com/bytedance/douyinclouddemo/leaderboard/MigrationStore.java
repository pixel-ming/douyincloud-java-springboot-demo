package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class MigrationStore {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final ObjectMapper mapper;
    private final LeaderboardProperties properties;
    private final Clock clock;
    private final LegacyScopeStore scopes;

    public void guard(GiftData gift) {
        if(!"BOOKED".equals(gift.getStatus()))return;
        List<Long> cuts=jdbc.query("SELECT cutover_at FROM game_cutover WHERE game=? LOCK IN SHARE MODE",(rs,n)->rs.getLong(1),gift.getGame());
        if(cuts.size()!=1)throw RankException.unavailable();
        if(gift.getEventAt()<cuts.get(0)) {
            gift.setStatus("PENDING_REVIEW");gift.setReason("BEFORE_CUTOVER");gift.setPendingPayload(gift.getReviewPayload());
            if(gift.getPendingPayload()==null)throw new IllegalStateException("切换前消息缺少核对原文");
        } else if(scopes.find(new RankScope(gift.getGame(),gift.getPlatform(),gift.getFaction(),gift.getZhubo()))==null) {
            throw new RankException(503,50305,"可信业务分组尚未登记");
        }
    }
    public void assertQueryable(RankScope scope) {
        Set<String> games=scope.getGame()==null?configuredGames():Collections.singleton(scope.getGame());
        if(games.isEmpty())throw RankException.unavailable();
        for(String game:games) {
            Integer ready=jdbc.queryForObject("SELECT COUNT(*) FROM game_cutover WHERE game=? AND status='ACTIVE' AND cutover_at<=?",Integer.class,game,clock.millis());
            if(ready==null||ready!=1)throw RankException.unavailable();
        }
        if(scope.getGame()==null) {
            Integer partial=jdbc.queryForObject("SELECT COUNT(*) FROM legacy_import_batch WHERE status='IMPORTED' AND mode='SCOPED' AND includes_all_games=FALSE",Integer.class);
            if(partial!=null&&partial>0)throw RankException.unavailable();
        }
    }
    public Map<String,Object> begin(MigrationPlan plan) {
        validatePlan(plan);
        return new TransactionTemplate(transactions).execute(tx->{
            for(LeaderboardProperties.App app:properties.getApps().values())if(app.isEnabled()&&plan.getGames().contains(app.getGame()))scopes.registerApp(app);
            List<Map<String,Object>> previous=jdbc.queryForList("SELECT * FROM legacy_import_batch WHERE batch_id=? FOR UPDATE",plan.getBatchId());
            String planJson=json(plan);
            if(!previous.isEmpty()) {
                if(!planJson.equals(previous.get(0).get("plan_json")))throw conflict("迁移批次内容不同");
                return progress(plan.getBatchId());
            }
            // 旧原型的 ACTUAL/SCOPED 无法证明互斥，禁止静默叠加或忽略。
            Integer old=jdbc.queryForObject("SELECT COUNT(*) FROM migration_record",Integer.class);
            if(old!=null&&old>0)throw conflict("旧迁移记录须先独立核对");
            for(String game:plan.getGames()) {
                Integer occupied=jdbc.queryForObject("SELECT COUNT(*) FROM score_total WHERE game=?",Integer.class,game);
                if(occupied!=null&&occupied>0)throw conflict("初始迁移要求该游戏新累计为空");
            }
            jdbc.update("INSERT INTO legacy_import_batch(batch_id,source_id,plan_json,mode,includes_all_games,status,imported_rows,content_digest,next_page) VALUES(?,?,?,?,?,'STAGING',0,?,1)",plan.getBatchId(),plan.getSourceId(),planJson,plan.getMode(),plan.isIncludesAllGames(),ScoreRules.sha256(""));
            for(String game:plan.getGames())jdbc.update("INSERT INTO game_cutover(game,batch_id,cutover_at,status) VALUES(?,?,?,'STAGING')",game,plan.getBatchId(),plan.getCutoverAt());
            return progress(plan.getBatchId());
        });
    }
    public Map<String,Object> append(String batch,int page,List<JsonNode> rows) {
        if(page<1||rows==null||rows.isEmpty()||rows.size()>200)throw RankException.badRequest("迁移分批必须为1到200行");
        return new TransactionTemplate(transactions).execute(tx->{
            Map<String,Object> state=locked(batch);MigrationPlan plan=plan(state);
            String pageDigest=ScoreRules.sha256(json(rows));
            List<String> prior=jdbc.query("SELECT content_digest FROM legacy_import_page WHERE batch_id=? AND page_no=?",(rs,n)->rs.getString(1),batch,page);
            if(!prior.isEmpty()){if(!prior.get(0).equals(pageDigest))throw conflict("重复页内容不同");return progress(batch);}
            if(!"STAGING".equals(state.get("status"))||((Number)state.get("next_page")).intValue()!=page)throw conflict("迁移页码或批次状态不符");
            if(!"SCOPED".equals(plan.getMode()))throw conflict("空基线模式不接受历史行");
            long count=((Number)state.get("imported_rows")).longValue();String digest=(String)state.get("content_digest");
            Map<RankScope,RankScope> pageScopes=new HashMap<>();
            for(JsonNode node:rows) {
                RankEntry row=validateRow(node,plan);RankScope requested=new RankScope(row.getGame(),row.getPlatform(),row.getFaction(),row.getZhubo());
                RankScope scope=pageScopes.computeIfAbsent(requested,scopes::register);
                String payload=json(row);digest=ScoreRules.sha256(digest+":"+ScoreRules.sha256(payload));
                jdbc.update("INSERT INTO legacy_rank_snapshot(scope_id,user_id,batch_id,day_start,cycle_start,kill_sum,daily_kill_sum,points1_sum,points2_sum,points3_sum,daily_points1_sum,daily_points2_sum,daily_points3_sum,entry_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        scope.id(),row.getUserId(),batch,plan.getDayStart(),plan.getCycleStart(),row.getKillSum(),row.getDailyKillSum(),row.getPoints1Sum(),row.getPoints2Sum(),row.getPoints3Sum(),row.getDailyPoints1Sum(),row.getDailyPoints2Sum(),row.getDailyPoints3Sum(),payload);
                count++;
            }
            if(count>plan.getExpectedRows())throw conflict("迁移条数超过清单");
            jdbc.update("INSERT INTO legacy_import_page(batch_id,page_no,content_digest) VALUES(?,?,?)",batch,page,pageDigest);
            jdbc.update("UPDATE legacy_import_batch SET imported_rows=?,content_digest=?,next_page=? WHERE batch_id=?",count,digest,page+1,batch);
            return progress(batch);
        });
    }
    public Map<String,Object> finish(String batch) {
        return new TransactionTemplate(transactions).execute(tx->{
            Map<String,Object> state=locked(batch);MigrationPlan plan=plan(state);
            if("IMPORTED".equals(state.get("status")))return progress(batch);
            if(((Number)state.get("imported_rows")).longValue()!=plan.getExpectedRows()||!plan.getExpectedDigest().equals(state.get("content_digest")))throw conflict("迁移条数或摘要不一致");
            if(plan.getCutoverAt()>clock.millis())throw conflict("切换时间尚未到达");
            // 与缓存登记/确认共用协调锁，批次可见前废弃全部旧 generation。
            if(jdbc.queryForList("SELECT id FROM cache_sync_control WHERE environment=? FOR UPDATE",properties.getEnvironment()).size()!=1)throw RankException.unavailable();
            jdbc.update("UPDATE rank_cache_state SET active_generation=NULL,building_generation=NULL,build_token=NULL,status='REQUESTED',retry_after=0 WHERE status<>'EXPIRED'");
            jdbc.update("UPDATE cache_sync_control SET registry_revision=registry_revision+1 WHERE environment=?",properties.getEnvironment());
            jdbc.update("UPDATE legacy_import_batch SET status='IMPORTED' WHERE batch_id=?",batch);
            jdbc.update("UPDATE game_cutover SET status='ACTIVE' WHERE batch_id=?",batch);
            return progress(batch);
        });
    }
    public Map<String,Object> progress(String batch) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT batch_id,status,imported_rows,content_digest,next_page FROM legacy_import_batch WHERE batch_id=?",batch);
        if(rows.isEmpty())throw RankException.badRequest("迁移批次不存在");return rows.get(0);
    }
    private Map<String,Object> locked(String batch){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM legacy_import_batch WHERE batch_id=? FOR UPDATE",batch);if(rows.isEmpty())throw RankException.badRequest("迁移批次不存在");return rows.get(0);}
    private MigrationPlan plan(Map<String,Object> row){try{return mapper.readValue((String)row.get("plan_json"),MigrationPlan.class);}catch(java.io.IOException e){throw new IllegalStateException("迁移清单损坏",e);}}
    void validatePlan(MigrationPlan p) {
        if(p==null)throw RankException.badRequest("缺少迁移清单");
        ScoreRules.identifier(p.getBatchId(),64,false);ScoreRules.identifier(p.getSourceId(),128,false);ScoreRules.identifier(p.getEvidence(),2048,false);
        if(!p.isLegacyFrozen()||(!"SCOPED".equals(p.getMode())&&!"EMPTY".equals(p.getMode())))throw RankException.badRequest("仅支持已冻结的SCOPED或确认无历史的EMPTY基线");
        if(p.getExpectedRows()==null||p.getExpectedRows()<0||p.getExpectedDigest()==null||!p.getExpectedDigest().matches("[a-f0-9]{64}"))throw RankException.badRequest("缺少迁移条数及摘要");
        if("EMPTY".equals(p.getMode())&&(p.getExpectedRows()!=0||!ScoreRules.sha256("").equals(p.getExpectedDigest())))throw RankException.badRequest("空基线清单不符");
        if(p.getGames()==null||p.getGames().isEmpty()||!configuredGames().containsAll(p.getGames()))throw RankException.badRequest("游戏清单须来自已配置可信应用");
        if(p.isIncludesAllGames()&&!configuredGames().equals(p.getGames()))throw RankException.badRequest("ALL快照必须覆盖全部配置游戏");
        if(p.getCutoverAt()==null||p.getCutoverAt()<1451606400000L||p.getDayStart()==null||p.getCycleStart()==null)throw RankException.badRequest("缺少可靠切换及快照周期");
        if(Periods.day(p.getCutoverAt())!=p.getDayStart()||Periods.cycle(p.getCycleStart())!=p.getCycleStart()||p.getCycleStart()>p.getCutoverAt()||Periods.end(1,p.getCycleStart())<p.getCutoverAt())throw RankException.badRequest("快照日或周期不覆盖切换点");
        if("SCOPED".equals(p.getMode())&&Periods.cycle(p.getCutoverAt())!=p.getCutoverAt())throw RankException.badRequest("历史迁移须在已确认的周期边界切换");
    }
    RankEntry validateRow(JsonNode node,MigrationPlan plan) {
        String[] text={"UserId","Game","Platform","Faction","Zhubo","Nickname","Avatar","CreatedAt","UpdatedAt"};
        if(node==null||!node.isObject()||node.size()!=21)throw RankException.badRequest("历史行必须完整保留21字段");
        for(String name:text)if(!node.path(name).isTextual())throw RankException.badRequest("历史字符串或时间字段缺失");
        for(String name:new String[]{"KillSum","DailyKillSum"})if(!node.path(name).isIntegralNumber()||!node.path(name).canConvertToLong()||node.path(name).longValue()<0||node.path(name).longValue()>ScoreRules.MAX_SCORE)throw RankException.badRequest("历史整数积分超出可精确缓存范围");
        for(String name:new String[]{"Points1Sum","Points2Sum","Points3Sum","DailyPoints1Sum","DailyPoints2Sum","DailyPoints3Sum"})if(!node.path(name).isNumber()||!Double.isFinite(node.path(name).doubleValue())||node.path(name).doubleValue()<0)throw RankException.badRequest("历史排行指标缺失、为负或不可表示");
        for(String name:new String[]{"Value1","Value2","Value3"})if(!node.path(name).isNumber()||!Double.isFinite(node.path(name).doubleValue()))throw RankException.badRequest("历史保存值缺失或不可表示");
        if(!node.has("DeletedAt")||!node.get("DeletedAt").isNull())throw RankException.badRequest("导入仅接受旧查询可见的未删除统计行");
        try {
            RankEntry row=mapper.treeToValue(node,RankEntry.class);
            ScoreRules.identifier(row.getUserId(),128,false);new RankScope(row.getGame(),row.getPlatform(),row.getFaction(),row.getZhubo());
            if(row.getGame().isEmpty()?!plan.isIncludesAllGames():!plan.getGames().contains(row.getGame()))throw RankException.badRequest("历史行超出清单游戏范围");
            OffsetDateTime.parse(row.getCreatedAt());OffsetDateTime.parse(row.getUpdatedAt());
            return row;
        }catch(java.io.IOException|java.time.DateTimeException e){throw RankException.badRequest("历史字段或时间格式错误");}
    }
    private Set<String> configuredGames(){Set<String> games=new TreeSet<>();for(LeaderboardProperties.App app:properties.getApps().values())if(app.getGame()!=null&&!app.getGame().isEmpty())games.add(app.getGame());return games;}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    private RankException conflict(String message){return new RankException(409,40904,message);}
}
