package com.manga.ai.gptimage.service;

import com.manga.ai.gptimage.dto.GptImage2GenerateRequest;
import com.manga.ai.gptimage.dto.GptImage2GenerateResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * GPT-Image2 图片生成服务
 */
public interface GptImage2Service {

    GptImage2GenerateResponse generate(GptImage2GenerateRequest request);

    String uploadReference(MultipartFile file);
}
