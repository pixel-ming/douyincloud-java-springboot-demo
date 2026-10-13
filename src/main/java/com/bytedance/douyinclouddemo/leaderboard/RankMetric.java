package com.bytedance.douyinclouddemo.leaderboard;

public enum RankMetric {
    DAILY_KILL(0,"daily_kill_sum",true,true), KILL(1,"kill_sum",false,true),
    POINTS1(2,"points1_sum",false,false), POINTS2(3,"points2_sum",false,false), POINTS3(4,"points3_sum",false,false),
    DAILY_POINTS1(5,"daily_points1_sum",true,false), DAILY_POINTS2(6,"daily_points2_sum",true,false), DAILY_POINTS3(7,"daily_points3_sum",true,false);
    public final int code;
    public final String column;
    public final boolean daily,integer;
    RankMetric(int code,String column,boolean daily,boolean integer){this.code=code;this.column=column;this.daily=daily;this.integer=integer;}
    public static RankMetric of(int code){for(RankMetric m:values())if(m.code==code)return m;throw new IllegalArgumentException("未知排行指标");}
    public static RankMetric requested(String name){if(name==null||name.isEmpty())return KILL;for(RankMetric m:values())if(m.column.equals(name))return m;throw new RankValidationException("OrderBy","OrderBy必须是八种累计指标之一");}
}
