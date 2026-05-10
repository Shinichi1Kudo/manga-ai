package com.manga.ai.common.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Redis connection tuning.
 */
@Slf4j
@Configuration
@EnableScheduling
public class RedisConfig {

    private final ApplicationContext applicationContext;

    public RedisConfig(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceClientConfigurationBuilderCustomizer() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                        .keepAlive(true)
                        .build())
                .autoReconnect(true)
                .pingBeforeActivateConnection(true)
                .build());
    }

    @Scheduled(fixedDelayString = "${spring.data.redis.keepalive-interval:60000}")
    public void keepRedisConnectionWarm() {
        try {
            StringRedisTemplate redisTemplate = applicationContext.getBean(StringRedisTemplate.class);
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                connection.ping();
                return null;
            });
        } catch (Exception e) {
            log.warn("Redis keepalive ping failed, Lettuce will reconnect on demand: {}", e.getMessage());
        }
    }
}
