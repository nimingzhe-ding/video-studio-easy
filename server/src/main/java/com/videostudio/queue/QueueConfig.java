package com.videostudio.queue;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QueueConfig {
    @Bean(destroyMethod="shutdown")
    RedissonClient redisson(@Value("${spring.data.redis.host}") String host,
                            @Value("${spring.data.redis.port}") int port,
                            @Value("${spring.data.redis.password}") String password) {
        var config=new Config();
        var server=config.useSingleServer().setAddress("redis://"+host+":"+port)
            .setConnectionMinimumIdleSize(2).setConnectionPoolSize(16).setTimeout(3000);
        if(!password.isBlank()) server.setPassword(password);
        config.setLockWatchdogTimeout(60000);
        return Redisson.create(config);
    }
}
