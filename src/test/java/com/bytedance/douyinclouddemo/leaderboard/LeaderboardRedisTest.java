package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="leaderboard.redis-test-port",matches="[0-9]+")
class LeaderboardRedisTest {
    private LettuceConnectionFactory connection;
    private StringRedisTemplate template;
    private LeaderboardProperties props;
    private LeaderboardRedis cache;
    private CacheBoard board;
    private String generation;
    @BeforeEach void start(){connection=new LettuceConnectionFactory("127.0.0.1",Integer.parseInt(System.getProperty("leaderboard.redis-test-port")));connection.afterPropertiesSet();template=new StringRedisTemplate(connection);template.afterPropertiesSet();props=new LeaderboardProperties();props.setEnvironment("test-"+UUID.randomUUID().toString().substring(0,12));cache=new LeaderboardRedis(template,props);board=Fixtures.board(0);board.setDayStart(Periods.day(System.currentTimeMillis()));board.setCycleStart(Periods.cycle(System.currentTimeMillis()));board.setPeriodStart(board.getDayStart());board.setPeriodEnd(Math.min(Periods.end(0,board.getDayStart()),Periods.end(1,board.getCycleStart())));generation=UUID.randomUUID().toString();board.setActiveGeneration(generation);cache.initialize(board,generation);}
    @AfterEach void close(){cache.drop(board,generation);template.delete("lb:v2:"+props.getEnvironment()+":sync-marker");connection.destroy();}
    @Test void duplicateAndOutOfOrderRefreshNeverAddsOrLowersScore(){cache.write(board,generation,Collections.singletonMap("u1",30L));cache.write(board,generation,Collections.singletonMap("u1",30L));cache.write(board,generation,Collections.singletonMap("u1",10L));cache.complete(board,generation);assertEquals(30L,cache.top(board,10).get("u1").longValue());}
    @Test void integerNearPrecisionLimitIsExact(){cache.write(board,generation,Collections.singletonMap("u1",ScoreRules.MAX_SCORE));cache.complete(board,generation);assertEquals(ScoreRules.MAX_SCORE,cache.top(board,10).get("u1").longValue());}
    @Test void incompleteGenerationIsNeverQueryable(){cache.write(board,generation,Collections.singletonMap("u1",30L));assertThrows(RankException.class,()->cache.top(board,10));cache.complete(board,generation);assertEquals(1,cache.top(board,10).size());}
    @Test void validEmptyBoardDiffersFromLostNonemptyBoard(){cache.complete(board,generation);assertTrue(cache.top(board,10).isEmpty());cache.write(board,generation,Collections.singletonMap("u1",10L));template.delete(cache.key(board,generation));assertThrows(RankException.class,()->cache.top(board,10));assertThrows(RankException.class,()->cache.write(board,generation,Collections.singletonMap("u2",20L)));assertFalse(Boolean.TRUE.equals(template.hasKey(cache.key(board,generation))));}
    @Test void firstScoreOnCompletedEmptyBoardInheritsTheRemainingExpiry(){
        board.setPeriodEnd(System.currentTimeMillis()+60000-Periods.CACHE_GRACE_MILLIS);
        cache.complete(board,generation);cache.write(board,generation,Collections.singletonMap("u1",10L));
        long scoreTtl=template.getExpire(cache.key(board,generation),TimeUnit.MILLISECONDS);
        long metaTtl=template.getExpire(cache.key(board,generation)+":meta",TimeUnit.MILLISECONDS);
        assertTrue(scoreTtl>0&&scoreTtl<=60000);assertTrue(metaTtl>0&&Math.abs(scoreTtl-metaTtl)<1000);
        assertEquals(10D,cache.top(board,10).get("u1"));
    }
    @Test void tiedScoresUseMemberByteOrderAndBatchReadFillsOnlyMissingUser(){Map<String,Long> rows=new LinkedHashMap<>();rows.put("u1",20L);rows.put("u2",20L);cache.write(board,generation,rows);cache.complete(board,generation);assertEquals(Arrays.asList("u2","u1"),new ArrayList<>(cache.top(board,10).keySet()));assertEquals(0L,cache.scores(board,Arrays.asList("u1","missing")).get("missing").longValue());}
    @Test void decimalPointMetricKeepsScoreAndZeroMember(){cache.drop(board,generation);board.setRankType(2);cache.initialize(board,generation);cache.write(board,generation,Collections.singletonMap("u1",0D));cache.write(board,generation,Collections.singletonMap("u2",1.25D));cache.complete(board,generation);assertEquals(Arrays.asList("u2","u1"),new ArrayList<>(cache.top(board,10).keySet()));assertEquals(1.25D,cache.top(board,10).get("u2"));}
    @Test void capacityFailureDoesNotPartiallyAppendMembers(){props.setMaxMembers(1);cache.write(board,generation,Collections.singletonMap("u1",10L));Map<String,Long> two=new LinkedHashMap<>();two.put("u2",20L);two.put("u3",30L);assertThrows(RankException.class,()->cache.write(board,generation,two));cache.complete(board,generation);assertEquals(Collections.singletonMap("u1",10D),cache.top(board,10));}
    @Test void retiredGenerationCannotBeResurrectedByOldTask(){cache.complete(board,generation);cache.retire(board,generation);assertThrows(RankException.class,()->cache.write(board,generation,Collections.singletonMap("u1",10L)));}
    @Test void staleOwnerCannotAdvanceNewOwnersMarker(){CacheStateStore.Lease first=new CacheStateStore.Lease("a",1,0,0,Fixtures.NOW+30000);assertEquals("MISSING",cache.installMarker(first));cache.mark(first,1);CacheStateStore.Lease next=new CacheStateStore.Lease("b",2,0,1,Fixtures.NOW+30000);assertEquals("MATCH",cache.installMarker(next));assertThrows(RankException.class,()->cache.mark(first,2));cache.mark(next,2);assertTrue(cache.abandonMarker(next,2));assertTrue(cache.healthy(next));}
}
