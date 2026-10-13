package com.bytedance.douyinclouddemo.leaderboard;

import com.bytedance.douyinclouddemo.DouyincloudDemoApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.function.*;
import java.time.*;
import java.util.*;

/** 仅在测试 classpath 启动，生产 JAR 不包含演练入口或测试时钟。 */
public final class LocalLeaderboardApplication {
    public static void main(String[] args){SpringApplication app=new SpringApplication(DouyincloudDemoApplication.class,Rehearsal.class);app.addInitializers(context->validate(context.getEnvironment()));app.run(args);}
    static void validate(Environment env){
        require("127.0.0.1".equals(env.getProperty("server.address"))&&"18000".equals(env.getProperty("server.port")));
        require(env.getProperty("spring.datasource.url","").matches("jdbc:mysql://127\\.0\\.0\\.1:3306/leaderboard\\?[^\\s]*"));
        require("never".equals(env.getProperty("spring.sql.init.mode")));
        require("127.0.0.1".equals(env.getProperty("spring.redis.host"))&&"16379".equals(env.getProperty("spring.redis.port")));
        for(String key:Arrays.asList("REDIS_URL","REDIS_ADDRESS","spring.redis.url","spring.redis.cluster.nodes","spring.redis.sentinel.master"))require(env.getProperty(key,"").isEmpty());
        LeaderboardProperties props=Binder.get(env).bind("leaderboard",LeaderboardProperties.class).orElseThrow(()->new IllegalStateException("缺少本机演练配置"));
        require("local-rehearsal".equals(props.getEnvironment())&&!props.isCloudOpenapiEnabled()&&props.isWorkerEnabled()&&props.getInternalToken().length()>=32);
        Set<String> games=new HashSet<>();for(LeaderboardProperties.App source:props.getApps().values()){require(!source.isEnabled());if(!source.getGame().isEmpty())games.add(source.getGame());}
        require(games.equals(new HashSet<>(LegacySnapshotConverter.GAMES)));
    }
    private static void require(boolean condition){if(!condition)throw new IllegalStateException("演练入口仅允许固定本机地址、独立测试库及关闭的礼物来源");}
    @TestConfiguration
    public static class Rehearsal {
        @Bean @Primary public Clock rehearsalClock(){return Clock.fixed(Instant.ofEpochMilli(Periods.end(1,Periods.cycle(System.currentTimeMillis()))),Periods.ZONE);}
        @Bean public RouterFunction<ServerResponse> rehearsalMetadata(Clock clock){
            return RouterFunctions.route(RequestPredicates.GET("/internal/leaderboard/rehearsal"),request->{
                Map<String,Object> info=new LinkedHashMap<>();info.put("mode","LOCAL_REHEARSAL");info.put("environment","local-rehearsal");info.put("cutoverAt",clock.millis());info.put("dayStart",Periods.day(clock.millis()));info.put("cycleStart",Periods.cycle(clock.millis()));info.put("games",LegacySnapshotConverter.GAMES);
                return ServerResponse.ok().body(info);
            });
        }
    }
}
