package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GiftClassifier {
    static final String LUCKY_CUBE_ID="9tPFzcpEQFovisU3j3coz5tqj/qQ5LHJQJWob/X5bLbxm1s7kYLj0aGSb4k=";
    static final String LUCKY_CUBE_RULE="lucky-cube-1diamond-v1";
    private final ObjectMapper mapper;

    public GiftData classify(LeaderboardProperties.App app, String room, JsonNode node, long receivedAt) {
        if (!node.isObject()) throw RankException.badRequest("礼物消息必须为对象");
        GiftData gift = new GiftData();
        gift.setAppId(ScoreRules.identifier(app.getAppId(),64,false));
        gift.setRoomId(ScoreRules.identifier(room,64,false));
        gift.setMsgType("live_gift");
        gift.setMsgId(ScoreRules.identifier(text(node,"msg_id"),128,false));
        gift.setGame(ScoreRules.identifier(app.getGame(),128,false));
        gift.setPlatform(ScoreRules.identifier(app.getPlatform(),128,false));
        gift.setFaction(ScoreRules.identifier(app.getFaction(),128,false));
        gift.setZhubo(ScoreRules.identifier(app.getZhubo(),128,true));
        gift.setFirstReceivedAt(receivedAt);
        gift.setStorageMonth(Periods.month(receivedAt));
        gift.setStatus("BOOKED");
        gift.setNickname(display(node,"nickname",256));
        gift.setAvatar(display(node,"avatar_url",2048));
        try {
            gift.setFingerprint(ScoreRules.sha256(mapper.writeValueAsString(Arrays.asList(gift.getGame(),gift.getPlatform(),
                    gift.getFaction(),gift.getZhubo(),node.get("sec_openid"),node.get("sec_gift_id"),node.get("gift_num"),
                    normalizedValue(node),node.get("timestamp"),node.has("test")?node.get("test"):Boolean.FALSE,node.get("sec_magic_gift_id")))));
            Map<String,Object> pending = new LinkedHashMap<>();
            pending.put("game",gift.getGame()); pending.put("platform",gift.getPlatform());
            pending.put("faction",gift.getFaction()); pending.put("zhubo",gift.getZhubo());
            com.fasterxml.jackson.databind.node.ObjectNode preserved=mapper.createObjectNode();
            for(String field:new String[]{"msg_id","sec_openid","sec_gift_id","gift_num","gift_value","timestamp","test","sec_magic_gift_id","nickname","avatar_url"}) {
                if(node.has(field))preserved.set(field,node.get(field));
            }
            pending.put("timestampUnit",app.getTimestampUnit().name()); pending.put("message",preserved);
            gift.setPendingPayload(mapper.writeValueAsString(pending));
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        try { gift.setUserId(ScoreRules.identifier(text(node,"sec_openid"),128,false)); }
        catch (RankException e) { pending(gift,"INVALID_USER"); }
        try { gift.setGiftId(ScoreRules.identifier(text(node,"sec_gift_id"),128,false)); }
        catch (RankException e) { pending(gift,"INVALID_GIFT"); }
        JsonNode number = node.get("gift_num");
        if (number != null && number.isIntegralNumber() && number.canConvertToLong() && number.longValue() > 0) gift.setGiftNum(number.longValue());
        else pending(gift,"INVALID_QUANTITY");
        JsonNode value = node.get("gift_value");
        if (value != null && value.isNumber()) {
            BigDecimal decimal = value.decimalValue();
            if (decimal.signum() >= 0 && decimal.stripTrailingZeros().scale() <= 6 && decimal.precision()-decimal.scale() <= 18) gift.setGiftValue(decimal);
            else pending(gift,"INVALID_VALUE");
        } else pending(gift,"INVALID_VALUE");
        JsonNode timestamp = node.get("timestamp");
        if (timestamp != null && timestamp.isIntegralNumber() && timestamp.canConvertToLong()) {
            try {
                long event = timestamp.longValue();
                if (app.getTimestampUnit() == LeaderboardProperties.TimestampUnit.SECONDS) event = Math.multiplyExact(event,1000);
                if (event < 1451606400000L || event > receivedAt + 30000L) pending(gift,"INVALID_TIME");
                else { gift.setEventAt(event); if (receivedAt-event > Periods.BACKFILL_MILLIS) pending(gift,"LATE_EVENT"); }
            } catch (ArithmeticException e) { pending(gift,"INVALID_TIME"); }
        } else pending(gift,"INVALID_TIME");
        JsonNode test = node.get("test");
        if (test != null && !test.isBoolean()) pending(gift,"INVALID_TEST_FLAG");
        boolean luckyCube=LUCKY_CUBE_ID.equals(text(node,"sec_magic_gift_id"));
        if (hasSpecialGift(node) && !luckyCube) pending(gift,"SPECIAL_GIFT");
        if (gift.getGiftValue() != null) {
            // 幸运魔方每条来源消息按 1 钻流水计分，随机效果价值和数量保留但不参与换算。
            if(luckyCube)gift.setRuleVersion(LUCKY_CUBE_RULE);
            try { gift.setScoreDelta(ScoreRules.score(luckyCube?BigDecimal.TEN:gift.getGiftValue())); }
            catch (ArithmeticException e) { pending(gift,"SCORE_OVERFLOW"); }
        }
        if (test != null && test.isBoolean() && test.booleanValue()) filtered(gift,"TEST_MESSAGE");
        else if (gift.getGiftId() != null && !app.getGiftIds().isEmpty() && !app.getGiftIds().contains(gift.getGiftId())) filtered(gift,"OUTSIDE_GIFT_RANGE");
        else if ("BOOKED".equals(gift.getStatus()) && gift.getScoreDelta() != null && gift.getScoreDelta() == 0) filtered(gift,"ZERO_SCORE");
        gift.setReviewPayload(gift.getPendingPayload());
        if (!"PENDING_REVIEW".equals(gift.getStatus())) gift.setPendingPayload(null);
        return gift;
    }
    static boolean hasSpecialGift(JsonNode node) {
        JsonNode value=node.get("sec_magic_gift_id");
        return value!=null && !value.isNull() && !(value.isTextual() && value.textValue().isEmpty());
    }
    private Object normalizedValue(JsonNode node) {
        JsonNode value = node.get("gift_value");
        if(value==null||!value.isNumber())return value;
        BigDecimal decimal=value.decimalValue().stripTrailingZeros();
        return decimal.scale()>=-18 && decimal.scale()<=6 ? decimal.toPlainString() : decimal.toString();
    }
    private void pending(GiftData gift,String reason) { gift.setStatus("PENDING_REVIEW"); if (gift.getReason()==null) gift.setReason(reason); }
    private void filtered(GiftData gift,String reason) { gift.setStatus("FILTERED"); gift.setReason(reason); }
    private String display(JsonNode node,String key,int max) {
        String text = text(node,key); return text == null || text.isEmpty() || text.length()>max ? null : text;
    }
    private String text(JsonNode node,String key) { JsonNode value=node.get(key); return value!=null && value.isTextual() ? value.textValue() : null; }
}
