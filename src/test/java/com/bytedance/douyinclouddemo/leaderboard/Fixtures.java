package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;

final class Fixtures {
    static final long NOW=Instant.parse("2026-10-19T12:00:00Z").toEpochMilli();
    static ObjectMapper mapper(){return new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);}
    static LeaderboardProperties.App app(){
        LeaderboardProperties.App app=new LeaderboardProperties.App();app.setEnabled(true);app.setAppId("app-A");app.setGame("BJGYB2");
        app.setPlatform("DYGYB2");app.setFaction("红方");app.setSecret("test-secret-at-least-32-characters");return app;
    }
    static String message(String id,long time){return "{\"msg_id\":\""+id+"\",\"sec_openid\":\"u1\",\"sec_gift_id\":\"gift1\",\"gift_num\":10,\"gift_value\":100,\"timestamp\":"+time+",\"nickname\":\"用户\",\"test\":false}";}
    static GiftData gift()throws Exception{return new GiftClassifier(mapper()).classify(app(),"room1",mapper().readTree(message("m1",NOW)),NOW);}
    static PlatformTransactionManager transactions(){
        PlatformTransactionManager tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any())).thenAnswer(invocation->new SimpleTransactionStatus());return tx;
    }
    static CacheBoard board(int type){CacheBoard b=new CacheBoard();b.setScope(new RankScope("BJGYB2",null,null,null));b.setRankType(type);b.setDayStart(Periods.day(NOW));b.setCycleStart(Periods.cycle(NOW));b.setPeriodStart(type==0?b.getDayStart():b.getCycleStart());b.setPeriodEnd(Periods.end(type,b.getPeriodStart()));b.setActiveGeneration("active");b.setStatus("READY");return b;}
    static LedgerStore.Counter counter(){LedgerStore.Counter c=new LedgerStore.Counter();c.setId(1);c.setRevision(8);c.setGame("BJGYB2");c.setPlatform("DYGYB2");c.setFaction("红方");c.setZhubo("");c.setUserId("u1");c.setRankType(0);c.setPeriodStart(Periods.day(NOW));return c;}
    static class MutableClock extends Clock {
        long millis=NOW;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(millis);}
    }
}
