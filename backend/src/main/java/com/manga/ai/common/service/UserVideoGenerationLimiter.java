package com.manga.ai.common.service;

import com.manga.ai.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 按用户限制视频生成任务的接受数量。
 * 线程池仍然是全局资源，这里负责避免单个用户一次性提交过多等待外部模型的任务。
 */
@Slf4j
@Service
public class UserVideoGenerationLimiter {

    @Value("${video.generation.user-concurrency-limit:15}")
    private int perUserLimit = 15;

    @Value("${video.generation.limiter-task-ttl:3600000}")
    private long taskTtlMs = 3600000L;

    private final Map<Long, Integer> activeCountByUser = new HashMap<>();
    private final Map<String, ActiveTask> activeTasks = new HashMap<>();

    public synchronized void acquireOrThrow(Long userId, String taskKey) {
        if (userId == null || taskKey == null || taskKey.isBlank()) {
            throw new BusinessException("视频生成任务参数异常，请重新提交");
        }
        cleanupExpiredTasks();
        ActiveTask existingTask = activeTasks.get(taskKey);
        if (existingTask != null && userId.equals(existingTask.userId())) {
            return;
        }

        int activeCount = activeCountByUser.getOrDefault(userId, 0);
        int safeLimit = Math.max(1, perUserLimit);
        if (activeCount >= safeLimit) {
            throw new BusinessException("当前账号同时生成视频任务已达" + safeLimit + "路，请等待已有任务完成后再试");
        }

        activeTasks.put(taskKey, new ActiveTask(userId, Instant.now()));
        activeCountByUser.put(userId, activeCount + 1);
        log.debug("用户视频生成并发占用: userId={}, taskKey={}, active={}/{}", userId, taskKey, activeCount + 1, safeLimit);
    }

    public synchronized void release(Long userId, String taskKey) {
        if (userId == null || taskKey == null || taskKey.isBlank()) {
            return;
        }
        ActiveTask removed = activeTasks.remove(taskKey);
        if (removed == null || !userId.equals(removed.userId())) {
            return;
        }
        decrementUserCount(userId);
        log.debug("用户视频生成并发释放: userId={}, taskKey={}, active={}", userId, taskKey, getActiveCount(userId));
    }

    public synchronized int getActiveCount(Long userId) {
        if (userId == null) {
            return 0;
        }
        cleanupExpiredTasks();
        return activeCountByUser.getOrDefault(userId, 0);
    }

    private void cleanupExpiredTasks() {
        long safeTtlMs = Math.max(Duration.ofMinutes(30).toMillis(), taskTtlMs);
        Instant expiredBefore = Instant.now().minusMillis(safeTtlMs);
        Iterator<Map.Entry<String, ActiveTask>> iterator = activeTasks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ActiveTask> entry = iterator.next();
            ActiveTask task = entry.getValue();
            if (task.acquiredAt().isBefore(expiredBefore)) {
                iterator.remove();
                decrementUserCount(task.userId());
                log.warn("自动释放超时的视频生成并发占用: userId={}, taskKey={}", task.userId(), entry.getKey());
            }
        }
    }

    private void decrementUserCount(Long userId) {
        Integer activeCount = activeCountByUser.get(userId);
        if (activeCount == null || activeCount <= 1) {
            activeCountByUser.remove(userId);
            return;
        }
        activeCountByUser.put(userId, activeCount - 1);
    }

    private record ActiveTask(Long userId, Instant acquiredAt) {
    }
}
