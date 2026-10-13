package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/leaderboard/migrations")
public class MigrationController {
    private final MigrationStore migrations;
    private final LeaderboardProperties properties;
    private final CacheStateStore states;
    @Data public static class Page { private int page;private List<JsonNode> rows; }
    private void authorize(String authorization){String token=properties.getInternalToken();if(token==null||token.length()<32||authorization==null||!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),authorization.getBytes(StandardCharsets.UTF_8)))throw new RankException(403,40301,"迁移管理访问被拒绝");states.validateEnvironment();}
    @PostMapping
    public Map<String,Object> begin(@RequestHeader(value="Authorization",required=false) String token,@RequestBody MigrationPlan plan){authorize(token);return migrations.begin(plan);}
    @PostMapping("/{batch}/pages")
    public Map<String,Object> append(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String batch,@RequestBody Page page){authorize(token);return migrations.append(batch,page.getPage(),page.getRows());}
    @PostMapping("/{batch}/finish")
    public Map<String,Object> finish(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String batch){authorize(token);return migrations.finish(batch);}
    @GetMapping("/{batch}")
    public Map<String,Object> progress(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String batch){authorize(token);return migrations.progress(batch);}
}
