package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;

class LocalLeaderboardApplicationTest {
    private MockEnvironment local(){
        MockEnvironment env=new MockEnvironment().withProperty("server.address","127.0.0.1").withProperty("server.port","18000")
                .withProperty("spring.datasource.url","jdbc:mysql://127.0.0.1:3306/leaderboard?serverTimezone=UTC")
                .withProperty("spring.sql.init.mode","never").withProperty("spring.redis.host","127.0.0.1").withProperty("spring.redis.port","16379")
                .withProperty("leaderboard.environment","local-rehearsal").withProperty("leaderboard.worker-enabled","true")
                .withProperty("leaderboard.internal-token","only-local-rehearsal-token-32-characters");
        int i=0;for(String game:LegacySnapshotConverter.GAMES)env.withProperty("leaderboard.apps.g"+(i++)+".game",game);return env;
    }
    @Test void acceptsOnlyIsolatedLocalConfiguration(){assertDoesNotThrow(()->LocalLeaderboardApplication.validate(local()));assertThrows(IllegalStateException.class,()->LocalLeaderboardApplication.validate(local().withProperty("spring.datasource.url","jdbc:mysql://remote:3306/prod?serverTimezone=UTC")));assertThrows(IllegalStateException.class,()->LocalLeaderboardApplication.validate(local().withProperty("REDIS_URL","redis://remote:6379")));assertThrows(IllegalStateException.class,()->LocalLeaderboardApplication.validate(local().withProperty("leaderboard.apps.g0.enabled","true")));}
    @Test void simulatedClockUsesAFutureRealBoundary(){long before=System.currentTimeMillis();Clock clock=new LocalLeaderboardApplication.Rehearsal().rehearsalClock();assertTrue(clock.millis()>before);assertEquals(clock.millis(),Periods.cycle(clock.millis()));assertEquals(clock.millis(),clock.millis());}
    @Test void rehearsalYamlPassesTheSameStartupGuard()throws Exception{MockEnvironment env=new MockEnvironment().withProperty("LOCAL_LEADERBOARD_TOKEN","only-local-rehearsal-token-32-characters");new YamlPropertySourceLoader().load("rehearsal",new ClassPathResource("application-local-rehearsal.yml")).forEach(source->env.getPropertySources().addLast(source));assertDoesNotThrow(()->LocalLeaderboardApplication.validate(env));}
}
