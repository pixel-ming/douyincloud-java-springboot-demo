package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.*;
import java.util.Collections;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class FailureReplayWorkerTest {
    private final LiveGiftRooms rooms=mock(LiveGiftRooms.class);
    private final GiftService gifts=mock(GiftService.class);
    private final LiveGiftRooms.Room room=new LiveGiftRooms.Room("app-A","room1","main",1);
    private final CacheStateStore states=mock(CacheStateStore.class);
    private final CacheStateStore.Lease lease=new CacheStateStore.Lease("owner",1,0,0,Fixtures.NOW+30000);
    private final FailureReplayWorker worker=new FailureReplayWorker(new LeaderboardProperties(),states,mock(CacheWorker.class),rooms,mock(LiveGiftOpenApi.class),new GiftClassifier(Fixtures.mapper()),gifts,Fixtures.mapper(),new Fixtures.MutableClock());
    @BeforeEach void prepare(){when(states.confirmReplay(any(),any())).thenAnswer(call->{((Runnable)call.getArgument(1)).run();return true;});when(gifts.receive(any())).thenReturn(new GiftService.Result(1,"BOOKED",false,30L));}
    private com.fasterxml.jackson.databind.JsonNode page()throws Exception{
        String payload="["+Fixtures.message("m1",Fixtures.NOW)+","+Fixtures.message("m2",Fixtures.NOW)+"]";
        java.util.Map<String,Object> record=new java.util.HashMap<>();record.put("roomid","room1");record.put("msg_type","live_gift");record.put("payload",payload);
        java.util.Map<String,Object> page=new java.util.HashMap<>();page.put("page_num",1);page.put("total_count",101);page.put("data_list",Collections.singletonList(record));return Fixtures.mapper().valueToTree(page);
    }
    @Test void countsAllMessagesInsideEachPayloadThenAdvancesPage()throws Exception{worker.replay(room,Fixtures.app(),page(),lease);verify(gifts,times(2)).receive(any());verify(rooms).finish(room,2,110);}
    @Test void failedMessageNeverAdvancesPage()throws Exception{when(gifts.receive(any())).thenReturn(new GiftService.Result(1,"BOOKED",false,30L)).thenThrow(RankException.unavailable());assertThrows(RankException.class,()->worker.replay(room,Fixtures.app(),page(),lease));verify(rooms,never()).finish(any(),anyInt(),anyLong());}
    @Test void foreignRoomIsNotTrustedAsAReplayResult()throws Exception{com.fasterxml.jackson.databind.JsonNode page=page();((com.fasterxml.jackson.databind.node.ObjectNode)page.get("data_list").get(0)).put("roomid","other-room");assertThrows(RankException.class,()->worker.replay(room,Fixtures.app(),page,lease));verifyNoInteractions(gifts,rooms);}
    @Test void lostLeaseCannotConfirmReplayProgress()throws Exception{doReturn(false).when(states).confirmReplay(any(),any());assertThrows(RankException.class,()->worker.replay(room,Fixtures.app(),page(),lease));verify(rooms,never()).finish(any(),anyInt(),anyLong());}
}
