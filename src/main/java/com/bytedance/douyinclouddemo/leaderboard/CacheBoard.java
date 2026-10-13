package com.bytedance.douyinclouddemo.leaderboard;

import lombok.Data;

@Data
public class CacheBoard {
    private RankScope scope;
    private int rankType;
    private long periodStart,periodEnd,dayStart,cycleStart;
    private String activeGeneration,buildingGeneration,status;
    private Long buildToken;
    public boolean ready() { return activeGeneration!=null && !"EXPIRED".equals(status); }
    public String identity() { return scope.id()+":"+rankType+":"+dayStart+":"+cycleStart; }
}
