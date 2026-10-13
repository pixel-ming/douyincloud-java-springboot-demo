package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.Collections;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CacheStateStoreTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final LedgerStore ledger=mock(LedgerStore.class);
    private final CacheStateStore states=new CacheStateStore(jdbc,Fixtures.transactions(),Fixtures.mapper(),new LeaderboardProperties(),ledger);
    private final CacheStateStore.Lease expected=new CacheStateStore.Lease("owner",1,3,0,Fixtures.NOW+30000);
    @BeforeEach @SuppressWarnings("unchecked") void prepare(){when(jdbc.queryForObject(contains("UNIX_TIMESTAMP"),eq(Long.class))).thenReturn(Fixtures.NOW);when(jdbc.queryForObject(contains("FOR UPDATE"),any(RowMapper.class),eq("local"))).thenReturn(expected);}
    @Test void matchingRegistryAndLeaseCanConfirmReadVersion(){assertTrue(states.acknowledge(expected,Collections.singletonList(Fixtures.counter()),1));verify(ledger).acknowledge(anyList());}
    @Test @SuppressWarnings("unchecked") void changedRegistryCannotClearSharedPending(){when(jdbc.queryForObject(contains("FOR UPDATE"),any(RowMapper.class),eq("local"))).thenReturn(new CacheStateStore.Lease("owner",1,4,0,Fixtures.NOW+30000));assertFalse(states.acknowledge(expected,Collections.singletonList(Fixtures.counter()),1));verifyNoInteractions(ledger);}
    @Test @SuppressWarnings("unchecked") void staleOwnerCannotConfirmOrPublish(){when(jdbc.queryForObject(contains("FOR UPDATE"),any(RowMapper.class),eq("local"))).thenReturn(new CacheStateStore.Lease("new-owner",2,3,0,Fixtures.NOW+30000));assertFalse(states.acknowledge(expected,Collections.singletonList(Fixtures.counter()),1));assertFalse(states.publish(Fixtures.board(0),"build",expected,"evidence"));verifyNoInteractions(ledger);}
    @Test @SuppressWarnings("unchecked") void expiredLeaseCannotConfirmReplay(){when(jdbc.queryForObject(contains("FOR UPDATE"),any(RowMapper.class),eq("local"))).thenReturn(new CacheStateStore.Lease("owner",1,3,0,Fixtures.NOW-1));Runnable update=mock(Runnable.class);assertFalse(states.confirmReplay(expected,update));verifyNoInteractions(update);}
}
