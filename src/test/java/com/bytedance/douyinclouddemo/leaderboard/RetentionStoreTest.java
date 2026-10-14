package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetentionStoreTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final NamedParameterJdbcTemplate named=mock(NamedParameterJdbcTemplate.class);
    private final RetentionStore retention=new RetentionStore(jdbc,named);
    private final long cutoff=Fixtures.NOW-Duration.ofDays(90).toMillis();
    @BeforeEach void sweepBounds(){when(jdbc.queryForObject(startsWith("SELECT COALESCE(MAX(id),0)"),eq(Long.class))).thenReturn(100L);}
    private Map<String,Object> row(Object... values){Map<String,Object> row=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)row.put((String)values[i],values[i+1]);return row;}
    @Test void nonpositiveRetentionCutoffNeverTouchesDatabase(){
        assertEquals(0,retention.cleanup(0,200));assertEquals(0,retention.cleanup(Duration.ofDays(90).toMillis(),200));verifyNoInteractions(jdbc,named);
    }
    @Test void onlyOldTerminalReceiptsAreCompactedAndDeduplicationColumnsRemainIntact(){
        when(jdbc.queryForList(contains("FROM gift_receipt WHERE id>"),anyLong(),anyLong(),anyInt())).thenReturn(Arrays.asList(
                row("id",1L,"status","BOOKED","completed_at",cutoff-1),row("id",2L,"status","FILTERED","completed_at",cutoff-1),
                row("id",3L,"status","PENDING_REVIEW","completed_at",null),row("id",4L,"status","BOOKED","completed_at",cutoff)));
        retention.cleanup(Fixtures.NOW,200);
        ArgumentCaptor<SqlParameterSource> args=ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named).update(startsWith("UPDATE gift_receipt r LEFT JOIN"),args.capture());
        assertEquals(Arrays.asList(1L,2L),args.getValue().getValue("ids"));assertEquals(cutoff,args.getValue().getValue("cutoff"));
        verify(named,never()).update(startsWith("DELETE FROM gift_receipt"),any(SqlParameterSource.class));
    }
    @Test void pendingReviewProtectsTheWholeOriginalStorageMonth(){
        when(jdbc.queryForList(contains("information_schema.TABLES"),eq(String.class),anyString(),anyString())).thenReturn(Collections.singletonList("gift_detail_202607"));
        when(jdbc.queryForList(contains("storage_month=? AND status='PENDING_REVIEW'"),eq(Long.class),eq("202607"))).thenReturn(Collections.singletonList(99L));
        retention.cleanup(Fixtures.NOW,200);
        verifyNoInteractions(named);verify(jdbc,never()).queryForList(contains("LEFT JOIN gift_receipt"),anyLong(),anyInt());
    }
    @Test void recentBookingAndMissingReceiptCannotBeRemovedFromOldMonthlyTable(){
        when(jdbc.queryForList(contains("information_schema.TABLES"),eq(String.class),anyString(),anyString())).thenReturn(Collections.singletonList("gift_detail_202607"));
        when(jdbc.queryForList(contains("LEFT JOIN gift_receipt"),anyLong(),anyInt())).thenReturn(Arrays.asList(
                row("receipt_id",1L,"booked_at",cutoff-1,"status","BOOKED","completed_at",cutoff-1),
                row("receipt_id",2L,"booked_at",Fixtures.NOW,"status","BOOKED","completed_at",Fixtures.NOW),
                row("receipt_id",3L,"booked_at",cutoff-1,"status",null,"completed_at",null)));
        retention.cleanup(Fixtures.NOW,200);
        ArgumentCaptor<SqlParameterSource> args=ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named).update(startsWith("DELETE d FROM gift_detail_202607"),args.capture());
        assertEquals(Collections.singletonList(1L),args.getValue().getValue("ids"));
        assertEquals("202607",args.getValue().getValue("month"));
    }
    @Test void currentPeriodRecentCorrectionAndUnsynchronizedCounterAreProtected(){
        long old=Periods.day(cutoff)-Duration.ofDays(10).toMillis();
        when(jdbc.queryForList(contains("FROM score_total WHERE id>"),anyLong(),anyLong(),anyInt())).thenReturn(Arrays.asList(
                row("id",1L,"rank_type",0,"period_start",old,"updated_at",cutoff-1,"sync_pending",false),
                row("id",2L,"rank_type",1,"period_start",Periods.cycle(Fixtures.NOW),"updated_at",cutoff-1,"sync_pending",false),
                row("id",3L,"rank_type",0,"period_start",old,"updated_at",cutoff-1,"sync_pending",true),
                row("id",4L,"rank_type",0,"period_start",old,"updated_at",Fixtures.NOW,"sync_pending",false)));
        retention.cleanup(Fixtures.NOW,200);
        ArgumentCaptor<SqlParameterSource> args=ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named).update(startsWith("DELETE FROM score_total"),args.capture());
        assertEquals(Collections.singletonList(1L),args.getValue().getValue("ids"));
    }
    @Test void ninetyDayCutoffAdvancesWithDatabaseTime(){
        when(jdbc.queryForList(contains("FROM gift_receipt WHERE id>"),anyLong(),anyLong(),anyInt())).thenReturn(Collections.singletonList(row("id",1L,"status","BOOKED","completed_at",cutoff-100)));
        retention.cleanup(Fixtures.NOW,200);
        retention.cleanup(Fixtures.NOW+Duration.ofDays(1).toMillis(),200);
        ArgumentCaptor<SqlParameterSource> args=ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named,times(2)).update(startsWith("UPDATE gift_receipt"),args.capture());
        assertEquals(cutoff,args.getAllValues().get(0).getValue("cutoff"));
        assertEquals(cutoff+Duration.ofDays(1).toMillis(),args.getAllValues().get(1).getValue("cutoff"));
    }
    @Test void eachSweepHasAFixedUpperBoundDespiteNewIncomingMessages(){
        when(jdbc.queryForObject(eq("SELECT COALESCE(MAX(id),0) FROM gift_receipt"),eq(Long.class))).thenReturn(100L,200L);
        when(jdbc.queryForList(contains("FROM gift_receipt WHERE id>"),anyLong(),anyLong(),anyInt())).thenReturn(Collections.singletonList(row("id",100L,"status","BOOKED","completed_at",Fixtures.NOW)),Collections.emptyList());
        retention.cleanup(Fixtures.NOW,200);retention.cleanup(Fixtures.NOW,200);
        verify(jdbc).queryForList(contains("FROM gift_receipt WHERE id>"),eq(0L),eq(100L),eq(200));
        verify(jdbc).queryForList(contains("FROM gift_receipt WHERE id>"),eq(0L),eq(200L),eq(200));
    }
}
