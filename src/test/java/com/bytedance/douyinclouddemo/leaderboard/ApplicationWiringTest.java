package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"leaderboard.worker-enabled=false","leaderboard.cloud-openapi-enabled=false"})
class ApplicationWiringTest {
    @MockBean DataSource dataSource;
    @Autowired RequestMappingHandlerMapping mappings;
    @Autowired org.springframework.context.ApplicationContext context;
    @Test void contextLoadsNewEndpointsWithoutConnectingDatabase(){String routes=mappings.getHandlerMethods().keySet().toString();assertTrue(routes.contains("/api/v1/game/get_kill_rank2"));assertTrue(routes.contains("/callbacks/douyin/{alias}"));assertFalse(routes.contains("/api/hello"));assertFalse(routes.contains("/api/set_name"));assertFalse(context.containsBean("rehearsalClock"));assertFalse(context.containsBean("rehearsalMetadata"));}
}
