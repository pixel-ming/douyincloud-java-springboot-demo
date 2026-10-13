package com.bytedance.douyinclouddemo.leaderboard;

import io.lettuce.core.RedisURI;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.StringUtils;
import java.time.Duration;

@Configuration
public class RedisConfiguration {
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(RedisProperties properties,Environment environment) {
        RedisStandaloneConfiguration standalone=new RedisStandaloneConfiguration(properties.getHost(),properties.getPort());
        standalone.setDatabase(properties.getDatabase());
        if(StringUtils.hasText(properties.getUsername()))standalone.setUsername(properties.getUsername());
        if(StringUtils.hasText(properties.getPassword()))standalone.setPassword(properties.getPassword());
        boolean ssl=properties.isSsl();
        String url=environment.getProperty("REDIS_URL",properties.getUrl());
        String address=environment.getProperty("REDIS_ADDRESS");
        if(StringUtils.hasText(url)) {
            RedisURI uri=RedisURI.create(url);standalone.setHostName(uri.getHost());standalone.setPort(uri.getPort());standalone.setDatabase(uri.getDatabase());
            if(StringUtils.hasText(uri.getUsername()))standalone.setUsername(uri.getUsername());
            if(uri.getPassword()!=null)standalone.setPassword(new String(uri.getPassword()));ssl=uri.isSsl();
        } else if(StringUtils.hasText(address) && !StringUtils.hasText(environment.getProperty("REDIS_HOST"))) {
            // 保留抖音云模板的地址注入约定；凭据独立设置，不拼接到 URI 中。
            RedisURI uri=RedisURI.create(address.contains("://")?address:"redis://"+address);
            standalone.setHostName(uri.getHost());standalone.setPort(uri.getPort());ssl=uri.isSsl();
        }
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client=LettuceClientConfiguration.builder().commandTimeout(properties.getTimeout()==null?Duration.ofSeconds(1):properties.getTimeout());
        if(ssl)client.useSsl();return new LettuceConnectionFactory(standalone,client.build());
    }
}
