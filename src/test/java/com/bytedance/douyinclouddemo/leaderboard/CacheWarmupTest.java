package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.*;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CacheWarmupTest {
    private final CacheStateStore states=mock(CacheStateStore.class);
    private final MigrationStore migrations=mock(MigrationStore.class);
    private final LegacyScopeStore scopes=mock(LegacyScopeStore.class);
    private final LeaderboardProperties props=new LeaderboardProperties();
    private final Fixtures.MutableClock clock=new Fixtures.MutableClock();
    private final CacheWarmup warmup=new CacheWarmup(states,migrations,scopes,props,clock);
    private final RankScope game=new RankScope("BJGYB2",null,null,null);
    private final RankScope all=new RankScope(null,null,null,null);
    @BeforeEach void prepare(){when(scopes.find(any())).thenAnswer(call->call.getArgument(0));}
    private void addGame(String name){LeaderboardProperties.App app=new LeaderboardProperties.App();app.setGame(name);props.getApps().put(name,app);}
    private void nextPoll(){ReflectionTestUtils.setField(warmup,"checkAfter",0L);}

    @Test void thirteenMigratedGamesCreateTwentyEightBoardsWithoutAQuery(){
        for(String name:LegacySnapshotConverter.GAMES)addGame(name);
        warmup.register();verify(states,times(28)).ensure(any(),eq(Periods.day(clock.millis())),eq(Periods.cycle(clock.millis())),anyInt());
        verify(states).ensure(all,Periods.day(clock.millis()),Periods.cycle(clock.millis()),1);
        verify(states).ensure(all,Periods.day(clock.millis()),Periods.cycle(clock.millis()),0);
        assertFalse(warmup.register());verifyNoMoreInteractions(states);
    }
    @Test void actualEnabledScopeAndExplicitAliasesAreCanonicalizedOnce(){
        props.getApps().put("main",Fixtures.app());RankScope actual=new RankScope("BJGYB2","DYGYB2","红方",null);
        LeaderboardProperties.WarmupScope alias=new LeaderboardProperties.WarmupScope();alias.setGame("bjgyb2");alias.setPlatform("dygyb2");alias.setFaction("红方");props.getWarmupScopes().add(alias);
        when(scopes.find(alias.toRankScope())).thenReturn(actual);
        warmup.register();verify(states,times(6)).ensure(any(),anyLong(),anyLong(),anyInt());
        verify(states).ensure(actual,Periods.day(clock.millis()),Periods.cycle(clock.millis()),1);
        verify(states).ensure(actual,Periods.day(clock.millis()),Periods.cycle(clock.millis()),0);
    }
    @Test void stagingGamesAndIncompleteAllDoNotBecomeWarmBoards(){
        addGame("BJGYB2");addGame("BJNC");RankScope pending=new RankScope("BJNC",null,null,null);
        doThrow(RankException.unavailable()).when(migrations).assertQueryable(pending);doThrow(RankException.unavailable()).when(migrations).assertQueryable(all);
        warmup.register();verify(states,times(2)).ensure(eq(game),anyLong(),anyLong(),anyInt());verify(scopes,never()).find(pending);verify(scopes,never()).find(all);
        doNothing().when(migrations).assertQueryable(pending);doNothing().when(migrations).assertQueryable(all);nextPoll();warmup.register();
        verify(states).ensure(pending,Periods.day(clock.millis()),Periods.cycle(clock.millis()),1);verify(states).ensure(all,Periods.day(clock.millis()),Periods.cycle(clock.millis()),0);
    }
    @Test void missingDirectoryCannotRegisterAFakeEmptyBoard(){
        addGame("BJGYB2");when(scopes.find(game)).thenReturn(null);when(scopes.find(all)).thenReturn(null);warmup.register();verifyNoInteractions(states);
    }
    @Test void midnightAndCycleBoundaryBothRenewBoardsDespitePollThrottle(){
        addGame("BJGYB2");warmup.register();long oldCycle=Periods.cycle(clock.millis());
        clock.millis=Periods.end(0,Periods.day(clock.millis()));long newDay=Periods.day(clock.millis());warmup.register();
        verify(states).ensure(game,newDay,oldCycle,1);verify(states).ensure(game,newDay,oldCycle,0);
        clock.millis=Periods.end(1,oldCycle);warmup.register();
        verify(states).ensure(game,newDay,clock.millis(),1);verify(states).ensure(game,newDay,clock.millis(),0);
        verify(states,times(12)).ensure(any(),anyLong(),anyLong(),anyInt());
    }
    @Test void capacityDeferralDoesNotPreventOtherBoardsOrRepeatedChecks(){
        addGame("BJGYB2");when(states.ensure(eq(game),anyLong(),anyLong(),eq(1))).thenThrow(RankException.unavailable());
        assertDoesNotThrow(warmup::register);verify(states).ensure(all,Periods.day(clock.millis()),Periods.cycle(clock.millis()),0);
        warmup.register();verify(states,times(4)).ensure(any(),anyLong(),anyLong(),anyInt());
        nextPoll();warmup.register();verify(states,times(8)).ensure(any(),anyLong(),anyLong(),anyInt());
    }
    @Test void databaseFailurePropagatesRatherThanLookingLikeAReadyWarmup(){
        addGame("BJGYB2");doThrow(new IllegalStateException("database unavailable")).when(migrations).assertQueryable(game);
        assertThrows(IllegalStateException.class,warmup::register);verifyNoInteractions(states);
    }
    @Test void restartedRegistrarReusesEnsureWithoutInvalidatingCache(){
        addGame("BJGYB2");warmup.register();new CacheWarmup(states,migrations,scopes,props,clock).register();
        verify(states,times(8)).ensure(any(),anyLong(),anyLong(),anyInt());verify(states,never()).invalidateAll(any());verify(states,never()).requestRebuild(any());
    }
    @Test void rehearsalScopesBindAndMalformedScopeFailsConfiguration()throws Exception{
        MockEnvironment env=new MockEnvironment();new YamlPropertySourceLoader().load("local",new ClassPathResource("application-local-rehearsal.yml")).forEach(source->env.getPropertySources().addLast(source));
        LeaderboardProperties bound=Binder.get(env).bind("leaderboard",LeaderboardProperties.class).get();assertEquals(1,bound.getWarmupScopes().size());assertEquals(new RankScope("BJGYB2","DYGYB2","红方",null),bound.getWarmupScopes().get(0).toRankScope());
        LeaderboardProperties.WarmupScope bad=new LeaderboardProperties.WarmupScope();bad.setGame("invalid\n");props.getWarmupScopes().add(bad);
        assertThrows(RankException.class,()->new LeaderboardConfiguration(props).validate());
    }
}
