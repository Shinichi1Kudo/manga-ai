package com.manga.ai.video.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.manga.ai.common.service.OssService;
import com.manga.ai.role.entity.Role;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.scene.entity.Scene;
import com.manga.ai.scene.mapper.SceneMapper;
import com.manga.ai.shot.entity.Shot;
import com.manga.ai.shot.entity.ShotCharacter;
import com.manga.ai.shot.mapper.ShotCharacterMapper;
import com.manga.ai.shot.mapper.ShotMapper;
import com.manga.ai.video.dto.SeedanceRequest;
import com.manga.ai.video.dto.SeedanceResponse;
import com.manga.ai.video.service.SeedanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Seedance视频生成服务实现
 */
@Slf4j
@Service
public class SeedanceServiceImpl implements SeedanceService {

    @Value("${volcengine.seedance.api-key}")
    private String apiKey;

    @Value("${volcengine.seedance.model:seedance-2.0}")
    private String model;

    @Value("${volcengine.seedance.base-url:https://ark.cn-beijing.volces.com/api/v3}")
    private String baseUrl;

    @Value("${volcengine.seedance.timeout:300000}")
    private int timeout;

    @Value("${volcengine.seedance.poll-interval:5000}")
    private int pollInterval;

    private final RestTemplate restTemplate;
    private final ShotMapper shotMapper;
    private final ShotCharacterMapper shotCharacterMapper;
    private final SceneMapper sceneMapper;
    private final RoleMapper roleMapper;
    private final OssService ossService;

