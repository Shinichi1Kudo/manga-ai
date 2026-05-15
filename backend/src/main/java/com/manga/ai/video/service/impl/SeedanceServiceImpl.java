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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seedance视频生成服务实现
 */
@Slf4j
@Service
public class SeedanceServiceImpl implements SeedanceService {

    private static final String FAST_VIP_MODEL = "doubao-seedance-2-0-fast-260128";
    private static final String VIP_MODEL = "doubao-seedance-2-0-260128";
    private static final String KLING_V3_OMNI_MODEL = "kling-v3-omni";

    @Value("${volcengine.seedance.api-key}")
    private String apiKey;

    @Value("${volcengine.seedance.model:seedance-2.0}")
    private String model;

    @Value("${volcengine.seedance.base-url:https://ark.cn-beijing.volces.com/api/v3}")
    private String baseUrl;

    @Value("${volcengine.seedance.fast-api-key:${volcengine.seedance.api-key}}")
    private String fastApiKey;

    @Value("${volcengine.seedance.fast-base-url:https://toapis.com/v1}")
    private String fastBaseUrl;

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
    private final Map<String, Boolean> fastTaskById = new ConcurrentHashMap<>();

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
        // 确定使用的模型：优先使用请求中的模型，否则使用配置的默认模型
        String useModel = request.getModel();
        if (useModel == null || useModel.isEmpty()) {
            useModel = "doubao-seedance-2-0-fast-260128"; // 默认使用 Fast 模型
        }

        log.info("提交视频生成任务: model={}, duration={}s, referenceImages={}",
                useModel, request.getDuration(),
                request.getContents() != null ? request.getContents().size() : 0);

