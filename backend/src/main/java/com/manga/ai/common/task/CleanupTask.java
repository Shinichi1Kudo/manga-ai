package com.manga.ai.common.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.manga.ai.common.enums.EpisodeStatus;
import com.manga.ai.episode.entity.Episode;
import com.manga.ai.episode.mapper.EpisodeMapper;
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
    private final EpisodeMapper episodeMapper;

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

    /**
     * 每30秒检查一次卡住的解析任务
     * 如果解析中状态超过5分钟，自动变为待审核
     */
    @Scheduled(fixedRate = 30000)
    public void cleanupStuckParsingEpisodes() {
        try {
            LocalDateTime timeout = LocalDateTime.now().minusMinutes(5);
            LambdaUpdateWrapper<Episode> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(Episode::getStatus, EpisodeStatus.PARSING.getCode())
                    .lt(Episode::getUpdatedAt, timeout)
                    .set(Episode::getStatus, EpisodeStatus.PENDING_REVIEW.getCode())
                    .set(Episode::getUpdatedAt, LocalDateTime.now());

            int updated = episodeMapper.update(null, updateWrapper);
            if (updated > 0) {
                log.info("已自动恢复 {} 个卡住的解析任务为待审核状态", updated);
            }
        } catch (Exception e) {
            log.error("清理卡住的解析任务失败", e);
        }
    }
}
