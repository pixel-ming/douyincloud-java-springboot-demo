package com.bytedance.douyinclouddemo.leaderboard;

import lombok.Data;
import java.util.Set;

@Data
public class MigrationPlan {
    private String batchId,sourceId,expectedDigest,evidence,mode;
    private Set<String> games;
    private Long cutoverAt,dayStart,cycleStart,expectedRows;
    private boolean includesAllGames,legacyFrozen;
}