    public SeedanceServiceImpl(ShotMapper shotMapper, ShotCharacterMapper shotCharacterMapper,
                               SceneMapper sceneMapper, RoleMapper roleMapper, OssService ossService) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(60000);
        factory.setReadTimeout(300000);
        this.restTemplate = new RestTemplate(factory);
        this.shotMapper = shotMapper;
        this.shotCharacterMapper = shotCharacterMapper;
        this.sceneMapper = sceneMapper;
        this.roleMapper = roleMapper;
        this.ossService = ossService;
    }

    @Override
    public SeedanceResponse submitVideoGeneration(SeedanceRequest request) {
        log.info("提交视频生成任务: model={}, duration={}s, referenceImages={}",
                model, request.getDuration(),
                request.getContents() != null ? request.getContents().size() : 0);

        try {
            JSONObject requestBody = new JSONObject();

            // 判断是否使用多参考图格式
            boolean useMultiReferenceFormat = request.getContents() != null && !request.getContents().isEmpty();

            if (useMultiReferenceFormat) {
                // 新格式：多参考图 i2v 模型
                requestBody.put("model", "doubao-seedance-1-0-lite-i2v-250428");

                // 构建 contents 数组
                JSONArray contentsArray = new JSONArray();

                // 1. 添加文本 prompt
                JSONObject textContent = new JSONObject();
                textContent.put("type", "text");
                textContent.put("text", request.getPrompt());
                contentsArray.add(textContent);

                // 2. 添加参考图
                for (SeedanceRequest.ReferenceContent content : request.getContents()) {
                    if ("image_url".equals(content.getType()) && content.getImageUrl() != null) {
                        JSONObject imageContent = new JSONObject();
                        imageContent.put("type", "image_url");
                        imageContent.put("role", "reference_image");

                        JSONObject imageUrl = new JSONObject();
                        imageUrl.put("url", content.getImageUrl().getUrl());
                        imageContent.put("image_url", imageUrl);

                        contentsArray.add(imageContent);
                    }
                }

                requestBody.put("contents", contentsArray);
                requestBody.put("duration", request.getDuration());
            } else {
                // 旧格式：单参考图或纯文本
                requestBody.put("model", model);
                requestBody.put("prompt", request.getPrompt());
                requestBody.put("duration", request.getDuration());
                requestBody.put("width", request.getWidth());
                requestBody.put("height", request.getHeight());

                if (request.getReferenceImageUrl() != null && !request.getReferenceImageUrl().isEmpty()) {
                    requestBody.put("image", request.getReferenceImageUrl());
                }
            }

            if (request.getSeed() != null) {
                requestBody.put("seed", request.getSeed());
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);
            String url = baseUrl + "/video/generations";

            log.info("调用Seedance API ({}): {}", useMultiReferenceFormat ? "多参考图" : "单参考图", url);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseSubmitResponse(response.getBody());
            } else {
                SeedanceResponse errorResponse = new SeedanceResponse();
                errorResponse.setStatus("failed");
                errorResponse.setErrorMessage("视频生成任务提交失败: " + response.getStatusCode());
                return errorResponse;
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("Seedance API调用失败: statusCode={}, responseBody={}", e.getStatusCode(), e.getResponseBodyAsString());
            SeedanceResponse errorResponse = new SeedanceResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage(e.getStatusCode() + ": " + e.getResponseBodyAsString());
            return errorResponse;
        } catch (Exception e) {
            log.error("视频生成任务提交异常", e);
            SeedanceResponse errorResponse = new SeedanceResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage(e.getMessage());
            return errorResponse;
        }
    }

    @Override
    public SeedanceResponse queryTaskStatus(String taskId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<String> entity = new HttpEntity<>(headers);
            String url = baseUrl + "/video/generations/" + taskId;

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseQueryResponse(response.getBody());
            } else {
                SeedanceResponse errorResponse = new SeedanceResponse();
                errorResponse.setStatus("failed");
                errorResponse.setErrorMessage("查询任务状态失败: " + response.getStatusCode());
                return errorResponse;
            }
        } catch (Exception e) {
            log.error("查询任务状态异常: taskId={}", taskId, e);
            SeedanceResponse errorResponse = new SeedanceResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage(e.getMessage());
            return errorResponse;
        }
    }

    @Override
    public SeedanceResponse generateVideo(SeedanceRequest request) {
        long startTime = System.currentTimeMillis();

        // 提交任务
        SeedanceResponse response = submitVideoGeneration(request);
        if (!"pending".equals(response.getStatus()) && !"processing".equals(response.getStatus())) {
            return response;
        }

        String taskId = response.getTaskId();
        log.info("视频生成任务已提交: taskId={}", taskId);

        // 轮询任务状态
        int maxAttempts = timeout / pollInterval;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                Thread.sleep(pollInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            response = queryTaskStatus(taskId);
            log.info("轮询任务状态: taskId={}, status={}", taskId, response.getStatus());

            if ("completed".equals(response.getStatus())) {
                response.setGenerationTimeMs(System.currentTimeMillis() - startTime);

                // 上传视频到OSS
                if (response.getVideoUrl() != null) {
                    String ossUrl = ossService.uploadVideoFromUrl(response.getVideoUrl(), "videos");
                    if (ossUrl != null) {
                        response.setVideoUrl(ossUrl);
                    }
                }

                return response;
            } else if ("failed".equals(response.getStatus())) {
                return response;
            }
        }

        // 超时
        SeedanceResponse timeoutResponse = new SeedanceResponse();
        timeoutResponse.setStatus("failed");
        timeoutResponse.setErrorMessage("视频生成超时");
        return timeoutResponse;
    }

    @Override
    public String buildShotPrompt(Long shotId) {
        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            return "";
        }

        StringBuilder prompt = new StringBuilder();

        // 添加场景描述
        if (shot.getSceneId() != null) {
            Scene scene = sceneMapper.selectById(shot.getSceneId());
            if (scene != null) {
                prompt.append("Scene: ").append(scene.getDescription()).append(". ");
            }
        }

        // 添加分镜描述
        if (shot.getDescription() != null) {
            prompt.append(shot.getDescription()).append(". ");
        }

        // 添加角色描述
        LambdaQueryWrapper<ShotCharacter> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShotCharacter::getShotId, shotId);
        List<ShotCharacter> shotCharacters = shotCharacterMapper.selectList(wrapper);

        for (ShotCharacter sc : shotCharacters) {
            Role role = roleMapper.selectById(sc.getRoleId());
            if (role != null) {
                prompt.append(role.getRoleName());
                if (sc.getCharacterAction() != null) {
                    prompt.append(" ").append(sc.getCharacterAction());
                }
                if (sc.getCharacterExpression() != null) {
                    prompt.append(", expression: ").append(sc.getCharacterExpression());
                }
                prompt.append(". ");
            }
        }

        // 添加镜头信息
        if (shot.getCameraAngle() != null) {
            prompt.append("Camera angle: ").append(shot.getCameraAngle()).append(". ");
        }
        if (shot.getCameraMovement() != null) {
            prompt.append("Camera movement: ").append(shot.getCameraMovement()).append(". ");
        }

        return prompt.toString();
    }

    /**
     * 解析提交响应
     */
    private SeedanceResponse parseSubmitResponse(String responseBody) {
        JSONObject json = JSON.parseObject(responseBody);
        SeedanceResponse response = new SeedanceResponse();
        response.setTaskId(json.getString("id"));
        response.setStatus(json.getString("status"));
        response.setSeed(json.getLong("seed"));
        return response;
    }

    /**
     * 解析查询响应
     */
    private SeedanceResponse parseQueryResponse(String responseBody) {
        JSONObject json = JSON.parseObject(responseBody);
        SeedanceResponse response = new SeedanceResponse();
        response.setTaskId(json.getString("id"));
        response.setStatus(json.getString("status"));
        response.setSeed(json.getLong("seed"));

        if ("completed".equals(response.getStatus())) {
            JSONArray dataArray = json.getJSONArray("data");
            if (dataArray != null && !dataArray.isEmpty()) {
                JSONObject videoData = dataArray.getJSONObject(0);
                response.setVideoUrl(videoData.getString("url"));
                response.setThumbnailUrl(videoData.getString("thumbnail_url"));
            }
        } else if ("failed".equals(response.getStatus())) {
            response.setErrorMessage(json.getString("error"));
        }

        return response;
    }
}