        boolean toapisModel = isToapisModel(useModel);
        JSONObject requestBody = toapisModel ? buildToapisRequestBody(request, useModel) : buildVolcengineRequestBody(request, useModel);
        String url = toapisModel
                ? trimTrailingSlash(fastBaseUrl) + "/videos/generations"
                : trimTrailingSlash(baseUrl) + "/contents/generations/tasks";
        String authorizationApiKey = toapisModel ? fastApiKey : apiKey;

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + authorizationApiKey);

            HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);

            log.info("调用Seedance API: {}", url);
            log.info("请求体: {}", requestBody.toJSONString());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                SeedanceResponse submitResponse = parseSubmitResponse(response.getBody());
                applySubmitRequestMetadata(submitResponse, url, requestBody.toJSONString(), useModel);
                if (submitResponse.getTaskId() != null) {
                    fastTaskById.put(submitResponse.getTaskId(), toapisModel);
                }
                return submitResponse;
            } else {
                SeedanceResponse errorResponse = new SeedanceResponse();
                errorResponse.setStatus("failed");
                errorResponse.setErrorMessage("视频生成任务提交失败: " + response.getStatusCode());
                applySubmitRequestMetadata(errorResponse, url, requestBody.toJSONString(), useModel);
                return errorResponse;
            }
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("Seedance API调用失败: statusCode={}, responseBody={}", e.getStatusCode(), e.getResponseBodyAsString());
            SeedanceResponse errorResponse = new SeedanceResponse();
            errorResponse.setStatus("failed");

            // 解析错误信息并转换为中文友好提示
            String errorBody = e.getResponseBodyAsString();
            String friendlyError = parseApiError(errorBody);
            errorResponse.setErrorMessage(friendlyError);
            applySubmitRequestMetadata(errorResponse, url, requestBody.toJSONString(), useModel);
            return errorResponse;
        } catch (Exception e) {
            log.error("视频生成任务提交异常", e);
            SeedanceResponse errorResponse = new SeedanceResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage(e.getMessage());
            applySubmitRequestMetadata(errorResponse, url, requestBody.toJSONString(), useModel);
            return errorResponse;
        }
    }

    @Override
    public SeedanceResponse queryTaskStatus(String taskId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            boolean fastVip = Boolean.TRUE.equals(fastTaskById.get(taskId));
            headers.set("Authorization", "Bearer " + (fastVip ? fastApiKey : apiKey));

            HttpEntity<String> entity = new HttpEntity<>(headers);
            String url = fastVip
                    ? trimTrailingSlash(fastBaseUrl) + "/videos/generations/" + taskId
                    : trimTrailingSlash(baseUrl) + "/contents/generations/tasks/" + taskId;

            log.info("查询任务状态: taskId={}", taskId);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseQueryResponse(response.getBody());
            } else {
                SeedanceResponse errorResponse = new SeedanceResponse();
                errorResponse.setStatus("failed");
                errorResponse.setErrorMessage("查询任务状态失败: " + response.getStatusCode());
                return errorResponse;
            }
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("查询任务状态失败: taskId={}, statusCode={}, responseBody={}", taskId, e.getStatusCode(), e.getResponseBodyAsString());
            SeedanceResponse errorResponse = new SeedanceResponse();
            errorResponse.setStatus("failed");
            errorResponse.setTaskId(taskId);
            errorResponse.setErrorMessage(parseApiError(e.getResponseBodyAsString()));
            return errorResponse;
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
        String submitRequestUrl = response.getSubmitRequestUrl();
        String submitRequestBody = response.getSubmitRequestBody();
        String submitModel = response.getSubmitModel();
        // 如果提交失败（有错误信息），直接返回
        if ("failed".equals(response.getStatus()) && response.getErrorMessage() != null) {
            return response;
        }
        // 如果没有 taskId，说明提交失败
        if (response.getTaskId() == null) {
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
            applySubmitRequestMetadata(response, submitRequestUrl, submitRequestBody, submitModel);
            log.info("轮询任务状态: taskId={}, status={}", taskId, response.getStatus());

            if ("completed".equals(response.getStatus()) || "succeeded".equals(response.getStatus())) {
                response.setGenerationTimeMs(System.currentTimeMillis() - startTime);

                // 上传视频到OSS
                if (response.getVideoUrl() != null) {
                    response.setProviderVideoUrl(response.getVideoUrl());
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
        applySubmitRequestMetadata(timeoutResponse, submitRequestUrl, submitRequestBody, submitModel);
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
     * 官方响应: {"id": "cgt-2025******-****"}
     */
    private SeedanceResponse parseSubmitResponse(String responseBody) {
        log.info("解析提交响应: {}", responseBody);
        JSONObject json = JSON.parseObject(responseBody);
        SeedanceResponse response = new SeedanceResponse();
        response.setTaskId(firstNonBlank(json.getString("id"), json.getString("task_id")));
        // 提交成功后默认为 pending 状态
        response.setStatus(json.getString("status"));
        if (response.getStatus() == null) {
            response.setStatus("pending");
        }
        response.setSeed(json.getLong("seed"));
        return response;
    }

    /**
     * 解析查询响应
     */
    private SeedanceResponse parseQueryResponse(String responseBody) {
        log.info("解析查询响应: {}", responseBody);
        JSONObject json = JSON.parseObject(responseBody);
        SeedanceResponse response = new SeedanceResponse();
        response.setTaskId(firstNonBlank(json.getString("id"), json.getString("task_id")));
        response.setStatus(json.getString("status"));
        response.setSeed(json.getLong("seed"));

        if ("completed".equals(response.getStatus()) || "succeeded".equals(response.getStatus())) {
            // 新格式：content.video_url
            JSONObject content = json.getJSONObject("content");
            if (content != null) {
                response.setVideoUrl(content.getString("video_url"));
                // 尝试多个可能的缩略图字段名
                String thumbnailUrl = content.getString("thumbnail_url");
                if (thumbnailUrl == null) {
                    thumbnailUrl = content.getString("cover_url");
                }
                if (thumbnailUrl == null) {
                    thumbnailUrl = content.getString("poster_url");
                }
                response.setThumbnailUrl(thumbnailUrl);
            }
            // 兼容旧格式：data[0].url
            JSONArray dataArray = json.getJSONArray("data");
            if (dataArray != null && !dataArray.isEmpty()) {
                JSONObject videoData = dataArray.getJSONObject(0);
                if (response.getVideoUrl() == null) {
                    response.setVideoUrl(videoData.getString("url"));
                }
                if (response.getThumbnailUrl() == null) {
                    String thumbnailUrl = videoData.getString("thumbnail_url");
                    if (thumbnailUrl == null) {
                        thumbnailUrl = videoData.getString("cover_url");
                    }
                    response.setThumbnailUrl(thumbnailUrl);
                }
            }
            if (response.getVideoUrl() == null) {
                response.setVideoUrl(firstNonBlank(json.getString("video_url"), json.getString("url")));
            }
            JSONObject metadata = json.getJSONObject("metadata");
            if (metadata != null && response.getVideoUrl() == null) {
                response.setVideoUrl(firstNonBlank(metadata.getString("video_url"), metadata.getString("url")));
            }
            JSONObject result = json.getJSONObject("result");
            if (result != null && response.getVideoUrl() == null) {
                JSONArray resultData = result.getJSONArray("data");
                if (resultData != null && !resultData.isEmpty()) {
                    JSONObject videoData = resultData.getJSONObject(0);
                    response.setVideoUrl(firstNonBlank(videoData.getString("url"), videoData.getString("video_url")));
                    response.setThumbnailUrl(firstNonBlank(
                            videoData.getString("cover_url"),
                            videoData.getString("thumbnail_url"),
                            videoData.getString("poster_url")
                    ));
                }
            }
            JSONObject data = json.getJSONObject("data");
            if (data != null && response.getVideoUrl() == null) {
                JSONObject taskResult = data.getJSONObject("task_result");
                if (taskResult != null) {
                    JSONArray videos = taskResult.getJSONArray("videos");
                    if (videos != null && !videos.isEmpty()) {
                        JSONObject video = videos.getJSONObject(0);
                        response.setVideoUrl(firstNonBlank(video.getString("url"), video.getString("video_url")));
                        response.setThumbnailUrl(firstNonBlank(
                                video.getString("cover_url"),
                                video.getString("thumbnail_url"),
                                video.getString("poster_url")
                        ));
                    }
                }
            }
            if (response.getThumbnailUrl() == null) {
                response.setThumbnailUrl(firstNonBlank(
                        json.getString("thumbnail_url"),
                        json.getString("cover_url"),
                        json.getString("poster_url")
                ));
            }
        } else if ("failed".equals(response.getStatus())) {
            response.setErrorMessage(toFriendlyApiError(json.get("error")));
        }

        return response;
    }

    private JSONObject buildVolcengineRequestBody(SeedanceRequest request, String useModel) {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", useModel);

        JSONArray contentArray = new JSONArray();
        JSONObject textContent = new JSONObject();
        textContent.put("type", "text");
        textContent.put("text", request.getPrompt());
        contentArray.add(textContent);

        if (request.getContents() != null && !request.getContents().isEmpty()) {
            for (SeedanceRequest.ReferenceContent content : request.getContents()) {
                if ("image_url".equals(content.getType()) && content.getImageUrl() != null) {
                    JSONObject imageContent = new JSONObject();
                    imageContent.put("type", "image_url");
                    imageContent.put("role", "reference_image");

                    JSONObject imageUrl = new JSONObject();
                    imageUrl.put("url", content.getImageUrl().getUrl());
                    imageContent.put("image_url", imageUrl);

                    contentArray.add(imageContent);
                } else if ("video_url".equals(content.getType()) && content.getVideoUrl() != null) {
                    JSONObject videoContent = new JSONObject();
                    videoContent.put("type", "video_url");
                    videoContent.put("role", content.getRole() != null ? content.getRole() : "reference_video");

                    JSONObject videoUrl = new JSONObject();
                    videoUrl.put("url", content.getVideoUrl().getUrl());
                    videoContent.put("video_url", videoUrl);

                    contentArray.add(videoContent);
                }
            }
        }

        requestBody.put("content", contentArray);
        requestBody.put("duration", request.getDuration());
        requestBody.put("ratio", request.getRatio() != null ? request.getRatio() : "16:9");
        requestBody.put("watermark", request.getWatermark() != null ? request.getWatermark() : false);
        if (request.getGenerateAudio() != null) {
            requestBody.put("generate_audio", request.getGenerateAudio());
        }
        if (request.getSeed() != null) {
            requestBody.put("seed", request.getSeed());
        }
        return requestBody;
    }

    private JSONObject buildToapisRequestBody(SeedanceRequest request, String useModel) {
        if (isKlingOmniModel(useModel)) {
            return buildKlingOmniRequestBody(request);
        }

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", useModel);
        requestBody.put("prompt", request.getPrompt());
        requestBody.put("duration", request.getDuration());
        requestBody.put("aspect_ratio", request.getRatio() != null ? request.getRatio() : "16:9");
        requestBody.put("watermark", request.getWatermark() != null ? request.getWatermark() : false);
        if (request.getSeed() != null) {
            requestBody.put("seed", request.getSeed());
        }

        JSONObject metadata = new JSONObject();
        metadata.put("resolution", resolutionFromSize(request.getWidth(), request.getHeight()));
        if (request.getGenerateAudio() != null) {
            metadata.put("generate_audio", request.getGenerateAudio());
        }
        requestBody.put("metadata", metadata);

        JSONArray imageWithRoles = new JSONArray();
        JSONArray videoWithRoles = new JSONArray();
        if (request.getContents() != null) {
            for (SeedanceRequest.ReferenceContent content : request.getContents()) {
                if ("image_url".equals(content.getType()) && content.getImageUrl() != null) {
                    JSONObject image = new JSONObject();
                    image.put("role", content.getRole() != null ? content.getRole() : "reference_image");
                    image.put("url", content.getImageUrl().getUrl());
                    imageWithRoles.add(image);
                } else if ("video_url".equals(content.getType()) && content.getVideoUrl() != null) {
                    JSONObject video = new JSONObject();
                    video.put("role", content.getRole() != null ? content.getRole() : "reference_video");
                    video.put("url", content.getVideoUrl().getUrl());
                    videoWithRoles.add(video);
                }
            }
        }
        if (!imageWithRoles.isEmpty()) {
            requestBody.put("image_with_roles", imageWithRoles);
        }
        if (!videoWithRoles.isEmpty()) {
            requestBody.put("video_with_roles", videoWithRoles);
        }
        return requestBody;
    }

    private JSONObject buildKlingOmniRequestBody(SeedanceRequest request) {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", KLING_V3_OMNI_MODEL);
        requestBody.put("model_name", KLING_V3_OMNI_MODEL);
        requestBody.put("multi_shot", false);
        requestBody.put("prompt", request.getPrompt());
        requestBody.put("sound", "on");
        requestBody.put("audio", true);
        requestBody.put("mode", klingModeFromRequest(request));
        requestBody.put("aspect_ratio", request.getRatio() != null ? request.getRatio() : "16:9");
        requestBody.put("duration", request.getDuration() != null ? request.getDuration() : 5);

        JSONObject watermarkInfo = new JSONObject();
        watermarkInfo.put("enabled", Boolean.TRUE.equals(request.getWatermark()));
        requestBody.put("watermark_info", watermarkInfo);

        JSONArray imageList = new JSONArray();
        if (request.getContents() != null) {
            for (SeedanceRequest.ReferenceContent content : request.getContents()) {
                if ("image_url".equals(content.getType()) && content.getImageUrl() != null
                        && content.getImageUrl().getUrl() != null && !content.getImageUrl().getUrl().isBlank()) {
                    JSONObject image = new JSONObject();
                    image.put("image_url", normalizePublicUrl(content.getImageUrl().getUrl()));
                    imageList.add(image);
                }
            }
        }
        if (!imageList.isEmpty()) {
            JSONObject metadata = new JSONObject();
            metadata.put("image_list", imageList);
            requestBody.put("metadata", metadata);
        }
        return requestBody;
    }

    private String normalizePublicUrl(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceFirst("^http://", "https://");
    }

    private boolean isFastVipModel(String useModel) {
        return FAST_VIP_MODEL.equals(useModel) || "seedance-2.0-fast".equals(useModel);
    }

    private boolean isToapisModel(String useModel) {
        return isFastVipModel(useModel) || VIP_MODEL.equals(useModel) || "seedance-2.0".equals(useModel) || isKlingOmniModel(useModel);
    }

    private boolean isKlingOmniModel(String useModel) {
        return KLING_V3_OMNI_MODEL.equals(useModel);
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("/+$", "");
    }

    private String resolutionFromSize(Integer width, Integer height) {
        int longSide = Math.max(width == null ? 0 : width, height == null ? 0 : height);
        if (longSide >= 1800) {
            return "1080p";
        }
        if (longSide <= 960) {
            return "480p";
        }
        return "720p";
    }

    private String modeFromSize(Integer width, Integer height) {
        String resolution = resolutionFromSize(width, height);
        if ("1080p".equals(resolution)) {
            return "4k";
        }
        if ("480p".equals(resolution)) {
            return "std";
        }
        return "pro";
    }

    private String klingModeFromRequest(SeedanceRequest request) {
        String mode = modeFromSize(request.getWidth(), request.getHeight());
        if ("std".equals(mode)) {
            return "pro";
        }
        return mode;
    }

    private void applySubmitRequestMetadata(SeedanceResponse response, String requestUrl, String requestBody, String submitModel) {
        if (response == null) {
            return;
        }
        response.setSubmitRequestUrl(requestUrl);
        response.setSubmitRequestBody(requestBody);
        response.setSubmitModel(submitModel);
    }

    /**
     * 解析API错误并返回中文友好提示
     */
    private String parseApiError(String errorBody) {
        try {
            JSONObject json = JSON.parseObject(errorBody);
            JSONObject error = json.getJSONObject("error");
            if (error != null) {
                return toFriendlyApiError(error);
            }
            if (json.containsKey("code") || json.containsKey("Code")
                    || json.containsKey("message") || json.containsKey("Message")) {
                return toFriendlyApiError(json);
            }
        } catch (Exception e) {
            log.warn("解析错误信息失败: {}", e.getMessage());
        }
        return "视频生成失败，请稍后重试";
    }

    private String toFriendlyApiError(Object errorPayload) {
        if (errorPayload == null) {
            return "视频生成失败，请稍后重试";
        }
        if (errorPayload instanceof JSONObject error) {
            String code = firstNonBlank(error.getString("code"), error.getString("Code"));
            String message = firstNonBlank(error.getString("message"), error.getString("Message"), error.toJSONString());
            return toFriendlyApiError(code, message);
        }
        String message = String.valueOf(errorPayload);
        String code = extractErrorCode(message);
        return toFriendlyApiError(code, message);
    }

    private String toFriendlyApiError(String code, String message) {
        String normalizedCode = code == null ? "" : code.trim();
        String normalizedMessage = message == null ? "" : message.trim();
        String combined = (normalizedCode + " " + normalizedMessage).toLowerCase();

        if (containsAny(combined, "missingparameter")) {
            return "请求缺少必要参数，请检查视频、参考图、比例、时长和替换描述后重试。";
        }
        if (containsAny(combined, "invalidparameter", "invalidargumenterror", "invalidimageurl")) {
            return "请求参数不合法，请检查视频、参考图格式或替换描述后重试。";
        }
        if (containsAny(combined, "outofcontexterror")) {
            return "输入内容过长，已超过模型上下文限制，请减少参考图数量或缩短替换描述后重试。";
        }
        if (containsAny(combined, "policyviolation", "copyright")) {
            return "输入或生成内容可能涉及版权限制，请更换素材或调整描述后重试。";
        }
        if (containsAny(combined, "privacyinformation", "real person")) {
            return "输入图片或视频可能包含真人隐私信息，请更换素材后重试。";
        }
        if (containsAny(combined, "sensitivecontentdetected", "riskdetection", "contentviolation", "contentsecurity")) {
            if (containsAny(combined, "inputimage")) {
                return "输入参考图可能包含敏感内容，请更换参考图后重试。";
            }
            if (containsAny(combined, "inputvideo")) {
                return "输入视频可能包含敏感内容，请更换视频后重试。";
            }
            if (containsAny(combined, "inputtext")) {
                return "输入提示词可能包含敏感内容，请调整替换描述后重试。";
            }
            if (containsAny(combined, "outputvideo", "outputimage", "outputtext")) {
                return "生成结果可能触发内容审核，请调整视频、参考图或替换描述后重试。";
            }
            return "内容可能触发平台审核，请调整视频、参考图或替换描述后重试。";
        }
        if (containsAny(combined, "authenticationerror", "invalidapikey")) {
            return "视频生成服务鉴权失败，请检查 API Key 配置。";
        }
        if (containsAny(combined, "invalidaccountstatus")) {
            return "视频生成服务账号状态异常，请检查账号状态或联系管理员。";
        }
        if (containsAny(combined, "serviceoverdue", "accountoverdueerror", "insufficientbalance", "overdue",
                "quota is not enough", "quota not enough", "user quota")) {
            return "视频生成服务账号欠费或余额不足，请充值后重试。";
        }
        if (containsAny(combined, "servicenotopen", "modelnotopen")) {
            return "模型服务未开通，请开通对应模型后重试。";
        }
        if (containsAny(combined, "accessdenied", "permissiondenied", "invalidsubscription")) {
            return "当前账号没有访问该模型或资源的权限，请检查权限、白名单或套餐状态。";
        }
        if (containsAny(combined, "invalidendpointormodel", "unsupportedmodel", "modelnotfound")) {
            return "模型或推理接入点不可用，请检查模型配置后重试。";
        }
        if (containsAny(combined, "rateLimit".toLowerCase(), "quotaexceeded", "serveroverloaded",
                "requestbursttoofast", "setlimitexceeded", "inflightbatchsizeexceeded",
                "accountratelimitexceeded", "too many requests")) {
            return "当前请求过多或额度已达上限，请稍后重试，或检查视频生成服务额度和并发限制。";
        }
        if (containsAny(combined, "closedendpoint")) {
            return "推理接入点暂时不可用，请稍后重试或检查接入点状态。";
        }
        if (containsAny(combined, "internalserviceerror", "internalerror", "unknownerror",
                "internal error", "internalservererror")) {
            return "视频生成服务内部异常，请稍后重试。";
        }
        if (containsAny(combined, "invaliddata", "invalidjson", "invalidjsonl")) {
            return "输入数据格式不符合要求，请检查素材或请求内容后重试。";
        }
        if (containsAny(combined, "timeout", "timed out", "超时")) {
            return "视频生成超时，请稍后重试。";
        }
        if (!normalizedMessage.isEmpty()) {
            return stripRequestId(normalizedMessage);
        }
        return "视频生成失败，请稍后重试";
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && text.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String extractErrorCode(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String[] knownCodes = {
                "MissingParameter", "InvalidParameter", "InvalidEndpoint.ClosedEndpoint",
                "SensitiveContentDetected", "SensitiveContentDetected.SevereViolation",
                "SensitiveContentDetected.Violence", "InputTextSensitiveContentDetected",
                "InputImageSensitiveContentDetected", "InputVideoSensitiveContentDetected",
                "InputAudioSensitiveContentDetected", "OutputTextSensitiveContentDetected",
                "OutputImageSensitiveContentDetected", "OutputVideoSensitiveContentDetected",
                "OutputAudioSensitiveContentDetected", "PolicyViolation", "PrivacyInformation",
                "InputTextRiskDetection", "InputImageRiskDetection", "OutputTextRiskDetection",
                "OutputImageRiskDetection", "ContentSecurityDetectionError",
                "InvalidArgumentError", "InvalidImageURL", "OutofContextError",
                "AuthenticationError", "InvalidAccountStatus", "ServiceNotOpen",
                "ServiceOverdue", "AccountOverdueError", "AccessDenied", "PermissionDenied",
                "InvalidEndpointOrModel", "ModelNotOpen", "UnsupportedModel",
                "RateLimitExceeded", "QuotaExceeded", "ServerOverloaded",
                "RequestBurstTooFast", "SetLimitExceeded", "InflightBatchsizeExceeded",
                "AccountRateLimitExceeded", "InternalServiceError"
        };
        for (String knownCode : knownCodes) {
            if (message.contains(knownCode)) {
                return knownCode;
            }
        }
        return null;
    }

    private String stripRequestId(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("(?i)\\s*\\(?\\s*Request\\s*ID\\s*:\\s*[^;)\\n。]*\\)?", "")
                .replaceAll("(?i)\\s*\\(?\\s*Request\\s*id\\s*:\\s*[^;)\\n。]*\\)?", "")
                .replaceAll("\\s*[（(]\\s*[）)]\\s*", "")
                .trim();
    }
}
