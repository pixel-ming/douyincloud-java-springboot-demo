package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationStoreTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final LegacyScopeStore scopes=mock(LegacyScopeStore.class);
    private final LeaderboardProperties properties=new LeaderboardProperties();
    private final MigrationStore store=new MigrationStore(jdbc,Fixtures.transactions(),Fixtures.mapper(),properties,new Fixtures.MutableClock(),scopes);
    private final MigrationPlan plan=new MigrationPlan();
    private MigrationPlan historyPlan(String cutover) {
        properties.getApps().put("main",Fixtures.app());
        MigrationPlan history=new MigrationPlan();history.setBatchId("history");history.setSourceId("snapshot");
        history.setEvidence("旧服务已停止写入，快照日期及周期已核对");history.setMode("SCOPED");history.setLegacyFrozen(true);
        history.setGames(Collections.singleton("BJGYB2"));history.setIncludesAllGames(true);
        history.setExpectedRows(1L);history.setExpectedDigest(ScoreRules.sha256("snapshot"));
        long time=Instant.parse(cutover).toEpochMilli();history.setCutoverAt(time);
        history.setDayStart(Periods.day(time));history.setCycleStart(Periods.cycle(time));return history;
    }
    @ParameterizedTest
    @ValueSource(strings={"2026-10-14T13:55:51Z","2026-10-19T17:59:59.999Z","2026-10-19T18:00:00Z","2026-10-19T18:00:00.001Z"})
    void acceptsFrozenHistoryInsideCycleAndAtBoundary(String cutover){
        assertDoesNotThrow(()->store.validatePlan(historyPlan(cutover)));
        verifyNoInteractions(jdbc,scopes);
    }
    @Test void rejectsSnapshotWithWrongDayOrCycle(){
        MigrationPlan history=historyPlan("2026-10-14T13:55:51Z");
        history.setDayStart(Periods.day(history.getCutoverAt()-86400000L));
        assertThrows(RankException.class,()->store.validatePlan(history));
        history.setDayStart(Periods.day(history.getCutoverAt()));history.setCycleStart(history.getCycleStart()+1);
        assertThrows(RankException.class,()->store.validatePlan(history));
    }
    @Test void rejectsPreviousCycleAtExactResetBoundary(){
        MigrationPlan history=historyPlan("2026-10-19T18:00:00Z");
        history.setCycleStart(Periods.cycle(history.getCutoverAt()-1));
        assertThrows(RankException.class,()->store.validatePlan(history));
    }
    @Test void stillRejectsHistoryWithoutFreezeConfirmation(){
        MigrationPlan history=historyPlan("2026-10-14T13:55:51Z");history.setLegacyFrozen(false);
        assertThrows(RankException.class,()->store.validatePlan(history));
    }
    private JsonNode row() {
        plan.setGames(Collections.singleton("BJGYB2"));
        RankEntry entry=new RankEntry();entry.setUserId("u1");entry.setGame("BJGYB2");entry.setPlatform("DYGYB2");entry.setFaction("红方");entry.setZhubo("");
        entry.setNickname("用户");entry.setAvatar("");entry.setKillSum(100L);entry.setDailyKillSum(100L);
        entry.setPoints1Sum(1.25D);entry.setPoints2Sum(0D);entry.setPoints3Sum(0D);
        entry.setDailyPoints1Sum(0D);entry.setDailyPoints2Sum(0D);entry.setDailyPoints3Sum(0D);
        entry.setValue1(-3D);entry.setValue2(0D);entry.setValue3(0D);
        entry.setCreatedAt("2026-10-13T00:00:00+08:00");entry.setUpdatedAt(entry.getCreatedAt());
        return Fixtures.mapper().valueToTree(entry);
    }
    @Test void preservesRealLegacyValuesAndRejectsNegativeRankScores(){
        JsonNode source=row();RankEntry imported=store.validateRow(source,plan);
        assertEquals(100L,imported.getKillSum());assertEquals(1.25D,imported.getPoints1Sum());assertEquals(-3D,imported.getValue1());
        ((com.fasterxml.jackson.databind.node.ObjectNode)source).put("Points1Sum",-1D);
        assertThrows(RankException.class,()->store.validateRow(source,plan));
    }
    @Test void cannotSilentlyReplaceMissingHistoryWithZero(){
        JsonNode source=row();((com.fasterxml.jackson.databind.node.ObjectNode)source).remove("Points1Sum");
        assertThrows(RankException.class,()->store.validateRow(source,plan));
    }
    @Test void beforeCutoverStaysPendingAndMissingRegisteredScopeFailsClosed(){
        GiftData gift=new GiftData();gift.setStatus("BOOKED");gift.setGame("BJGYB2");gift.setPlatform("DYGYB2");gift.setFaction("红方");gift.setZhubo("");gift.setEventAt(Fixtures.NOW);gift.setReviewPayload("retained");
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any(),eq("BJGYB2"))).thenReturn(Collections.singletonList(Fixtures.NOW+1));store.guard(gift);assertEquals("PENDING_REVIEW",gift.getStatus());verifyNoInteractions(scopes);
        gift.setStatus("BOOKED");gift.setEventAt(Fixtures.NOW+1);assertEquals(50305,assertThrows(RankException.class,()->store.guard(gift)).getCode());
    }
    @Test void importingAliasUsesCanonicalIdButKeepsOriginalResponseFields()throws Exception{
        JsonNode source=row();RankScope canonical=new RankScope("bjgyb2","dygyb2","红方",null);when(scopes.register(any())).thenReturn(canonical);
        plan.setMode("SCOPED");plan.setExpectedRows(1L);plan.setDayStart(Periods.day(Fixtures.NOW));plan.setCycleStart(Periods.cycle(Fixtures.NOW));
        Map<String,Object> state=new HashMap<>();state.put("plan_json",Fixtures.mapper().writeValueAsString(plan));state.put("status","STAGING");state.put("next_page",1);state.put("imported_rows",0L);state.put("content_digest",ScoreRules.sha256(""));
        when(jdbc.queryForList(anyString(),eq("test"))).thenReturn(Collections.singletonList(state));
        store.append("test",1,Collections.singletonList(source));
        verify(jdbc).update(startsWith("INSERT INTO legacy_rank_snapshot"),eq(canonical.id()),eq("u1"),eq("test"),anyLong(),anyLong(),eq(100L),eq(100L),eq(1.25D),eq(0D),eq(0D),eq(0D),eq(0D),eq(0D),contains("\"Game\":\"BJGYB2\""));
    }
}
