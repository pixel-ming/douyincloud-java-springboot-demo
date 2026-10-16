package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CacheStateStoreTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final LedgerStore ledger=mock(LedgerStore.class);
    private final PlatformTransactionManager transactions=Fixtures.transactions();
    private final LeaderboardProperties properties=new LeaderboardProperties();
    private final CacheStateStore states=new CacheStateStore(jdbc,transactions,Fixtures.mapper(),properties,ledger);
    private final CacheStateStore.Lease expected=new CacheStateStore.Lease("owner",1,3,0,Fixtures.NOW+30000);
    @BeforeEach @SuppressWarnings("unchecked") void prepare(){when(jdbc.queryForObject(contains("UNIX_TIMESTAMP"),eq(Long.class))).thenReturn(Fixtures.NOW);when(jdbc.queryForObject(contains("FOR UPDATE"),any(RowMapper.class),eq("local"))).thenReturn(expected);}
    @Test void matchingRegistryAndLeaseCanConfirmReadVersion(){assertTrue(states.acknowledge(expected,Collections.singletonList(Fixtures.counter()),1));verify(ledger).acknowledge(anyList());}
    @Test void invalidationRetainsGenerationAndOnlyChangesTheMatchingReadyBoard(){
        CacheBoard board=Fixtures.board(1);
        String sql="UPDATE rank_cache_state SET status=IF(building_generation IS NULL,'REQUESTED','BUILDING'),retry_after=0 WHERE scope_id=? AND rank_type=? AND day_start=? AND cycle_start=? AND status='READY' AND active_generation=?";
        when(jdbc.update(eq(sql),eq(board.getScope().id()),eq(1),eq(board.getDayStart()),eq(board.getCycleStart()),eq("active"))).thenReturn(1,0);
        states.requestRebuild(board);states.requestRebuild(board);
        verify(jdbc,times(2)).update(sql,board.getScope().id(),1,board.getDayStart(),board.getCycleStart(),"active");
        verify(jdbc,times(1)).update("UPDATE cache_sync_control SET registry_revision=registry_revision+1 WHERE environment=?","local");
    }
    @Test @SuppressWarnings("unchecked") void changedRegistryCannotClearSharedPending(){when(jdbc.queryForObject(contains("FOR UPDATE"),any(RowMapper.class),eq("local"))).thenReturn(new CacheStateStore.Lease("owner",1,4,0,Fixtures.NOW+30000));assertFalse(states.acknowledge(expected,Collections.singletonList(Fixtures.counter()),1));verifyNoInteractions(ledger);}
    @Test @SuppressWarnings("unchecked") void staleOwnerCannotConfirmOrPublish(){when(jdbc.queryForObject(contains("FOR UPDATE"),any(RowMapper.class),eq("local"))).thenReturn(new CacheStateStore.Lease("new-owner",2,3,0,Fixtures.NOW+30000));assertFalse(states.acknowledge(expected,Collections.singletonList(Fixtures.counter()),1));assertFalse(states.publish(Fixtures.board(0),"build",expected,"evidence"));verifyNoInteractions(ledger);}
    @Test @SuppressWarnings("unchecked") void expiredLeaseCannotConfirmReplay(){when(jdbc.queryForObject(contains("FOR UPDATE"),any(RowMapper.class),eq("local"))).thenReturn(new CacheStateStore.Lease("owner",1,3,0,Fixtures.NOW-1));Runnable update=mock(Runnable.class);assertFalse(states.confirmReplay(expected,update));verifyNoInteractions(update);}
    @Test @SuppressWarnings("unchecked") void expiryRetainsGenerationsAndRevokesTheBuildToken(){
        CacheBoard old=Fixtures.board(0);old.setBuildingGeneration("building");
        when(jdbc.query(contains("ORDER BY period_end LIMIT"),any(RowMapper.class),anyLong(),anyInt())).thenReturn(Collections.singletonList(old));
        assertEquals(Collections.singletonList(old),states.expire(expected));
        verify(jdbc).batchUpdate(eq("UPDATE rank_cache_state SET status='EXPIRED',build_token=NULL WHERE scope_id=? AND rank_type=? AND day_start=? AND cycle_start=?"),anyList());
        verify(jdbc,never()).update(contains("active_generation=NULL"),anyLong());
    }
    @Test void confirmationMatchesTheExactExpiredGenerationAndPruningKeepsOutstandingReferences(){
        CacheBoard old=Fixtures.board(0);states.confirmExpiredRemoval(old,expected);states.purgeExpired(expected);
        verify(jdbc).update(contains("status='EXPIRED' AND active_generation<=>? AND building_generation<=>?"),eq(old.getScope().id()),eq(0),eq(old.getDayStart()),eq(old.getCycleStart()),eq("active"),isNull());
        verify(jdbc).update(contains("active_generation IS NULL AND building_generation IS NULL"),eq(Fixtures.NOW-java.time.Duration.ofDays(7).toMillis()),eq(200));
    }
    @Test void disabledBusinessCleanupNeverTouchesDatabase(){properties.setDataCleanupEnabled(false);RetentionStore retention=mock(RetentionStore.class);states.cleanBusinessData(expected,retention);verifyNoInteractions(jdbc,retention);}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    @SuppressWarnings("unchecked")
    void failedCleanupRetriesTheBatchWithoutLosingCommittedProgress(boolean failOnCommit){
        NamedParameterJdbcTemplate named=mock(NamedParameterJdbcTemplate.class);
        RetentionStore retention=new RetentionStore(jdbc,named);
        long old=Fixtures.NOW-java.time.Duration.ofDays(100).toMillis();
        when(jdbc.queryForObject(startsWith("SELECT COALESCE(MAX(id),0)"),eq(Long.class))).thenReturn(500L);
        when(jdbc.queryForList(contains("FROM gift_receipt WHERE id>"),anyLong(),anyLong(),anyInt())).thenReturn(
                Collections.singletonList(row("id",100L,"status","BOOKED","completed_at",old)),
                Collections.singletonList(row("id",500L,"status","BOOKED","completed_at",old)));
        when(jdbc.queryForList(contains("FROM score_total WHERE id>"),anyLong(),anyLong(),anyInt())).thenReturn(
                Collections.singletonList(row("id",100L,"rank_type",0,"period_start",Periods.day(old),"updated_at",old,"sync_pending",false)),
                Collections.singletonList(row("id",500L,"rank_type",0,"period_start",Periods.day(old),"updated_at",old,"sync_pending",false)));
        when(jdbc.queryForList(contains("information_schema.TABLES"),eq(String.class),anyString(),anyString())).thenReturn(Collections.singletonList("gift_detail_202607"));
        when(jdbc.queryForList(contains("LEFT JOIN gift_receipt"),anyLong(),anyInt())).thenReturn(
                Collections.singletonList(row("receipt_id",100L,"booked_at",old,"status","BOOKED","completed_at",old)),Collections.emptyList());
        states.cleanBusinessData(expected,retention);
        if(failOnCommit)doThrow(new org.springframework.transaction.TransactionSystemException("commit failed")).doNothing().when(transactions).commit(any());
        else doThrow(new org.springframework.dao.DataAccessResourceFailureException("cleanup failed")).doReturn(1).when(named).update(startsWith("DELETE FROM score_total"),any(SqlParameterSource.class));
        assertThrows(RuntimeException.class,()->states.cleanBusinessData(expected,retention));
        states.cleanBusinessData(expected,retention);
        verify(jdbc,times(2)).queryForList(contains("FROM gift_receipt WHERE id>"),eq(100L),eq(500L),eq(200));
        verify(jdbc,times(2)).queryForList(contains("FROM score_total WHERE id>"),eq(100L),eq(500L),eq(200));
        verify(jdbc,times(2)).queryForList(contains("LEFT JOIN gift_receipt"),eq(100L),eq(200));
        verify(jdbc,times(1)).queryForList(contains("information_schema.TABLES"),eq(String.class),eq(""),anyString());
        verify(jdbc,times(1)).queryForObject("SELECT COALESCE(MAX(id),0) FROM gift_receipt",Long.class);
        verify(jdbc,times(1)).queryForObject("SELECT COALESCE(MAX(id),0) FROM score_total",Long.class);
    }
    private Map<String,Object> row(Object... values){Map<String,Object> row=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)row.put((String)values[i],values[i+1]);return row;}
}
