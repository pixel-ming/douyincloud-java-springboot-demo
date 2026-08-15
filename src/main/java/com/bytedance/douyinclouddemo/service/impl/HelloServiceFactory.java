package com.bytedance.douyinclouddemo.service.impl;

import com.bytedance.douyinclouddemo.service.HelloService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class HelloServiceFactory {
    @Qualifier("redis")
    @Autowired
    private HelloService redisImpl;
    public HelloService getHelloService(String target){
        return redisImpl;
    }
}
