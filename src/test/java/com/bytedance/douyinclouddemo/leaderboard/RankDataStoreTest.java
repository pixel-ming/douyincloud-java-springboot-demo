package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RankDataStoreTest {
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
