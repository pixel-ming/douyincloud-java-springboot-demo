package com.bytedance.douyinclouddemo.leaderboard;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RankController {
    private final RankQueryService queries;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    @PostMapping("/api/v1/game/get_kill_rank2")
    public org.springframework.http.ResponseEntity<Map<String,Object>> rank(@RequestBody(required=false) String body) throws java.io.IOException {
        if(body==null||body.trim().isEmpty())return LegacyRankErrorHandler.response(400,10011,"参数不能为空",null);
        RankRequest request=mapper.readValue(body,RankRequest.class);
        if(request==null)request=new RankRequest();
        return LegacyRankErrorHandler.response(200,0,"success",queries.query(request));
    }
}
