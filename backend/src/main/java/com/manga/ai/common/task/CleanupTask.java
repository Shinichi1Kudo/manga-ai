package com.manga.ai.common.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.manga.ai.common.enums.EpisodeStatus;
import com.manga.ai.common.enums.RoleStatus;
import com.manga.ai.common.enums.SeriesStatus;
import com.manga.ai.common.enums.ShotGenerationStatus;
import com.manga.ai.episode.entity.Episode;
import com.manga.ai.episode.mapper.EpisodeMapper;
import com.manga.ai.gptimage.service.GptImage2Service;
import com.manga.ai.prop.entity.Prop;
import com.manga.ai.prop.mapper.PropMapper;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.scene.entity.Scene;
import com.manga.ai.scene.mapper.SceneMapper;
import com.manga.ai.series.mapper.SeriesMapper;
import com.manga.ai.shot.mapper.ShotMapper;
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
    private final SceneMapper sceneMapper;
    private final PropMapper propMapper;
    private final GptImage2Service gptImage2Service;
    private final RoleMapper roleMapper;
    private final ShotMapper shotMapper;

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
     * 如果解析中状态超过15分钟且已有分镜，自动变为待审核。
     * 分镜解析失败的剧集需要停留在资产选择重试状态，不能伪装成待审核。
     */
    @Scheduled(fixedRate = 30000)
    public void cleanupStuckParsingEpisodes() {
        try {
            LocalDateTime timeout = LocalDateTime.now().minusMinutes(15);
            LambdaUpdateWrapper<Episode> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(Episode::getStatus, EpisodeStatus.PARSING.getCode())
                    .lt(Episode::getUpdatedAt, timeout)
                    .gt(Episode::getTotalShots, 0)
                    .and(wrapper -> wrapper.isNull(Episode::getParsedScript)
                            .or()
                            .notLike(Episode::getParsedScript, "\"shotParseFailed\":true"))
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

    /**
     * 每分钟检查一次卡在生成中的场景和道具
     * 如果生成中状态超过8分钟，自动逻辑删除
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupStuckGeneratingAssets() {
        LocalDateTime timeout = LocalDateTime.now().minusMinutes(8);
        try {
            // 清理卡住的场景（status=0 生成中，超过8分钟）
            LambdaUpdateWrapper<Scene> sceneWrapper = new LambdaUpdateWrapper<>();
            sceneWrapper.eq(Scene::getStatus, 0)
                    .lt(Scene::getUpdatedAt, timeout)
                    .set(Scene::getIsDeleted, 1);
            int deletedScenes = sceneMapper.update(null, sceneWrapper);
            if (deletedScenes > 0) {
                log.info("已自动删除 {} 个卡在生成中超过8分钟的场景", deletedScenes);
            }
        } catch (Exception e) {
            log.error("清理卡住的场景失败", e);
        }
        try {
            // 清理卡住的道具（status=0 生成中，超过8分钟）
            LambdaUpdateWrapper<Prop> propWrapper = new LambdaUpdateWrapper<>();
            propWrapper.eq(Prop::getStatus, 0)
                    .lt(Prop::getUpdatedAt, timeout)
                    .set(Prop::getIsDeleted, 1);
            int deletedProps = propMapper.update(null, propWrapper);
            if (deletedProps > 0) {
                log.info("已自动删除 {} 个卡在生成中超过8分钟的道具", deletedProps);
            }
        } catch (Exception e) {
            log.error("清理卡住的道具失败", e);
        }
    }

    /**
     * 每分钟检查一次卡在初始化中的系列。
     * 如果初始化超过1小时仍未完成，说明异步角色生成任务已经中断，自动恢复到待审核。
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupStuckInitializingSeries() {
        LocalDateTime timeout = LocalDateTime.now().minusHours(1);
        LocalDateTime now = LocalDateTime.now();
        try {
            int restoredRoles = roleMapper.restoreStuckInitializingRoles(
                    RoleStatus.PENDING_REVIEW.getCode(),
                    RoleStatus.EXTRACTING.getCode(),
                    SeriesStatus.INITIALIZING.getCode(),
                    timeout,
                    now
            );
            if (restoredRoles > 0) {
                log.info("已自动恢复 {} 个卡在生成中的初始化角色为待审核状态", restoredRoles);
            }
        } catch (Exception e) {
            log.error("清理卡住的初始化角色失败", e);
        }

        try {
            int restoredSeries = seriesMapper.restoreStuckInitializingSeries(
                    SeriesStatus.PENDING_REVIEW.getCode(),
                    SeriesStatus.INITIALIZING.getCode(),
                    timeout,
                    now
            );
            if (restoredSeries > 0) {
                log.info("已自动恢复 {} 个卡在初始化超过1小时的系列为待审核状态", restoredSeries);
            }
        } catch (Exception e) {
            log.error("清理卡住的初始化系列失败", e);
        }
    }

    /**
     * 每分钟检查一次卡住的GPT-Image2生图任务
     * 如果任务超过配置的卡住超时时间仍未完成，标记失败并返还积分。
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupStuckGptImage2Tasks() {
        try {
            gptImage2Service.failStaleRunningTasks();
        } catch (Exception e) {
            log.error("清理卡住的GPT-Image2生图任务失败", e);
        }
    }

    /**
     * 每分钟检查一次卡住的分镜视频生成任务。
     * 如果任务超过1小时仍未完成，恢复为待生成，避免页面长期停留在生成中。
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupStuckGeneratingShots() {
        try {
            LocalDateTime timeout = LocalDateTime.now().minusHours(1);
            int restored = shotMapper.restoreStuckGeneratingShots(
                    ShotGenerationStatus.PENDING.getCode(),
                    ShotGenerationStatus.GENERATING.getCode(),
                    timeout,
                    LocalDateTime.now()
            );
            if (restored > 0) {
                log.warn("已自动恢复 {} 个卡住超过1小时的分镜视频生成任务", restored);
            }
        } catch (Exception e) {
            log.error("清理卡住的分镜视频生成任务失败", e);
        }
    }
}
