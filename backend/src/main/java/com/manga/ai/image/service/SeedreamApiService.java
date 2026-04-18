package com.manga.ai.image.service;

import com.manga.ai.image.dto.SeedreamGenerateRequest;
import com.manga.ai.image.dto.SeedreamGenerateResponse;

import java.util.List;

/**
 * Seedream API 服务接口
 */
public interface SeedreamApiService {

    /**
     * 生成图像
     */
    SeedreamGenerateResponse generateImage(SeedreamGenerateRequest request);

    /**
     * 批量生成图像
     */
    List<SeedreamGenerateResponse> batchGenerate(List<SeedreamGenerateRequest> requests);

    /**
     * 查询任务状态
     */
    SeedreamGenerateResponse getTaskStatus(String taskId);
}
