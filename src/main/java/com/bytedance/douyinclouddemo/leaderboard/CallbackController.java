package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@Slf4j
@RequiredArgsConstructor
public class CallbackController {
    private final CallbackVerifier verifier;
    private final GiftClassifier classifier;
    private final GiftService gifts;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final LiveGiftRooms rooms;
    private final CacheStateStore states;

    @PostMapping(value="/callbacks/douyin/{alias}",consumes="application/json")
    public Map<String,Object> receive(@PathVariable String alias,HttpServletRequest request) throws IOException {
        LeaderboardProperties.App app=verifier.app(alias);
        int max=1024*1024;
        if(request.getContentLengthLong()>max)throw new RankException(413,41301,"回调正文过大");
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int read;
        while((read=request.getInputStream().read(buffer))!=-1) {
            if(out.size()+read>max)throw new RankException(413,41301,"回调正文过大");out.write(buffer,0,read);
        }
        byte[] body=out.toByteArray();String room=verifier.verify(app,request,body);
        JsonNode batch=mapper.readTree(body);
        if(batch==null || !batch.isArray() || batch.size()>1000)throw RankException.badRequest("回调应为不超过1000条的消息数组");
        states.validateEnvironment();
        int booked=0,filtered=0,pending=0,duplicates=0;
        long receivedAt=clock.millis();
        rooms.observe(alias,app.getAppId(),room,receivedAt);
        for(JsonNode node:batch) {
            GiftService.Result result=gifts.receive(classifier.classify(app,room,node,receivedAt));
            if(result.isDuplicate())duplicates++;
            else if("BOOKED".equals(result.getStatus()))booked++;
            else if("FILTERED".equals(result.getStatus()))filtered++;
            else pending++;
        }
        Map<String,Object> result=new LinkedHashMap<>();
        if(pending>0)log.warn("礼物批次保管待核对：appId={}, roomId={}, count={}",app.getAppId(),room,pending);
        result.put("booked",booked);result.put("filtered",filtered);result.put("pendingReview",pending);result.put("duplicates",duplicates);
        return result;
    }
}
