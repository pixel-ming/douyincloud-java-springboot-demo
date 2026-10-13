package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyScopeStoreTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final LegacyScopeStore store=new LegacyScopeStore(jdbc,Fixtures.mapper());
    private final RankScope canonical=new RankScope("BJGYB2","DYGYB2",null,null);
    private final RankScope alias=new RankScope("bjgyb2","dygyb2",null,null);
    @AfterEach void clean(){if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}
    private void returnedScope()throws Exception{when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any(),org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(Collections.singletonList(Fixtures.mapper().writeValueAsString(canonical)));}
    @Test void databaseComparisonResolvesCaseAndCachesOnlyPositiveResults()throws Exception{
        returnedScope();assertEquals(canonical,store.find(alias));assertEquals(canonical,store.find(alias));verify(jdbc,times(1)).query(contains("game=? AND platform=?"),org.mockito.ArgumentMatchers.<RowMapper<String>>any(),org.mockito.ArgumentMatchers.<Object[]>any());
    }
    @Test void unknownScopeIsNotCached(){when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any(),org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(Collections.emptyList());assertNull(store.find(alias));assertNull(store.find(alias));verify(jdbc,times(2)).query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any(),org.mockito.ArgumentMatchers.<Object[]>any());}
    @Test void rollbackCannotLeaveARegisteredScopeInMemory()throws Exception{
        returnedScope();TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.initSynchronization();assertEquals(canonical,store.register(alias));
        TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any(),org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(Collections.emptyList());assertNull(store.find(alias));
    }
    @Test void registrationRequiresTransaction(){assertThrows(IllegalStateException.class,()->store.register(alias));verifyNoInteractions(jdbc);}
}
