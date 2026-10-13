package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RankQueryServiceTest {
    private final CacheStateStore states=mock(CacheStateStore.class);
    private final LeaderboardRedis redis=mock(LeaderboardRedis.class);
    private final RankDataStore ranks=mock(RankDataStore.class);
    private final MigrationStore migrations=mock(MigrationStore.class);
    private final LegacyScopeStore scopes=mock(LegacyScopeStore.class);
    private final Fixtures.MutableClock clock=new Fixtures.MutableClock();
    private final RankQueryService service=new RankQueryService(states,redis,ranks,migrations,scopes,clock);
    private final RankRequest request=new RankRequest();
    @BeforeEach void prepare(){
        request.setLimit(10);request.setGame("BJGYB2");
        when(scopes.find(any())).thenAnswer(call->call.getArgument(0));
        when(states.ensure(any(),anyLong(),anyLong(),anyInt())).thenReturn(Fixtures.board(1));
        when(redis.top(any(),anyInt())).thenReturn(Collections.singletonMap("u1",100D));
        RankEntry entry=new RankEntry();entry.setUserId("u1");entry.setCreatedAt("2026-10-13T00:00:00+08:00");entry.setUpdatedAt(entry.getCreatedAt());
        when(ranks.entries(any(),anyCollection(),anyLong(),anyLong())).thenReturn(Collections.singletonMap("u1",entry));
    }
    @Test void returnsCompleteLegacyShape(){
        RankEntry entry=service.query(request).get(0);com.fasterxml.jackson.databind.JsonNode json=Fixtures.mapper().valueToTree(entry);
        assertEquals(21,json.size());assertEquals(100,json.path("KillSum").longValue());assertTrue(json.has("Points1Sum"));assertTrue(json.has("DeletedAt"));
        verify(ranks).entries(any(),eq(Collections.singleton("u1")),anyLong(),anyLong());
    }
    @Test void allEightSortsUseTheirOwnBoard(){for(RankMetric metric:RankMetric.values()){
        RankRequest one=new RankRequest();one.setLimit(10);one.setGame("BJGYB2");one.setOrderBy(metric.column);service.query(one);
        verify(states).ensure(any(),anyLong(),anyLong(),eq(metric.code));
    }}
    @Test void coldBoardReturnsUnavailable(){CacheBoard cold=Fixtures.board(1);cold.setActiveGeneration(null);cold.setStatus("REQUESTED");when(states.ensure(any(),anyLong(),anyLong(),anyInt())).thenReturn(cold);assertEquals(503,assertThrows(RankException.class,()->service.query(request)).getHttpStatus());verifyNoInteractions(redis,ranks);}
    @Test void failureFallbackExpires(){List<RankEntry> rows=service.query(request);clock.millis+=2000;doThrow(RankException.unavailable()).when(migrations).assertQueryable(any());assertEquals(rows,service.query(request));clock.millis+=28001;assertThrows(RankException.class,()->service.query(request));}
    @Test void dayBoundaryDoesNotReturnOldPeriod(){service.query(request);clock.millis=Periods.end(0,Periods.day(Fixtures.NOW))+1;doThrow(RankException.unavailable()).when(migrations).assertQueryable(any());assertThrows(RankException.class,()->service.query(request));}
    @Test void cacheReadFailureRequestsRebuild(){doThrow(RankException.unavailable()).when(redis).top(any(),anyInt());assertThrows(RankException.class,()->service.query(request));verify(states).requestRebuild(any());}
    @Test void invalidSortAndLimitAreRejected(){request.setOrderBy("unknown");assertThrows(RankValidationException.class,()->service.query(request));request.setOrderBy(null);request.setLimit(1001);assertThrows(RankValidationException.class,()->service.query(request));verifyNoInteractions(states,redis,ranks,migrations);}
    @Test void caseAliasesUseTheSameCanonicalBoard(){RankScope canonical=new RankScope("BJGYB2",null,null,null);when(scopes.find(any())).thenReturn(canonical);request.setGame("bjgyb2");service.query(request);verify(migrations).assertQueryable(canonical);verify(states).ensure(eq(canonical),anyLong(),anyLong(),eq(1));}
    @Test void unknownScopeIsEmptyOnlyAfterMigrationGate(){when(scopes.find(any())).thenReturn(null);assertTrue(service.query(request).isEmpty());verify(migrations).assertQueryable(request.scope());verifyNoInteractions(states,redis,ranks);doThrow(RankException.unavailable()).when(migrations).assertQueryable(any());assertThrows(RankException.class,()->service.query(request));}
}
