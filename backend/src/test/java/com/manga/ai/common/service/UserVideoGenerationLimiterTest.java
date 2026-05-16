package com.manga.ai.common.service;

import com.manga.ai.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserVideoGenerationLimiterTest {

    @Test
    void allowsConfiguredNumberOfConcurrentVideoGenerationsPerUser() {
        UserVideoGenerationLimiter limiter = new UserVideoGenerationLimiter();
        ReflectionTestUtils.setField(limiter, "perUserLimit", 3);

        limiter.acquireOrThrow(7L, "shot:1");
        limiter.acquireOrThrow(7L, "shot:2");
        limiter.acquireOrThrow(7L, "subject:3");

        assertThat(limiter.getActiveCount(7L)).isEqualTo(3);
        assertThatThrownBy(() -> limiter.acquireOrThrow(7L, "shot:4"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("3路");
    }

    @Test
    void releaseFreesSlotAndIgnoresDuplicateReleaseForSameTask() {
        UserVideoGenerationLimiter limiter = new UserVideoGenerationLimiter();
        ReflectionTestUtils.setField(limiter, "perUserLimit", 1);

        limiter.acquireOrThrow(7L, "shot:1");
        limiter.release(7L, "shot:1");
        limiter.release(7L, "shot:1");

        limiter.acquireOrThrow(7L, "shot:2");
        assertThat(limiter.getActiveCount(7L)).isEqualTo(1);
    }

    @Test
    void limitsUsersIndependently() {
        UserVideoGenerationLimiter limiter = new UserVideoGenerationLimiter();
        ReflectionTestUtils.setField(limiter, "perUserLimit", 1);

        limiter.acquireOrThrow(7L, "shot:1");
        limiter.acquireOrThrow(8L, "shot:2");

        assertThat(limiter.getActiveCount(7L)).isEqualTo(1);
        assertThat(limiter.getActiveCount(8L)).isEqualTo(1);
    }

    @Test
    void defaultsToFifteenConcurrentVideoGenerationsPerUser() {
        UserVideoGenerationLimiter limiter = new UserVideoGenerationLimiter();

        for (int i = 0; i < 15; i++) {
            limiter.acquireOrThrow(7L, "shot:" + i);
        }

        assertThatThrownBy(() -> limiter.acquireOrThrow(7L, "shot:overflow"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("15路");
    }
}
