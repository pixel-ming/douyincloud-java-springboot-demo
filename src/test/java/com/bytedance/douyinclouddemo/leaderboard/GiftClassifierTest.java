package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GiftClassifierTest {
    private final com.fasterxml.jackson.databind.ObjectMapper mapper=Fixtures.mapper();
    private final GiftClassifier classifier=new GiftClassifier(mapper);
    private ObjectNode node()throws Exception{return (ObjectNode)mapper.readTree(Fixtures.message("m1",Fixtures.NOW));}
    @Test void totalValueDoesNotMultiplyQuantity()throws Exception{GiftData g=classifier.classify(Fixtures.app(),"room1",node(),Fixtures.NOW);assertEquals("BOOKED",g.getStatus());assertEquals(30L,g.getScoreDelta().longValue());assertNull(g.getPendingPayload());}
    @Test void testGiftIsFilteredRegardlessOfValue()throws Exception{ObjectNode n=node();n.put("test",true);n.put("sec_magic_gift_id","");assertEquals("FILTERED",classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW).getStatus());}
    @Test void emptyMagicGiftIdDoesNotBlockOrdinaryGift()throws Exception{
        ObjectNode n=node();n.put("sec_magic_gift_id","");n.put("gift_value",10);n.put("gift_num",1);n.remove("test");
        GiftData gift=classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW);
        assertEquals("BOOKED",gift.getStatus());assertEquals(3L,gift.getScoreDelta().longValue());assertNull(gift.getPendingPayload());
        n.putNull("sec_magic_gift_id");assertEquals("BOOKED",classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW).getStatus());
    }
    @Test void malformedMagicGiftIdStillRequiresReview()throws Exception{
        for(String value:new String[]{"123","false","{}","[]","\" \""}){
            ObjectNode n=node();n.set("sec_magic_gift_id",mapper.readTree(value));
            GiftData gift=classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW);
            assertEquals("PENDING_REVIEW",gift.getStatus());assertEquals("SPECIAL_GIFT",gift.getReason());
        }
    }
    @Test void luckyCubeUsesOneDiamondInsteadOfRandomEffectValueOrQuantity()throws Exception{
        ObjectNode n=node();n.put("sec_magic_gift_id",GiftClassifier.LUCKY_CUBE_ID);n.put("gift_value",190);n.put("gift_num",19);
        GiftData gift=classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW);
        assertEquals("BOOKED",gift.getStatus());assertEquals(3L,gift.getScoreDelta().longValue());
        assertEquals(new java.math.BigDecimal("190"),gift.getGiftValue());assertEquals(19L,gift.getGiftNum().longValue());
        assertEquals(GiftClassifier.LUCKY_CUBE_RULE,gift.getRuleVersion());assertNull(gift.getPendingPayload());
        n.put("gift_value",10);n.put("gift_num",1);
        GiftData low=classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW);
        assertEquals(3L,low.getScoreDelta().longValue());assertNotEquals(gift.getFingerprint(),low.getFingerprint());
        assertEquals(ScoreRules.VERSION,Fixtures.gift().getRuleVersion());
    }
    @Test void luckyCubeStillFiltersTestsAndRejectsInvalidData()throws Exception{
        ObjectNode n=node();n.put("sec_magic_gift_id",GiftClassifier.LUCKY_CUBE_ID);n.put("test",true);
        GiftData test=classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW);
        assertEquals("FILTERED",test.getStatus());assertEquals("TEST_MESSAGE",test.getReason());
        for(String field:new String[]{"sec_openid","sec_gift_id","gift_num","gift_value","timestamp"}){
            n=node();n.put("sec_magic_gift_id",GiftClassifier.LUCKY_CUBE_ID);n.remove(field);
            assertEquals("PENDING_REVIEW",classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW).getStatus());
        }
        n=node();n.put("sec_magic_gift_id",GiftClassifier.LUCKY_CUBE_ID);n.put("gift_value",-1);
        assertEquals("INVALID_VALUE",classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW).getReason());
        n=node();n.put("sec_magic_gift_id",GiftClassifier.LUCKY_CUBE_ID);
        assertEquals("LATE_EVENT",classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW+Periods.BACKFILL_MILLIS+1).getReason());
    }
    @Test void lateEventIsKeptWithOriginalRoutingAndPayload()throws Exception{GiftData g=classifier.classify(Fixtures.app(),"room1",node(),Fixtures.NOW+Periods.BACKFILL_MILLIS+1);assertEquals("PENDING_REVIEW",g.getStatus());assertEquals(Fixtures.NOW,g.getEventAt().longValue());assertNotNull(g.getPendingPayload());}
    @Test void timestampUnitsAreExplicitNotGuessed()throws Exception{ObjectNode n=node();n.put("timestamp",Fixtures.NOW/1000);assertEquals("PENDING_REVIEW",classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW).getStatus());LeaderboardProperties.App app=Fixtures.app();app.setTimestampUnit(LeaderboardProperties.TimestampUnit.SECONDS);assertEquals("BOOKED",classifier.classify(app,"room1",n,Fixtures.NOW).getStatus());}
    @Test void mutableDisplayDoesNotChangeFingerprint()throws Exception{ObjectNode n=node();String first=classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW).getFingerprint();n.put("nickname","新昵称");n.put("avatar_url","https://example.com/new.png");assertEquals(first,classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW).getFingerprint());}
    @Test void numericScaleNormalizesButGroupChangeConflicts()throws Exception{ObjectNode n=node();String first=classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW).getFingerprint();n.put("gift_value",new java.math.BigDecimal("100.000"));assertEquals(first,classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW).getFingerprint());LeaderboardProperties.App app=Fixtures.app();app.setFaction("蓝方");assertNotEquals(first,classifier.classify(app,"room1",n,Fixtures.NOW).getFingerprint());}
    @Test void missingIdentityAndSpecialGiftAreNotAutomaticallyScored()throws Exception{ObjectNode n=node();n.remove("sec_openid");assertEquals("PENDING_REVIEW",classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW).getStatus());n=node();n.put("sec_magic_gift_id","magic");assertEquals("PENDING_REVIEW",classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW).getStatus());}
    @Test void clientKillFieldCannotOverrideScore()throws Exception{ObjectNode n=node();n.put("kill_num",999999);assertEquals(30L,classifier.classify(Fixtures.app(),"room1",n,Fixtures.NOW).getScoreDelta().longValue());}
    @Test void firstReceptionMonthIsIndependentFromEventMonth()throws Exception{long event=java.time.Instant.parse("2026-09-30T15:59:59Z").toEpochMilli();ObjectNode n=node();n.put("timestamp",event);GiftData gift=classifier.classify(Fixtures.app(),"room1",n,event+2000);assertEquals("202610",gift.getStorageMonth());assertEquals("BOOKED",gift.getStatus());}
}
