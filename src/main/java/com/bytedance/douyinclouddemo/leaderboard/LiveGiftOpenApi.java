package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LiveGiftOpenApi {
    private final RestTemplate leaderboardOpenApiClient;
    private final LeaderboardProperties properties;
    private static final String BASE="http://webcast-bytedance-com.openapi.dyc.ivolces.com";
    public void task(LeaderboardProperties.App app,String room,String action) {
        enabled();if(!"start".equals(action)&&!"stop".equals(action))throw RankException.badRequest("未知推送任务操作");
        Map<String,String> body=new LinkedHashMap<>();body.put("appid",app.getAppId());body.put("roomid",room);body.put("msg_type","live_gift");
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        JsonNode response=leaderboardOpenApiClient.postForObject(BASE+"/api/live_data/task/"+action,new HttpEntity<>(body,headers),JsonNode.class);check(response);
    }
    public JsonNode failurePage(LiveGiftRooms.Room room) {
        enabled();
        java.net.URI uri=UriComponentsBuilder.fromHttpUrl(BASE+"/api/live_data/task/fail_data/get").queryParam("appid",room.getAppId()).queryParam("roomid",room.getRoomId())
                .queryParam("msg_type","live_gift").queryParam("page_num",room.getPage()).queryParam("page_size",100).build().encode().toUri();
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        JsonNode response=leaderboardOpenApiClient.exchange(uri,HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class).getBody();check(response);return response.get("data");
    }
    private void check(JsonNode node) {
        if(node==null||!node.path("err_no").isIntegralNumber()||!node.path("err_no").canConvertToInt()||node.path("err_no").intValue()!=0)throw new RankException(503,50305,"抖音推送任务调用失败");
    }
    private void enabled(){if(!properties.isCloudOpenapiEnabled())throw new RankException(503,50305,"抖音云内网OpenAPI尚未启用");}
}
