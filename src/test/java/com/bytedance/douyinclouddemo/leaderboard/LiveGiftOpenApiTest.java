package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.junit.jupiter.api.Assertions.*;

class LiveGiftOpenApiTest {
    private final RestTemplate http=new RestTemplate();
    private final MockRestServiceServer server=MockRestServiceServer.bindTo(http).build();
    private final LeaderboardProperties properties=new LeaderboardProperties();
    private final LiveGiftOpenApi api=new LiveGiftOpenApi(http,properties);
    @BeforeEach void enable(){properties.setCloudOpenapiEnabled(true);}
    @AfterEach void verify(){server.verify();}
    @Test void startsOnlyGiftTaskWithServerOwnedApp(){server.expect(requestTo("http://webcast-bytedance-com.openapi.dyc.ivolces.com/api/live_data/task/start")).andExpect(method(HttpMethod.POST)).andExpect(content().json("{\"appid\":\"app-A\",\"roomid\":\"room1\",\"msg_type\":\"live_gift\"}")).andRespond(withSuccess("{\"err_no\":0}",MediaType.APPLICATION_JSON));api.task(Fixtures.app(),"room1","start");}
    @Test void failurePagingUsesOfficialQueryParameters(){server.expect(requestTo("http://webcast-bytedance-com.openapi.dyc.ivolces.com/api/live_data/task/fail_data/get?appid=app-A&roomid=room1&msg_type=live_gift&page_num=2&page_size=100")).andExpect(method(HttpMethod.GET)).andRespond(withSuccess("{\"err_no\":0,\"data\":{\"page_num\":2,\"total_count\":0,\"data_list\":[]}}",MediaType.APPLICATION_JSON));assertEquals(2,api.failurePage(new LiveGiftRooms.Room("app-A","room1","main",2)).path("page_num").intValue());}
    @Test void nonzeroBusinessCodeDoesNotMasqueradeAsSuccess(){server.expect(anything()).andRespond(withSuccess("{\"err_no\":40022,\"err_msg\":\"error\"}",MediaType.APPLICATION_JSON));assertThrows(RankException.class,()->api.task(Fixtures.app(),"room1","start"));}
    @Test void disabledCloudModeMakesNoNetworkCall(){properties.setCloudOpenapiEnabled(false);assertThrows(RankException.class,()->api.task(Fixtures.app(),"room1","start"));}
}
