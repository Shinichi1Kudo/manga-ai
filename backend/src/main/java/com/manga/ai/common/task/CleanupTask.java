package com.manga.ai.common.task;

import com.manga.ai.series.mapper.SeriesMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 定时清理任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CleanupTask {

    private final SeriesMapper seriesMapper;

    /**
     * 每天凌晨3点清理过期的回收站系列
     * 删除超过3天的已删除系列
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupExpiredSeries() {
        log.info("开始清理过期的回收站系列...");
        try {
            LocalDateTime expireTime = LocalDateTime.now().minusDays(3);
            seriesMapper.deleteExpiredSeries(expireTime);
            log.info("回收站清理完成");
        } catch (Exception e) {
            log.error("回收站清理失败", e);
        }
    }
}
