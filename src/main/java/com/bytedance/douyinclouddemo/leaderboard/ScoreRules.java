package com.bytedance.douyinclouddemo.leaderboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class ScoreRules {
    public static final String VERSION = "gift-fen-x3-div10-v1";
    public static final long MAX_SCORE = (1L << 53) - 1;
    private ScoreRules() { }
    public static long score(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("礼物价值非法");
        long score = value.multiply(BigDecimal.valueOf(3)).divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP).longValueExact();
        if (score > MAX_SCORE) throw new ArithmeticException("积分超出缓存精确范围");
        return score;
    }
    public static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex="0123456789abcdef".toCharArray();char[] out=new char[64];int i=0;
            for(byte b:bytes){out[i++]=hex[(b&255)>>>4];out[i++]=hex[b&15];}
            return new String(out);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String identifier(String value, int max, boolean allowEmpty) {
        if (value == null || (!allowEmpty && value.isEmpty()) || value.length() > max || !value.equals(value.trim())) {
            throw RankException.badRequest("业务标识缺失或超长");
        }
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) throw RankException.badRequest("业务标识含控制字符");
        return value;
    }
}
