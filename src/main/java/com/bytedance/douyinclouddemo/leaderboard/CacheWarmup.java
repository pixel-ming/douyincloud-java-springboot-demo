package com.bytedance.douyinclouddemo.leaderboard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmup {
    private final CacheStateStore states;
    private final MigrationStore migrations;
    private final LegacyScopeStore scopes;
    private final LeaderboardProperties properties;
    private final Clock clock;
    private long checkAfter;
    private long lastDay=Long.MIN_VALUE,lastCycle=Long.MIN_VALUE;

    public boolean register() {
        long now=clock.millis(),day=Periods.day(now),cycle=Periods.cycle(now),wall=System.currentTimeMillis();
        if(day==lastDay&&cycle==lastCycle&&wall<checkAfter)return false;
        lastDay=day;lastCycle=cycle;checkAfter=wall+5000;
        Set<RankScope> requested=new LinkedHashSet<>();
        for(LeaderboardProperties.App app:properties.getApps().values()) {
            if(app.getGame()==null||app.getGame().isEmpty())continue;
            requested.add(new RankScope(app.getGame(),null,null,null));
            if(app.isEnabled())requested.add(new RankScope(app.getGame(),app.getPlatform(),app.getFaction(),app.getZhubo()));
        }
        if(!requested.isEmpty())requested.add(new RankScope(null,null,null,null));
        for(LeaderboardProperties.WarmupScope scope:properties.getWarmupScopes())requested.add(scope.toRankScope());
        Set<RankScope> registered=new HashSet<>();int deferred=0;boolean attempted=false;
        for(RankScope requestedScope:requested) {
            try{migrations.assertQueryable(requestedScope);}catch(RankException e){if(e.getHttpStatus()!=503)throw e;continue;}
            RankScope scope=scopes.find(requestedScope);if(scope==null||!registered.add(scope))continue;
            for(int metric:new int[]{1,0}) {
                attempted=true;
                try{states.ensure(scope,day,cycle,metric);}catch(RankException e){if(e.getHttpStatus()!=503)throw e;deferred++;}
            }
        }
        if(deferred>0){checkAfter=wall+30000;log.warn("排行榜常用榜登记延期：count={}",deferred);}
        return attempted;
    }
}
