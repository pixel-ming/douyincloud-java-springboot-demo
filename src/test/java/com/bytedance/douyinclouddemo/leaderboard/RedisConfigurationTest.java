package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import static org.junit.jupiter.api.Assertions.*;

class RedisConfigurationTest {
    @Test void keepsCloudAddressAndSeparatePasswordWithoutConnecting(){RedisProperties props=new RedisProperties();props.setPassword("test-password@with-reserved-char");LettuceConnectionFactory factory=new RedisConfiguration().redisConnectionFactory(props,new MockEnvironment().withProperty("REDIS_ADDRESS","127.0.0.1:16379"));assertEquals("127.0.0.1",factory.getStandaloneConfiguration().getHostName());assertEquals(16379,factory.getStandaloneConfiguration().getPort());assertEquals(props.getPassword(),new String(factory.getStandaloneConfiguration().getPassword().get()));}
    @Test void explicitHostOverridesInjectedCloudAddress(){RedisProperties props=new RedisProperties();props.setHost("manual-host");props.setPort(6380);LettuceConnectionFactory factory=new RedisConfiguration().redisConnectionFactory(props,new MockEnvironment().withProperty("REDIS_HOST","manual-host").withProperty("REDIS_ADDRESS","cloud-host:6379"));assertEquals("manual-host",factory.getStandaloneConfiguration().getHostName());assertEquals(6380,factory.getStandaloneConfiguration().getPort());}
    @Test void fullUrlSupportsTlsAndDatabase(){LettuceConnectionFactory factory=new RedisConfiguration().redisConnectionFactory(new RedisProperties(),new MockEnvironment().withProperty("REDIS_URL","rediss://user:test-password@localhost:6380/2"));assertEquals(2,factory.getStandaloneConfiguration().getDatabase());assertEquals("user",factory.getStandaloneConfiguration().getUsername());assertTrue(factory.getClientConfiguration().isUseSsl());}
}
