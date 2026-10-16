package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import java.util.*;
import java.util.function.Consumer;
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
    private final RetentionStore retention=mock(RetentionStore.class);
    private final CacheWorker worker=new CacheWorker(states,ledger,ranks,redis,tx,props,warmup,retention);
    private final CacheStateStore.Lease lease=new CacheStateStore.Lease("owner",1,3,0,Fixtures.NOW+30000);
    @BeforeEach void membership(){when(ranks.affectedUsers(any(),anyList())).thenReturn(Collections.singleton("u1"));}
    @AfterEach void stop(){worker.stop();}
    private void stream(List<Map<String,Double>> pages){
        doAnswer(call->{Consumer<Map<String,Double>> batch=call.getArgument(3);pages.forEach(batch);return null;})
                .when(ranks).streamScores(any(),anyInt(),anyInt(),any());
    }
    @Test void recoveryInvalidatesDatabaseBeforeReplacingRedisEvidence(){props.setWorkerEnabled(true);when(states.acquire(anyString())).thenReturn(lease);when(redis.healthy(lease)).thenReturn(false);when(states.invalidateAll(lease)).thenThrow(new IllegalStateException("database unavailable"));worker.tick();verify(states).invalidateAll(lease);verify(redis,never()).installMarker(any());}
    @Test void recoveredMarkerIsOnlyInstalledAfterDurableInvalidation(){props.setWorkerEnabled(true);when(states.acquire(anyString())).thenReturn(lease);when(redis.healthy(lease)).thenReturn(false);when(states.invalidateAll(lease)).thenReturn(Collections.emptyList());when(redis.installMarker(lease)).thenThrow(new IllegalStateException("process interrupted"));worker.tick();org.mockito.InOrder order=inOrder(states,redis);order.verify(states).invalidateAll(lease);order.verify(redis).installMarker(lease);}
    @Test void refreshesAllScopesBeforeAcknowledgement(){CacheBoard a=Fixtures.board(0),b=Fixtures.board(0);b.setScope(new RankScope("BJGYB2","DYGYB2","红方",null));when(ranks.scores(any(),anyCollection())).thenReturn(Collections.singletonMap("u1",30D));when(states.acknowledge(any(),anyList(),anyLong())).thenReturn(true);worker.synchronize(lease,Collections.singletonList(Fixtures.counter()),Arrays.asList(a,b));org.mockito.InOrder order=inOrder(redis,states);order.verify(redis).write(a,"active",Collections.singletonMap("u1",30D));order.verify(redis).write(b,"active",Collections.singletonMap("u1",30D));order.verify(redis).mark(lease,1);order.verify(states).acknowledge(eq(lease),anyList(),eq(1L));}
    @Test void partialRedisFailureNeverClearsPendingOrInvalidatesTheCache(){CacheBoard a=Fixtures.board(0),b=Fixtures.board(0);b.setScope(new RankScope("BJGYB2","DYGYB2",null,null));when(ranks.scores(any(),anyCollection())).thenReturn(Collections.singletonMap("u1",30D));doThrow(new org.springframework.data.redis.RedisConnectionFailureException("temporary")).when(redis).write(eq(b),anyString(),anyMap());assertThrows(RuntimeException.class,()->worker.synchronize(lease,Collections.singletonList(Fixtures.counter()),Arrays.asList(a,b)));verify(states,never()).acknowledge(any(),anyList(),anyLong());verify(states,never()).requestRebuild(any());verify(redis,never()).mark(any(),anyLong());}
    @Test void newVersionRemainsPendingAndUnconfirmedMarkerCanBeAbandoned(){CacheBoard a=Fixtures.board(0);when(ranks.scores(any(),anyCollection())).thenReturn(Collections.singletonMap("u1",30D));when(states.acknowledge(any(),anyList(),anyLong())).thenReturn(false);when(redis.abandonMarker(lease,1)).thenReturn(true);worker.synchronize(lease,Collections.singletonList(Fixtures.counter()),Collections.singletonList(a));verify(redis).abandonMarker(lease,1);}
    @Test void activeAndBuildingGenerationBothReceiveAuthoritativeTotal(){CacheBoard a=Fixtures.board(0);a.setBuildingGeneration("building");when(ranks.scores(any(),anyCollection())).thenReturn(Collections.singletonMap("u1",130D));when(states.acknowledge(any(),anyList(),anyLong())).thenReturn(true);worker.synchronize(lease,Collections.singletonList(Fixtures.counter()),Collections.singletonList(a));verify(redis).write(a,"active",Collections.singletonMap("u1",130D));verify(redis).write(a,"building",Collections.singletonMap("u1",130D));}
    @Test void allSnapshotBatchesUseOneQueryAndOneReadOnlyRepeatableReadTransaction(){
        props.setBatchSize(2);props.setMaxMembers(3);CacheBoard b=Fixtures.board(0);
        Map<String,Double> first=new LinkedHashMap<>();first.put("u1",10D);first.put("u2",20D);
        stream(Arrays.asList(first,Collections.singletonMap("u3",30D)));String evidence=worker.snapshot(b,"building");
        String digest="";for(int i=1;i<=3;i++)digest=ScoreRules.sha256(digest+":"+ScoreRules.sha256("u"+i)+":"+(i*10D));
        assertTrue(evidence.endsWith(":3:"+digest));
        verify(ranks,times(1)).streamScores(eq(b),eq(2),eq(120),any());
        ArgumentCaptor<TransactionDefinition> definition=ArgumentCaptor.forClass(TransactionDefinition.class);verify(tx).getTransaction(definition.capture());
        assertTrue(definition.getValue().isReadOnly());assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,definition.getValue().getIsolationLevel());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,definition.getValue().getPropagationBehavior());assertEquals(120,definition.getValue().getTimeout());
        verify(redis,times(2)).write(eq(b),eq("building"),anyMap());verify(tx).commit(any());
    }
    @Test void incompleteSnapshotCannotBeDeclaredSuccessful(){CacheBoard b=Fixtures.board(0);stream(Collections.singletonList(Collections.singletonMap("u1",10D)));doThrow(RankException.unavailable()).when(redis).write(any(),anyString(),anyMap());assertThrows(RankException.class,()->worker.snapshot(b,"building"));verify(tx).rollback(any());verify(redis,never()).complete(any(),anyString());}
    @Test void overCapacitySnapshotRollsBackBeforeTheExcessBatch(){
        props.setMaxMembers(1);stream(Arrays.asList(Collections.singletonMap("u1",10D),Collections.singletonMap("u2",20D)));
        assertEquals(50304,assertThrows(RankException.class,()->worker.snapshot(Fixtures.board(0),"building")).getCode());
        verify(redis,times(1)).write(any(),eq("building"),anyMap());verify(tx).rollback(any());
    }
    @Test void invalidActiveDataRequestsRebuildWithoutAcknowledgingTheGift(){
        CacheBoard b=Fixtures.board(0);when(ranks.scores(any(),anyCollection())).thenReturn(Collections.singletonMap("u1",130D));
        doThrow(new LeaderboardRedis.InvalidCacheException()).when(redis).write(eq(b),eq("active"),anyMap());
        assertThrows(RankException.class,()->worker.synchronize(lease,Collections.singletonList(Fixtures.counter()),Collections.singletonList(b)));
        verify(states).requestRebuild(b);verify(states,never()).acknowledge(any(),anyList(),anyLong());
    }
    @Test void retainedInvalidGenerationDoesNotBlockGiftsToTheBuildingGeneration(){
        CacheBoard b=Fixtures.board(0);b.setStatus("BUILDING");b.setBuildingGeneration("building");
        when(ranks.scores(any(),anyCollection())).thenReturn(Collections.singletonMap("u1",130D));when(states.acknowledge(any(),anyList(),anyLong())).thenReturn(true);
        worker.synchronize(lease,Collections.singletonList(Fixtures.counter()),Collections.singletonList(b));
        verify(redis,never()).write(eq(b),eq("active"),anyMap());verify(redis).write(b,"building",Collections.singletonMap("u1",130D));
        verify(states).acknowledge(eq(lease),anyList(),eq(1L));
    }
    @Test void retirementAndBothSnapshotsMustFinishBeforePublication(){
        CacheBoard b=Fixtures.board(0);b.setStatus("REQUESTED");stream(Collections.singletonList(Collections.singletonMap("u1",100D)));
        when(states.begin(eq(b),anyString(),eq(lease))).thenReturn(true);when(states.publish(eq(b),anyString(),eq(lease),anyString())).thenReturn(true);
        ReflectionTestUtils.invokeMethod(worker,"rebuild",b,lease);
        org.mockito.InOrder order=inOrder(states,redis,ranks);
        order.verify(states).begin(eq(b),anyString(),eq(lease));order.verify(redis).retire(b,"active");
        order.verify(ranks).streamScores(eq(b),eq(200),eq(120),any());order.verify(redis).write(eq(b),anyString(),anyMap());
        order.verify(ranks).streamScores(eq(b),eq(200),eq(120),any());order.verify(redis).write(eq(b),anyString(),anyMap());
        order.verify(redis).complete(eq(b),anyString());order.verify(states).publish(eq(b),anyString(),eq(lease),contains(";"));
        verify(tx,times(2)).commit(any());verify(redis,never()).drop(any(),anyString());
    }
    @Test void failureOfTheSecondSnapshotNeverPublishesTheFirst(){
        CacheBoard b=Fixtures.board(0);when(states.begin(eq(b),anyString(),eq(lease))).thenReturn(true);
        doNothing().doThrow(RankException.unavailable()).when(ranks).streamScores(any(),anyInt(),anyInt(),any());
        ReflectionTestUtils.invokeMethod(worker,"rebuild",b,lease);
        verify(states).fail(eq(b),anyString(),eq(lease));verify(redis).drop(eq(b),anyString());
        verify(redis,never()).complete(any(),anyString());verify(states,never()).publish(any(),anyString(),any(),anyString());
        verify(tx).commit(any());verify(tx).rollback(any());
    }
    @Test void failedRetirementDoesNotLoseTheOldReferenceByPublishing(){
        CacheBoard b=Fixtures.board(0);b.setStatus("REQUESTED");when(states.begin(eq(b),anyString(),eq(lease))).thenReturn(true);
        doThrow(RankException.unavailable()).when(redis).retire(b,"active");ReflectionTestUtils.invokeMethod(worker,"rebuild",b,lease);
        verify(states).fail(eq(b),anyString(),eq(lease));verify(redis).drop(eq(b),anyString());
        verifyNoInteractions(ranks);verify(states,never()).publish(any(),anyString(),any(),anyString());
    }
    @Test void caseVariantPendingCounterUsesDatabaseMembership(){CacheBoard b=Fixtures.board(0);LedgerStore.Counter counter=Fixtures.counter();counter.setGame("bjgyb2");when(ranks.scores(any(),anyCollection())).thenReturn(Collections.singletonMap("u1",30D));when(states.acknowledge(any(),anyList(),anyLong())).thenReturn(true);worker.synchronize(lease,Collections.singletonList(counter),Collections.singletonList(b));verify(ranks).affectedUsers(b,Collections.singletonList(counter));verify(redis).write(b,"active",Collections.singletonMap("u1",30D));}
    @Test void onlyLeaseOwnerRegistersWarmBoardsAndReacquiresBeforePending(){props.setWorkerEnabled(true);when(states.acquire(anyString())).thenReturn(lease);when(redis.healthy(lease)).thenReturn(true);when(warmup.register()).thenReturn(true);worker.tick();org.mockito.InOrder order=inOrder(warmup,states,ledger);order.verify(warmup).register();order.verify(states).acquire(anyString());order.verify(ledger).pending(0,props.getBatchSize());}
    @Test void workerWithoutLeaseOrDisabledNeverRegistersBoards(){props.setWorkerEnabled(false);worker.tick();verifyNoInteractions(states);props.setWorkerEnabled(true);worker.tick();verifyNoInteractions(warmup);}
    @Test void defaultsStartWorkerAndCleanupWithoutEnvironmentVariables()throws Exception{
        MockEnvironment environment=new MockEnvironment();
        new YamlPropertySourceLoader().load("defaults",new ClassPathResource("application.yml")).forEach(source->environment.getPropertySources().addLast(source));
        LeaderboardProperties configured=Binder.get(environment).bind("leaderboard",LeaderboardProperties.class).get();
        assertTrue(props.isWorkerEnabled());assertTrue(props.isDataCleanupEnabled());
        assertTrue(configured.isWorkerEnabled());assertTrue(configured.isDataCleanupEnabled());
        when(states.acquire(anyString())).thenReturn(lease);when(redis.healthy(lease)).thenReturn(true);
        worker.tick();verify(states).cleanBusinessData(lease,retention);verify(ledger).pending(0,props.getBatchSize());
    }
    @Test void disablingBusinessCleanupKeepsCacheCleanupAndScoreSynchronization(){
        props.setDataCleanupEnabled(false);
        when(states.acquire(anyString())).thenReturn(lease);when(redis.healthy(lease)).thenReturn(true);
        worker.tick();verify(states).expire(lease);verify(states).purgeExpired(lease);
        verify(states,never()).cleanBusinessData(any(),any());verify(ledger).pending(0,props.getBatchSize());
    }
    @Test void failedCacheRemovalKeepsRetryEvidenceAndDoesNotBlockScoreSynchronization(){
        props.setWorkerEnabled(true);CacheBoard expired=Fixtures.board(0);
        when(states.acquire(anyString())).thenReturn(lease);when(redis.healthy(lease)).thenReturn(true);
        when(states.expire(lease)).thenReturn(Collections.singletonList(expired));
        doThrow(RankException.unavailable()).when(redis).drop(expired,expired.getActiveGeneration());
        worker.tick();
        verify(states,never()).confirmExpiredRemoval(any(),any());
        verify(ledger).pending(0,props.getBatchSize());verifyNoInteractions(retention);
    }
    @Test void successfulRemovalIsConfirmedBeforeOldStateCanBeDeleted(){
        props.setWorkerEnabled(true);CacheBoard expired=Fixtures.board(0);expired.setBuildingGeneration("building");
        when(states.acquire(anyString())).thenReturn(lease);when(redis.healthy(lease)).thenReturn(true);
        when(states.expire(lease)).thenReturn(Collections.singletonList(expired));worker.tick();
        org.mockito.InOrder order=inOrder(redis,states);
        order.verify(redis).drop(expired,"active");order.verify(redis).drop(expired,"building");
        order.verify(states).confirmExpiredRemoval(expired,lease);order.verify(states).purgeExpired(lease);
    }
    @Test void failedBusinessCleanupDoesNotStopIngestionSynchronization(){
        props.setWorkerEnabled(true);
        when(states.acquire(anyString())).thenReturn(lease);when(redis.healthy(lease)).thenReturn(true);
        when(states.cleanBusinessData(lease,retention)).thenThrow(RankException.unavailable());worker.tick();
        verifyNoInteractions(retention);verify(ledger).pending(0,props.getBatchSize());
    }
    @Test void warmRegistrationUsesRefreshedRegistryForPendingAcknowledgement(){
        props.setWorkerEnabled(true);CacheStateStore.Lease refreshed=new CacheStateStore.Lease("owner",1,4,0,Fixtures.NOW+30000);
        when(states.acquire(anyString())).thenReturn(lease,lease,lease,refreshed);when(redis.healthy(any())).thenReturn(true);when(redis.readable(any())).thenReturn(true);when(warmup.register()).thenReturn(true);
        when(states.enabled()).thenReturn(Collections.singletonList(Fixtures.board(0)));when(ledger.pending(0,props.getBatchSize())).thenReturn(Collections.singletonList(Fixtures.counter()));
        when(ranks.scores(any(),anyCollection())).thenReturn(Collections.singletonMap("u1",30D));when(states.acknowledge(eq(refreshed),anyList(),eq(1L))).thenReturn(true);
        worker.tick();verify(redis).mark(refreshed,1);verify(states).acknowledge(eq(refreshed),anyList(),eq(1L));verify(states,never()).acknowledge(eq(lease),anyList(),anyLong());
    }
}
