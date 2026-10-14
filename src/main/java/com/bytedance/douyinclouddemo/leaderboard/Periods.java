package com.bytedance.douyinclouddemo.leaderboard;

import java.time.*;
import java.time.format.DateTimeFormatter;

public final class Periods {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final long BACKFILL_MILLIS = Duration.ofHours(48).toMillis();
    public static final long REVIEW_MILLIS = Duration.ofDays(30).toMillis();
    public static final long CACHE_GRACE_MILLIS = Duration.ofSeconds(30).toMillis();
    private Periods() { }
    public static long day(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate().atStartOfDay(ZONE).toInstant().toEpochMilli();
    }
    public static long cycle(long millis) {
        ZonedDateTime time = Instant.ofEpochMilli(millis).atZone(ZONE);
        YearMonth month = YearMonth.from(time);
        for (int back = 0; back < 2; back++, month = month.minusMonths(1)) {
            for (int day : new int[]{30, 20, 10}) {
                if (day > month.lengthOfMonth()) continue;
                ZonedDateTime boundary = month.atDay(day).atTime(2, 0).atZone(ZONE);
                if (!boundary.isAfter(time)) return boundary.toInstant().toEpochMilli();
            }
        }
        throw new IllegalStateException("无法计算周期");
    }
    public static long end(int type, long start) {
        ZonedDateTime time = Instant.ofEpochMilli(start).atZone(ZONE);
        if (type == 0) return time.toLocalDate().plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        for (LocalDate date = time.toLocalDate().plusDays(1); ; date = date.plusDays(1)) {
            if (date.getDayOfMonth() == 10 || date.getDayOfMonth() == 20 || date.getDayOfMonth() == 30) {
                return date.atTime(2, 0).atZone(ZONE).toInstant().toEpochMilli();
            }
        }
    }
    public static String month(long receivedAt) {
        return Instant.ofEpochMilli(receivedAt).atZone(ZONE).format(DateTimeFormatter.ofPattern("yyyyMM"));
    }
    public static String detailTable(String month) {
        if (month == null || !month.matches("[0-9]{4}(0[1-9]|1[0-2])")) throw new IllegalArgumentException("非法明细月份");
        return "gift_detail_" + month;
    }
}
