package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes","unchecked"})
class LeaderboardRedisCleanupTest {
    private final StringRedisTemplate template=mock(StringRedisTemplate.class);
    private final LeaderboardProperties props=new LeaderboardProperties();
    private final LeaderboardRedis redis=new LeaderboardRedis(template,props);
    private final String generation="11111111-2222-3333-4444-555555555555";
    @BeforeEach void scripts(){when(template.execute(any(DefaultRedisScript.class),anyList(),anyString())).thenReturn(1L);when(template.execute(any(DefaultRedisScript.class),anyList(),anyString(),anyString())).thenReturn(1L);}
    @Test void completeSetsExpiryBeforePublishingAndNeverPersistsKeys(){
        CacheBoard board=Fixtures.board(0);redis.complete(board,generation);
        verify(template).execute(any(DefaultRedisScript.class),eq(Arrays.asList(redis.key(board,generation),redis.key(board,generation)+":meta")),eq(Long.toString(board.getPeriodEnd()+Periods.CACHE_GRACE_MILLIS)));
        verify(template,never()).persist(anyString());
        when(template.execute(any(DefaultRedisScript.class),anyList(),anyString())).thenReturn(0L);
        assertThrows(RankException.class,()->redis.complete(board,generation));
    }
    @Test void keyParserAcceptsBothBoundaryCyclesButRejectsOtherNamespacesAndMarkers(){
        long day=LocalDateTime.parse("2026-10-20T00:00:00").atZone(Periods.ZONE).toInstant().toEpochMilli();
        CacheBoard board=Fixtures.board(0);board.setDayStart(day);board.setCycleStart(Periods.cycle(day));
        assertEquals(day+7200000+Periods.CACHE_GRACE_MILLIS,redis.keyExpiry(redis.key(board,generation)));
        board.setCycleStart(Periods.cycle(day+7200000));
        assertEquals(Periods.end(0,day)+Periods.CACHE_GRACE_MILLIS,redis.keyExpiry(redis.key(board,generation)+":meta"));
        assertEquals(0,redis.keyExpiry(redis.key(board,generation).replace("lb:v2:local:","lb:v2:prod:")));
        assertEquals(0,redis.keyExpiry("lb:v2:local:sync-marker"));
        board.setDayStart(day+1);assertEquals(0,redis.keyExpiry(redis.key(board,generation)));
    }
    @Test void expiredUnreferencedKeysAreRemovedButLiveReferencesAndCurrentKeysAreProtected(){
        CacheBoard old=Fixtures.board(0),current=Fixtures.board(0),referenced=Fixtures.board(0);
        current.setDayStart(Periods.end(0,old.getDayStart()));current.setCycleStart(Periods.cycle(current.getDayStart()));
        referenced.setActiveGeneration(generation);
        String oldKey=redis.key(old,generation),liveKey=redis.key(referenced,generation),currentKey=redis.key(current,generation);
        referenced.setScope(new RankScope("other-game",null,null,null));liveKey=redis.key(referenced,generation);
        when(template.execute(any(DefaultRedisScript.class),anyList(),anyString(),anyString(),anyString())).thenReturn(Arrays.asList("0",Arrays.asList(oldKey,liveKey,currentKey,"lb:v2:local:sync-marker")));
        redis.repairExpirations(old.getPeriodEnd()+Periods.CACHE_GRACE_MILLIS,10,Collections.singletonList(referenced));
        verify(template).execute(any(DefaultRedisScript.class),eq(Arrays.asList(oldKey,oldKey+":meta")),eq(Long.toString(old.getPeriodEnd()+Periods.CACHE_GRACE_MILLIS)),anyString());
        verify(template,never()).execute(any(DefaultRedisScript.class),eq(Arrays.asList(liveKey,liveKey+":meta")),anyString(),anyString());
        verify(template).execute(any(DefaultRedisScript.class),eq(Arrays.asList(currentKey,currentKey+":meta")),anyString(),anyString());
    }
    @Test void scanKeepsEmptyPageCursorAndDoesNotDiscardKeysAboveTheBatchLimit(){
        CacheBoard board=Fixtures.board(0);String key=redis.key(board,generation);
        when(template.execute(any(DefaultRedisScript.class),anyList(),anyString(),anyString(),anyString()))
                .thenReturn(Arrays.asList("7",Collections.emptyList()),Arrays.asList("0",Arrays.asList(key,key+":meta")));
        long now=board.getPeriodEnd()+Periods.CACHE_GRACE_MILLIS;
        redis.repairExpirations(now,1,Collections.emptyList());
        redis.repairExpirations(now,1,Collections.emptyList());
        redis.repairExpirations(now,1,Collections.emptyList());
        verify(template).execute(any(DefaultRedisScript.class),eq(Collections.emptyList()),eq("7"),eq("lb:v2:local:*:g*"),eq("1"));
        verify(template,times(2)).execute(any(DefaultRedisScript.class),anyList(),anyString(),anyString(),anyString());
        verify(template,times(2)).execute(any(DefaultRedisScript.class),eq(Arrays.asList(key,key+":meta")),anyString(),anyString());
    }
}
