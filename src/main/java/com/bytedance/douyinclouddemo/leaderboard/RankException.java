package com.bytedance.douyinclouddemo.leaderboard;

import lombok.Getter;

@Getter
public class RankException extends RuntimeException {
    private final int httpStatus;
    private final int code;
    public RankException(int status, int code, String message) {
        super(message);
        this.httpStatus = status;
        this.code = code;
    }
    public static RankException badRequest(String message) { return new RankException(400, 40001, message); }
    public static RankException unavailable() { return new RankException(503, 50301, "排行榜暂不可用"); }
}
