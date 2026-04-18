package com.manga.ai.image.service.impl;

import com.alibaba.fastjson2.JSON;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.image.dto.SeedreamGenerateRequest;
import com.manga.ai.image.dto.SeedreamGenerateResponse;
import com.manga.ai.image.service.SeedreamApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Seedream API 服务实现
 * 注意: 当前已使用火山方舟 API，此类暂时禁用
 */
@Slf4j
// @Service  // 已禁用，使用 ImageGenerateServiceImpl 代替
public class SeedreamApiServiceImpl implements SeedreamApiService {

    @Value("${seedream.api.url}")
    private String apiUrl;

    @Value("${seedream.api.key}")
    private String apiKey;

    @Value("${seedream.api.timeout:120000}")
    private int timeout;

    @Value("${seedream.model:seedream-5.0-lite}")
    private String defaultModel;

    private final RestTemplate restTemplate;

    public SeedreamApiServiceImpl() {
        this.restTemplate = new RestTemplate();
    }

    @Override
    public SeedreamGenerateResponse generateImage(SeedreamGenerateRequest request) {
        // 设置默认模型
        if (request.getModelVersion() == null) {
            request.setModelVersion(defaultModel);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        HttpEntity<SeedreamGenerateRequest> entity = new HttpEntity<>(request, headers);

        try {
            log.info("调用 Seedream API: prompt={}", request.getPrompt());

            ResponseEntity<SeedreamGenerateResponse> response = restTemplate.exchange(
                    apiUrl + "/generate",
                    HttpMethod.POST,
                    entity,
                    SeedreamGenerateResponse.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                log.info("Seedream API 调用成功: taskId={}", response.getBody().getTaskId());
                return response.getBody();
            } else {
                throw new BusinessException("图像生成失败: " + response.getStatusCode());
            }
        } catch (RestClientException e) {
            log.error("调用 Seedream API 失败", e);
            throw new BusinessException("图像生成服务异常: " + e.getMessage());
        }
    }

    @Override
    public List<SeedreamGenerateResponse> batchGenerate(List<SeedreamGenerateRequest> requests) {
        List<SeedreamGenerateResponse> results = new ArrayList<>();

        for (SeedreamGenerateRequest request : requests) {
            try {
                SeedreamGenerateResponse response = generateImage(request);
                results.add(response);
            } catch (Exception e) {
                log.error("批量生成失败", e);
                // 创建错误响应
                SeedreamGenerateResponse errorResponse = new SeedreamGenerateResponse();
                errorResponse.setStatus("failed");
                errorResponse.setErrorMessage(e.getMessage());
                results.add(errorResponse);
            }
        }

        return results;
    }

    @Override
    public SeedreamGenerateResponse getTaskStatus(String taskId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<SeedreamGenerateResponse> response = restTemplate.exchange(
                    apiUrl + "/task/" + taskId,
                    HttpMethod.GET,
                    entity,
                    SeedreamGenerateResponse.class
            );

            return response.getBody();
        } catch (RestClientException e) {
            log.error("查询任务状态失败: taskId={}", taskId, e);
            throw new BusinessException("查询任务状态失败: " + e.getMessage());
        }
    }
}
