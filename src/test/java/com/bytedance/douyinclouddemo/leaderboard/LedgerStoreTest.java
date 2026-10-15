package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.KeyHolder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerStoreTest {
    @Test void luckyCubeRuleAndRawValueArePersistedWithoutExecutingSql()throws Exception{
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        LedgerStore store=new LedgerStore(jdbc,mock(NamedParameterJdbcTemplate.class));
        Connection connection=mock(Connection.class);PreparedStatement statement=mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString(),anyInt())).thenReturn(statement);
        when(jdbc.update(any(PreparedStatementCreator.class),any(KeyHolder.class))).thenAnswer(call->{
            call.<PreparedStatementCreator>getArgument(0).createPreparedStatement(connection);
            call.<KeyHolder>getArgument(1).getKeyList().add(Collections.singletonMap("id",7L));return 1;
        });
        when(jdbc.update(anyString(),org.mockito.ArgumentMatchers.<Object[]>any())).thenAnswer(call->{
            String sql=call.getArgument(0);Object[] values=java.util.Arrays.copyOfRange(call.getArguments(),1,call.getArguments().length);
            if(sql.startsWith("INSERT INTO gift_detail_")){
                assertEquals(new java.math.BigDecimal("190"),values[8]);assertEquals(3L,values[9]);
                assertEquals(GiftClassifier.LUCKY_CUBE_RULE,values[10]);
            }else if(sql.startsWith("UPDATE gift_receipt"))assertEquals(GiftClassifier.LUCKY_CUBE_RULE,values[3]);
            return 1;
        });
        com.fasterxml.jackson.databind.node.ObjectNode message=(com.fasterxml.jackson.databind.node.ObjectNode)Fixtures.mapper().readTree(Fixtures.message("m1",Fixtures.NOW));
        message.put("sec_magic_gift_id",GiftClassifier.LUCKY_CUBE_ID);message.put("gift_value",190);
        GiftData gift=new GiftClassifier(Fixtures.mapper()).classify(Fixtures.app(),"room1",message,Fixtures.NOW);
        assertEquals(7L,store.insertReceipt(gift));verify(statement).setObject(11,3L);
        verify(statement).setObject(12,GiftClassifier.LUCKY_CUBE_RULE);
        store.book(7,gift,Fixtures.NOW);
        assertTrue(store.completeReview(7,gift,"operator","confirmed",Fixtures.NOW));
        verify(connection).prepareStatement(startsWith("INSERT INTO gift_receipt"),anyInt());
        verify(statement,never()).executeUpdate();
    }
}
