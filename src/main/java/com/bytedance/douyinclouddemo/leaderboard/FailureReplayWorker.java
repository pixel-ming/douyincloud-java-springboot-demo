package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class FailureReplayWorker {
    private final LeaderboardProperties properties;
    private final CacheStateStore states;
    private final CacheWorker coordinator;
    private final LiveGiftRooms rooms;
    private final LiveGiftOpenApi api;
    private final GiftClassifier classifier;
    private final GiftService gifts;
    private final ObjectMapper mapper;
    private final Clock clock;
    private long errorAfter;
    @Scheduled(fixedDelay=110)
    public void tick() {
        if(!properties.isWorkerEnabled()||!properties.isCloudOpenapiEnabled()||clock.millis()<errorAfter||!coordinator.canCoordinate())return;
        LiveGiftRooms.Room room=null;
        CacheStateStore.Lease lease=null;
        try {
            lease=states.acquire(coordinator.ownerId());if(lease==null)return;
            Optional<LiveGiftRooms.Room> next=rooms.next();if(!next.isPresent()||!states.reserveOpenapi(lease))return;
            room=next.get();LeaderboardProperties.App app=properties.getApps().get(room.getAlias());
            if(app==null||!app.isEnabled()||!room.getAppId().equals(app.getAppId())) {LiveGiftRooms.Room skipped=room;states.confirmReplay(lease,()->rooms.finish(skipped,skipped.getPage(),60000));return;}
            replay(room,app,api.failurePage(room),lease);
        } catch(Exception e) {
            if(room!=null&&lease!=null){LiveGiftRooms.Room failed=room;try{states.confirmReplay(lease,()->rooms.finish(failed,failed.getPage(),5000));}catch(RuntimeException ignored){log.warn("礼物回查进度延期：appId={}",room.getAppId());}}
            errorAfter=clock.millis()+5000;
            log.warn("礼物失败回查失败：exception={}",e.getClass().getSimpleName());
        }
    }
    void replay(LiveGiftRooms.Room room,LeaderboardProperties.App app,JsonNode page,CacheStateStore.Lease lease) throws java.io.IOException {
        if(page==null||!page.path("data_list").isArray()||!page.path("page_num").isIntegralNumber()||page.path("page_num").intValue()!=room.getPage()
                || !page.path("total_count").isIntegralNumber()||!page.path("total_count").canConvertToLong()||page.path("total_count").longValue()<0)throw RankException.badRequest("回查分页结构异常");
        JsonNode list=page.get("data_list");
        if(list.size()>100)throw RankException.badRequest("回查页超过记录上限");
        long now=clock.millis();
        int pending=0;
        for(JsonNode record:list) {
            if(!room.getRoomId().equals(record.path("roomid").asText())||!"live_gift".equals(record.path("msg_type").asText())||!record.path("payload").isTextual())throw RankException.badRequest("回查消息来源不匹配");
            JsonNode messages=mapper.readTree(record.get("payload").textValue());
            if(!messages.isArray())throw RankException.badRequest("回查正文不是消息数组");
            for(JsonNode message:messages) {
                GiftService.Result outcome=gifts.receive(classifier.classify(app,room.getRoomId(),message,now));
                if(outcome!=null&&!outcome.isDuplicate()&&"PENDING_REVIEW".equals(outcome.getStatus()))pending++;
            }
        }
        if(pending>0)log.warn("礼物回查保管待核对：appId={}, roomId={}, count={}",room.getAppId(),room.getRoomId(),pending);
        boolean more=(long)room.getPage()*100<page.path("total_count").longValue() && !list.isEmpty();
        if(more && room.getPage()>=1000)throw new RankException(503,50305,"回查范围超过初始平台窗口");
        if(!states.confirmReplay(lease,()->rooms.finish(room,more?room.getPage()+1:1,more?110:60000)))throw RankException.unavailable();
    }
}
