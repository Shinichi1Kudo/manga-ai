package com.manga.ai.gptimage.service;

import com.manga.ai.gptimage.dto.GptImage2GenerateRequest;
import com.manga.ai.gptimage.dto.GptImage2GenerateResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * GPT-Image2 图片生成服务
 */
public interface GptImage2Service {

    GptImage2GenerateResponse generate(GptImage2GenerateRequest request);

    GptImage2GenerateResponse getTask(Long taskId);

    List<GptImage2GenerateResponse> listMyTasks(Integer limit);

    GptImage2GenerateResponse getLatestTask();

    String uploadReference(MultipartFile file);

    int failStaleRunningTasks();
}
