package com.bytedance.douyinclouddemo.leaderboard;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import java.util.HashSet;
import java.util.Set;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(LeaderboardProperties.class)
@RequiredArgsConstructor
public class LeaderboardConfiguration {
    private final LeaderboardProperties properties;
    @PostConstruct
    public void validate() {
        if(!properties.getEnvironment().matches("[A-Za-z0-9_-]{1,32}") || properties.getBatchSize()<1 || properties.getBatchSize()>1000
                || properties.getMaxBoards()<2 || properties.getMaxMembers()<1 || properties.getSnapshotTimeoutSeconds()<1 || properties.getSnapshotTimeoutSeconds()>120) {
            throw new IllegalStateException("排行榜环境或后台容量参数非法");
        }
        for(LeaderboardProperties.WarmupScope scope:properties.getWarmupScopes())scope.toRankScope();
        Set<String> apps=new HashSet<>(),secrets=new HashSet<>();
        if(properties.isCloudOpenapiEnabled() && properties.getInternalToken().length()<32)throw new IllegalStateException("云端任务管理须配置不少于32字符的内部访问令牌");
        for(LeaderboardProperties.App app:properties.getApps().values()) {
            if(!app.isEnabled())continue;
            ScoreRules.identifier(app.getAppId(),64,false);ScoreRules.identifier(app.getGame(),128,false);
            ScoreRules.identifier(app.getPlatform(),128,false);ScoreRules.identifier(app.getFaction(),128,false);ScoreRules.identifier(app.getZhubo(),128,true);
            if(!apps.add(app.getAppId()))throw new IllegalStateException("启用的应用来源重复");
            if(app.getMode()==LeaderboardProperties.Mode.SIGNED) {
                if(app.getSecret()==null||app.getSecret().length()<16||!secrets.add(app.getSecret()))throw new IllegalStateException("每个应用须配置独立且不少于16字符的回调密钥");
            } else if(!app.isPrivateIngressConfirmed()||app.getSource()==null||app.getSource().isEmpty()) {
                throw new IllegalStateException("抖音云回调必须先确认专用路径访问限制和来源标识");
            }
        }
    }
    @Bean
    public Clock leaderboardClock() { return Clock.systemUTC(); }
    @Bean
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);scheduler.setThreadNamePrefix("leaderboard-schedule-");return scheduler;
    }
    @Bean
    public org.springframework.web.client.RestTemplate leaderboardOpenApiClient() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);factory.setReadTimeout(2000);return new org.springframework.web.client.RestTemplate(factory);
    }
}
