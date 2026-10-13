package com.bytedance.douyinclouddemo.leaderboard;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class GiftData {
    private String appId, roomId, msgType, msgId, game, platform, faction, zhubo, userId;
    private String giftId, fingerprint, storageMonth, status, reason, pendingPayload, nickname, avatar;
    private String reviewPayload;
    private Long giftNum, eventAt, scoreDelta;
    private BigDecimal giftValue;
    private long firstReceivedAt;
}
