package com.bytedance.douyinclouddemo.leaderboard;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Collections;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class LiveGiftTaskController {
    private final LeaderboardProperties properties;
    private final CallbackVerifier verifier;
    private final LiveGiftOpenApi api;
    private final LiveGiftRooms rooms;
    private final Clock clock;
    private final CacheStateStore states;
    @PostMapping("/internal/douyin/{alias}/rooms/{room}/{action}")
    public Map<String,Object> task(@PathVariable String alias,@PathVariable String room,@PathVariable String action,@RequestHeader(value="Authorization",required=false) String authorization) {
        String token=properties.getInternalToken();
        if(token==null||token.length()<32)throw new RankException(503,50303,"任务管理尚未配置");
        if(authorization==null||!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),authorization.getBytes(StandardCharsets.UTF_8)))throw new RankException(403,40301,"任务管理访问被拒绝");
        ScoreRules.identifier(room,64,false);LeaderboardProperties.App app=verifier.app(alias);
        states.validateEnvironment();
        api.task(app,room,action);
        rooms.task(alias,app.getAppId(),room,"start".equals(action)?"STARTED":"STOPPED",clock.millis());
        return Collections.singletonMap("status","success");
    }
}
