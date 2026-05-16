package com.manga.ai.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncConfigTest {

    @Test
    void videoExecutorUsesGlobalHundredWorkersAndThousandQueueDefaults() {
        AsyncConfig config = new AsyncConfig();

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.videoGenerateExecutor();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(100);
            assertThat(executor.getMaxPoolSize()).isEqualTo(100);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(1000);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void videoExecutorRejectsInsteadOfRunningOverflowOnRequestThread() throws Exception {
        AsyncConfig config = new AsyncConfig();
        ReflectionTestUtils.setField(config, "videoCorePoolSize", 1);
        ReflectionTestUtils.setField(config, "videoMaxPoolSize", 1);
        ReflectionTestUtils.setField(config, "videoQueueCapacity", 0);

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.videoGenerateExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> executor.execute(() -> {}))
                    .isInstanceOf(RejectedExecutionException.class)
                    .rootCause()
                    .hasMessageContaining("生成人数过多");
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
