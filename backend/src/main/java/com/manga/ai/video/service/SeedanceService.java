package com.manga.ai.video.service;

import com.manga.ai.video.dto.SeedanceRequest;
import com.manga.ai.video.dto.SeedanceResponse;

/**
 * Seedance视频生成服务接口
 */
public interface SeedanceService {

    /**
     * 提交视频生成任务
     * @param request 生成请求
     * @return 任务响应（包含taskId）
     */
    SeedanceResponse submitVideoGeneration(SeedanceRequest request);

    /**
     * 查询任务状态
     * @param taskId 任务ID
     * @return 任务状态
     */
    SeedanceResponse queryTaskStatus(String taskId);

    /**
     * 生成视频（同步等待完成）
     * @param request 生成请求
     * @return 生成结果
     */
    SeedanceResponse generateVideo(SeedanceRequest request);

    /**
     * 构建分镜视频提示词
     * @param shotId 分镜ID
     * @return 提示词
     */
    String buildShotPrompt(Long shotId);
}
