package com.manga.ai.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Value("${task.executor.core-pool-size:5}")
    private int corePoolSize;

    @Value("${task.executor.max-pool-size:10}")
    private int maxPoolSize;

    @Value("${task.executor.queue-capacity:100}")
    private int queueCapacity;

    @Value("${task.executor.thread-name-prefix:manga-ai-}")
    private String threadNamePrefix;

    @Value("${video.executor.core-pool-size:100}")
    private int videoCorePoolSize = 100;

    @Value("${video.executor.max-pool-size:100}")
    private int videoMaxPoolSize = 100;

    @Value("${video.executor.queue-capacity:1000}")
    private int videoQueueCapacity = 1000;

    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    @Bean("imageGenerateExecutor")
    public Executor imageGenerateExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("image-gen-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean("videoGenerateExecutor")
    public Executor videoGenerateExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(videoCorePoolSize);
        executor.setMaxPoolSize(videoMaxPoolSize);
        executor.setQueueCapacity(videoQueueCapacity);
        executor.setThreadNamePrefix("video-gen-");
        executor.setRejectedExecutionHandler((r, e) -> {
            throw new RejectedExecutionException("生成人数过多请稍后再试");
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }

    @Bean("llmExecutor")
    public Executor llmExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(30);
        executor.setThreadNamePrefix("llm-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
