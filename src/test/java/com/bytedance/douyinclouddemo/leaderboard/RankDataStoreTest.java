package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RankDataStoreTest {
    @Nested class Streaming {
        private final DataSource source=mock(DataSource.class);
        private final Connection connection=mock(Connection.class);
        private final PreparedStatement statement=mock(PreparedStatement.class);
        private final ResultSet rows=mock(ResultSet.class);
        private final RankDataStore store=new RankDataStore(new NamedParameterJdbcTemplate(source),Fixtures.mapper());
        private final CacheBoard board=Fixtures.board(0);
        @BeforeEach void prepare()throws Exception{
            when(source.getConnection()).thenReturn(connection);when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.getConnection()).thenReturn(connection);when(statement.executeQuery()).thenReturn(rows);
        }
        @Test void oneAggregateStreamsBoundedBatchesIncludingZeroScores()throws Exception{
            when(rows.next()).thenReturn(true,true,true,false);when(rows.getString("user_id")).thenReturn("u1","u2","u3");
            when(rows.getDouble("daily_kill_sum")).thenReturn(0D,20D,30D);
            List<Map<String,Double>> batches=new ArrayList<>();store.streamScores(board,2,120,batches::add);
            assertEquals(2,batches.size());assertEquals(Arrays.asList("u1","u2"),new ArrayList<>(batches.get(0).keySet()));
            assertEquals(0D,batches.get(0).get("u1"));assertEquals(Collections.singletonMap("u3",30D),batches.get(1));
            verify(statement,times(1)).executeQuery();verify(statement).setFetchSize(Integer.MIN_VALUE);verify(statement).setQueryTimeout(120);
            org.mockito.InOrder order=inOrder(connection,rows);
            order.verify(connection).setNetworkTimeout(any(),eq(120000));order.verify(rows).close();order.verify(connection).setNetworkTimeout(any(),eq(0));
            verify(statement).close();verify(connection).close();
            ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class);verify(connection).prepareStatement(sql.capture());
            assertTrue(sql.getValue().endsWith("ORDER BY user_id"));assertFalse(sql.getValue().contains("LIMIT"));assertFalse(sql.getValue().contains("user_id>"));
            assertTrue(sql.getValue().contains("UNION ALL"));assertTrue(sql.getValue().contains("b.status='IMPORTED'"));
            for(RankMetric metric:RankMetric.values())assertTrue(sql.getValue().contains(metric.column+"<>0"));
        }
        @Test void emptyResultDoesNotEmitABatch()throws Exception{
            store.streamScores(board,2,120,batch->fail("empty batch"));verify(statement).executeQuery();verify(rows).close();
        }
        @Test void fullFinalBatchIsNotWrittenTwice()throws Exception{
            when(rows.next()).thenReturn(true,true,false);when(rows.getString("user_id")).thenReturn("u1","u2");
            List<Map<String,Double>> batches=new ArrayList<>();store.streamScores(board,2,120,batches::add);
            assertEquals(1,batches.size());assertEquals(2,batches.get(0).size());
        }
        @Test void failedRedisBatchClosesTheStreamAndRestoresStricterNetworkTimeout()throws Exception{
            when(connection.getNetworkTimeout()).thenReturn(3000);when(statement.getQueryTimeout()).thenReturn(30);
            when(rows.next()).thenReturn(true);when(rows.getString("user_id")).thenReturn("u1");
            assertThrows(RankException.class,()->store.streamScores(board,1,120,batch->{throw RankException.unavailable();}));
            verify(rows).close();verify(statement).close();verify(connection).close();
            verify(connection,times(2)).setNetworkTimeout(any(),eq(3000));verify(statement).setQueryTimeout(30);
        }
        @Test void invalidScoresFailBeforeReachingRedis()throws Exception{
            for(double score:new double[]{-1D,Double.NaN,Double.POSITIVE_INFINITY,0.5D,9007199254740992D}){
                when(rows.next()).thenReturn(true);when(rows.getDouble("daily_kill_sum")).thenReturn(score);
                assertEquals(40902,assertThrows(RankException.class,()->store.streamScores(board,1,120,batch->fail("invalid score"))).getCode());
            }
            verify(rows,times(5)).close();
        }
        @Test void elapsedBudgetFailsEvenForAnEmptyStream()throws Exception{
            assertThrows(RankException.class,()->store.streamScores(board,2,0,batch->fail("expired")));
            verify(rows).close();verify(statement).close();
        }
        @Test void interruptionDuringRedisWriteCannotFinishTheSnapshot()throws Exception{
            when(rows.next()).thenReturn(true,false);when(rows.getString("user_id")).thenReturn("u1");
            try{
                assertThrows(RankException.class,()->store.streamScores(board,1,120,batch->Thread.currentThread().interrupt()));
                assertTrue(Thread.currentThread().isInterrupted());verify(rows).close();verify(statement).close();
            }finally{Thread.interrupted();}
        }
    }
    @Test void newRowTimesKeepRfc3339SecondsAndMilliseconds(){
        long second=Instant.parse("2026-10-13T00:00:00Z").toEpochMilli();
        assertEquals("2026-10-13T08:00:00+08:00",RankDataStore.time(second));
        assertEquals("2026-10-13T08:00:00.123+08:00",RankDataStore.time(second+123));
    }
    @Test void affectedMembershipUsesBoundedCounterIdsAndNativeGroupComparison(){
        NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);RankDataStore store=new RankDataStore(jdbc,Fixtures.mapper());CacheBoard board=Fixtures.board(0);
        LedgerStore.Counter counter=Fixtures.counter();counter.setGame("bjgyb2");
        when(jdbc.queryForList(anyString(),any(MapSqlParameterSource.class),eq(String.class))).thenReturn(Collections.singletonList("u1"));
        assertEquals(Collections.singleton("u1"),store.affectedUsers(board,Collections.singletonList(counter)));
        ArgumentCaptor<MapSqlParameterSource> parameters=ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForList(eq("SELECT DISTINCT user_id FROM score_total WHERE id IN (:ids) AND game=:game"),parameters.capture(),eq(String.class));
        assertEquals("BJGYB2",parameters.getValue().getValue("game"));assertEquals(Collections.singletonList(counter.getId()),parameters.getValue().getValue("ids"));
        counter.setPeriodStart(0);assertEquals(Collections.emptySet(),store.affectedUsers(board,Collections.singletonList(counter)));verifyNoMoreInteractions(jdbc);
    }
    @Test void aliasQueryDoesNotRewriteHistoricalOrNewProfileGrouping()throws Exception{
        for(boolean imported:new boolean[]{false,true}) {
            NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);RankDataStore store=new RankDataStore(jdbc,Fixtures.mapper());
            RankEntry historical=new RankEntry();historical.setUserId("u1");historical.setGame("BJGYB2");historical.setPlatform("DYGYB2");historical.setFaction("红方");historical.setZhubo("");historical.setCreatedAt(RankDataStore.time(Fixtures.NOW));historical.setUpdatedAt(historical.getCreatedAt());
            doAnswer(call->{String sql=call.getArgument(0);org.springframework.jdbc.core.RowCallbackHandler callback=call.getArgument(2);java.sql.ResultSet rs=mock(java.sql.ResultSet.class);
                if(sql.startsWith("SELECT s.entry_json")){if(imported){when(rs.getString(1)).thenReturn(Fixtures.mapper().writeValueAsString(historical));callback.processRow(rs);}}
                else {when(rs.getString("user_id")).thenReturn("u1");when(rs.getString("game")).thenReturn(imported?"bjgyb2":"BJGYB2");when(rs.getString("platform")).thenReturn(imported?"dygyb2":"DYGYB2");when(rs.getString("faction")).thenReturn("红方");when(rs.getString("zhubo")).thenReturn("");when(rs.getDouble("kill_sum")).thenReturn(100D);when(rs.getLong("created_at")).thenReturn(Fixtures.NOW);when(rs.getLong("updated_at")).thenReturn(Fixtures.NOW);callback.processRow(rs);}return null;
            }).when(jdbc).query(anyString(),any(MapSqlParameterSource.class),any(org.springframework.jdbc.core.RowCallbackHandler.class));
            RankEntry result=store.entries(new RankScope("bjgyb2","dygyb2","红方",null),Collections.singleton("u1"),Periods.day(Fixtures.NOW),Periods.cycle(Fixtures.NOW)).get("u1");
            assertEquals("BJGYB2",result.getGame());assertEquals("DYGYB2",result.getPlatform());assertEquals("",result.getZhubo());
        }
    }
}
