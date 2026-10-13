package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CallbackControllerTest {
    private final GiftService gifts=mock(GiftService.class);
    private final LiveGiftRooms rooms=mock(LiveGiftRooms.class);
    private final LeaderboardProperties.App app=Fixtures.app();
    private MockMvc mvc;
    @BeforeEach void setUp(){LeaderboardProperties props=new LeaderboardProperties();props.getApps().put("main",app);Fixtures.MutableClock clock=new Fixtures.MutableClock();mvc=MockMvcBuilders.standaloneSetup(new CallbackController(new CallbackVerifier(props,clock),new GiftClassifier(Fixtures.mapper()),gifts,Fixtures.mapper(),clock,rooms,mock(CacheStateStore.class))).setControllerAdvice(new RankErrorHandler()).build();}
    private MockHttpServletRequestBuilder signed(String body){byte[] bytes=body.getBytes(StandardCharsets.UTF_8);MockHttpServletRequestBuilder request=post("/callbacks/douyin/main").contentType("application/json").content(bytes);java.util.Map<String,String> headers=CallbackVerifierTest.headers(Fixtures.NOW);headers.forEach(request::header);request.header("x-signature",Base64.getEncoder().encodeToString(CallbackVerifier.signature(headers,bytes,app.getSecret())));return request;}
    @Test void forgedClientCannotReachLedger()throws Exception{mvc.perform(post("/callbacks/douyin/main").contentType("application/json").header("x-tt-source","claimed-official").content("[]")).andExpect(status().isForbidden());verifyNoInteractions(gifts,rooms);}
    @Test void eachCommittedMessageIsAcknowledged()throws Exception{when(gifts.receive(any())).thenReturn(new GiftService.Result(1,"BOOKED",false,30L));mvc.perform(signed("["+Fixtures.message("m1",Fixtures.NOW)+"]")).andExpect(status().isOk()).andExpect(jsonPath("$.booked").value(1));verify(rooms).observe("main","app-A","room1",Fixtures.NOW);}
    @Test void partialBatchFailureIsNonSuccessHttp()throws Exception{when(gifts.receive(any())).thenReturn(new GiftService.Result(1,"BOOKED",false,30L)).thenThrow(RankException.unavailable());mvc.perform(signed("["+Fixtures.message("m1",Fixtures.NOW)+","+Fixtures.message("m2",Fixtures.NOW)+","+Fixtures.message("m3",Fixtures.NOW)+"]")).andExpect(status().isServiceUnavailable());verify(gifts,times(2)).receive(any());}
    @Test void signedMalformedJsonIsNotAcknowledged()throws Exception{mvc.perform(signed("[ broken")).andExpect(status().isBadRequest());verifyNoInteractions(gifts,rooms);}
    @Test void signedPendingReviewIsKeptWithoutClaimingScore()throws Exception{when(gifts.receive(any())).thenReturn(new GiftService.Result(1,"PENDING_REVIEW",false,null));mvc.perform(signed("["+Fixtures.message("m1",Fixtures.NOW)+"]")).andExpect(status().isOk()).andExpect(jsonPath("$.pendingReview").value(1)).andExpect(jsonPath("$.booked").value(0));}
    @Test void formerArbitraryScoreAndDemoEndpointsAreGone()throws Exception{mvc.perform(post("/api/v1/game/add_kill_log2").contentType("application/json").content("{\"kill_num\":1000000}")).andExpect(status().isNotFound());mvc.perform(get("/api/hello")).andExpect(status().isNotFound());}
}
