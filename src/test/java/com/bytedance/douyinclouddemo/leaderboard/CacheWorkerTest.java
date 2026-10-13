package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CacheWorkerTest {
    private final CacheStateStore states=mock(CacheStateStore.class);
    private final LedgerStore ledger=mock(LedgerStore.class);
    private final RankDataStore ranks=mock(RankDataStore.class);
    private final LeaderboardRedis redis=mock(LeaderboardRedis.class);
    private final PlatformTransactionManager tx=Fixtures.transactions();
    private final LeaderboardProperties props=new LeaderboardProperties();
    private final CacheWarmup warmup=mock(CacheWarmup.class);
    private final CacheWorker worker=new CacheWorker(states,ledger,ranks,redis,tx,props,warmup);
    private final CacheStateStore.Lease lease=new CacheStateStore.Lease("owner",1,3,0,Fixtures.NOW+30000);
    @BeforeEach void membership(){when(ranks.affectedUsers(any(),anyList())).thenReturn(Collections.singleton("u1"));}
    @AfterEach void stop(){worker.stop();}
    @Test void recoveryInvalidatesDatabaseBeforeReplacingRedisEvidence(){props.setWorkerEnabled(true);when(states.acquire(anyString())).thenReturn(lease);when(redis.healthy(lease)).thenReturn(false);when(states.invalidateAll(lease)).thenThrow(new IllegalStateException("database unavailable"));worker.tick();verify(states).invalidateAll(lease);verify(redis,never()).installMarker(any());}
    @Test void recoveredMarkerIsOnlyInstalledAfterDurableInvalidation(){props.setWorkerEnabled(true);when(states.acquire(anyString())).thenReturn(lease);when(redis.healthy(lease)).thenReturn(false);when(states.invalidateAll(lease)).thenReturn(Collections.emptyList());when(redis.installMarker(lease)).thenThrow(new IllegalStateException("process interrupted"));worker.tick();org.mockito.InOrder order=inOrder(states,redis);order.verify(states).invalidateAll(lease);order.verify(redis).installMarker(lease);}
    @Test void refreshesAllScopesBeforeAcknowledgement(){CacheBoard a=Fixtures.board(0),b=Fixtures.board(0);b.setScope(new RankScope("BJGYB2","DYGYB2","红方",null));when(ranks.scores(any(),anyCollection())).thenReturn(Collections.singletonMap("u1",30D));when(states.acknowledge(any(),anyList(),anyLong())).thenReturn(true);worker.synchronize(lease,Collections.singletonList(Fixtures.counter()),Arrays.asList(a,b));org.mockito.InOrder order=inOrder(redis,states);order.verify(redis).write(a,"active",Collections.singletonMap("u1",30D));order.verify(redis).write(b,"active",Collections.singletonMap("u1",30D));order.verify(redis).mark(lease,1);order.verify(states).acknowledge(eq(lease),anyList(),eq(1L));}
    @Test void partialRedisFailureNeverClearsPending(){CacheBoard a=Fixtures.board(0),b=Fixtures.board(0);b.setScope(new RankScope("BJGYB2","DYGYB2",null,null));when(ranks.scores(any(),anyCollection())).thenReturn(Collections.singletonMap("u1",30D));doThrow(RankException.unavailable()).when(redis).write(eq(b),anyString(),anyMap());assertThrows(RankException.class,()->worker.synchronize(lease,Collections.singletonList(Fixtures.counter()),Arrays.asList(a,b)));verify(states,never()).acknowledge(any(),anyList(),anyLong());verify(redis,never()).mark(any(),anyLong());}
    @Test void newVersionRemainsPendingAndUnconfirmedMarkerCanBeAbandoned(){CacheBoard a=Fixtures.board(0);when(ranks.scores(any(),anyCollection())).thenReturn(Collections.singletonMap("u1",30D));when(states.acknowledge(any(),anyList(),anyLong())).thenReturn(false);when(redis.abandonMarker(lease,1)).thenReturn(true);worker.synchronize(lease,Collections.singletonList(Fixtures.counter()),Collections.singletonList(a));verify(redis).abandonMarker(lease,1);}
    @Test void activeAndBuildingGenerationBothReceiveAuthoritativeTotal(){CacheBoard a=Fixtures.board(0);a.setBuildingGeneration("building");when(ranks.scores(any(),anyCollection())).thenReturn(Collections.singletonMap("u1",130D));when(states.acknowledge(any(),anyList(),anyLong())).thenReturn(true);worker.synchronize(lease,Collections.singletonList(Fixtures.counter()),Collections.singletonList(a));verify(redis).write(a,"active",Collections.singletonMap("u1",130D));verify(redis).write(a,"building",Collections.singletonMap("u1",130D));}
    @Test void allSnapshotPagesUseOneReadOnlyRepeatableReadTransaction(){props.setBatchSize(2);CacheBoard b=Fixtures.board(0);Map<String,Double> first=new LinkedHashMap<>();first.put("u1",10D);first.put("u2",20D);when(ranks.pageScores(eq(b),eq(""),eq(2))).thenReturn(first);when(ranks.pageScores(eq(b),eq("u2"),eq(2))).thenReturn(Collections.singletonMap("u3",30D));String evidence=worker.snapshot(b,"building");assertTrue(evidence.contains(":3:"));ArgumentCaptor<TransactionDefinition> definition=ArgumentCaptor.forClass(TransactionDefinition.class);verify(tx).getTransaction(definition.capture());assertTrue(definition.getValue().isReadOnly());assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,definition.getValue().getIsolationLevel());assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,definition.getValue().getPropagationBehavior());verify(redis,times(2)).write(eq(b),eq("building"),anyMap());verify(tx).commit(any());}
    @Test void incompleteSnapshotCannotBeDeclaredSuccessful(){CacheBoard b=Fixtures.board(0);when(ranks.pageScores(any(),anyString(),anyInt())).thenReturn(Collections.singletonMap("u1",10D));doThrow(RankException.unavailable()).when(redis).write(any(),anyString(),anyMap());assertThrows(RankException.class,()->worker.snapshot(b,"building"));verify(tx).rollback(any());verify(redis,never()).complete(any(),anyString());}
    @Test void caseVariantPendingCounterUsesDatabaseMembership(){CacheBoard b=Fixtures.board(0);LedgerStore.Counter counter=Fixtures.counter();counter.setGame("bjgyb2");when(ranks.scores(any(),anyCollection())).thenReturn(Collections.singletonMap("u1",30D));when(states.acknowledge(any(),anyList(),anyLong())).thenReturn(true);worker.synchronize(lease,Collections.singletonList(counter),Collections.singletonList(b));verify(ranks).affectedUsers(b,Collections.singletonList(counter));verify(redis).write(b,"active",Collections.singletonMap("u1",30D));}
    @Test void onlyLeaseOwnerRegistersWarmBoardsAndReacquiresBeforePending(){props.setWorkerEnabled(true);when(states.acquire(anyString())).thenReturn(lease);when(redis.healthy(lease)).thenReturn(true);when(warmup.register()).thenReturn(true);worker.tick();org.mockito.InOrder order=inOrder(warmup,states,ledger);order.verify(warmup).register();order.verify(states).acquire(anyString());order.verify(ledger).pending(0,props.getBatchSize());}
    @Test void workerWithoutLeaseOrDisabledNeverRegistersBoards(){worker.tick();props.setWorkerEnabled(true);worker.tick();verifyNoInteractions(warmup);}
    @Test void warmRegistrationUsesRefreshedRegistryForPendingAcknowledgement(){
        props.setWorkerEnabled(true);CacheStateStore.Lease refreshed=new CacheStateStore.Lease("owner",1,4,0,Fixtures.NOW+30000);
        when(states.acquire(anyString())).thenReturn(lease,lease,lease,refreshed);when(redis.healthy(any())).thenReturn(true);when(redis.readable(any())).thenReturn(true);when(warmup.register()).thenReturn(true);
        when(states.enabled()).thenReturn(Collections.singletonList(Fixtures.board(0)));when(ledger.pending(0,props.getBatchSize())).thenReturn(Collections.singletonList(Fixtures.counter()));
        when(ranks.scores(any(),anyCollection())).thenReturn(Collections.singletonMap("u1",30D));when(states.acknowledge(eq(refreshed),anyList(),eq(1L))).thenReturn(true);
        worker.tick();verify(redis).mark(refreshed,1);verify(states).acknowledge(eq(refreshed),anyList(),eq(1L));verify(states,never()).acknowledge(eq(lease),anyList(),anyLong());
    }
}
