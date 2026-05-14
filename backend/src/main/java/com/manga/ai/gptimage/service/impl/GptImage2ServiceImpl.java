package com.manga.ai.gptimage.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.manga.ai.common.enums.CreditUsageType;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.service.OssService;
import com.manga.ai.gptimage.dto.GptImage2GenerateRequest;
import com.manga.ai.gptimage.dto.GptImage2GenerateResponse;
import com.manga.ai.gptimage.entity.GptImage2Task;
import com.manga.ai.gptimage.mapper.GptImage2TaskMapper;
import com.manga.ai.gptimage.service.GptImage2Service;
import com.manga.ai.user.service.UserService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * GPT-Image2 图片生成服务实现
 */
@Slf4j
@Service
public class GptImage2ServiceImpl implements GptImage2Service {

    private static final List<String> ALLOWED_IMAGE_TYPES = Arrays.asList(
            "image/jpeg", "image/png", "image/webp"
    );
    private static final List<String> ALLOWED_ASPECT_RATIOS = Arrays.asList(
            "1:1", "16:9", "9:16", "4:3", "3:4"
    );
    private static final List<String> ALLOWED_RESOLUTIONS = Arrays.asList(
            "1k", "2k", "4k"
    );
    private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;
    private static final int MAX_GENERATE_ATTEMPTS = 3;
    private static final int GENERATE_CREDIT_COST = 12;
    private static final String CREDIT_REFERENCE_TYPE = "GPT_IMAGE2_TASK";

    private final OssService ossService;
    private final UserService userService;
    private final GptImage2TaskMapper taskMapper;
    private final Executor imageGenerateExecutor;
    private final RestTemplate restTemplate;

    @Value("${gpt-image2.api-key:${GPT_IMAGE2_API_KEY:}}")
    private String apiKey;

    @Value("${gpt-image2.base-url:https://api.airiver.cn/v1}")
    private String baseUrl;

    @Value("${gpt-image2.model:gpt-image-2}")
    private String model;

    @Autowired
    public GptImage2ServiceImpl(
            OssService ossService,
            UserService userService,
            GptImage2TaskMapper taskMapper,
            @Qualifier("imageGenerateExecutor") Executor imageGenerateExecutor,
            @Value("${gpt-image2.timeout:300000}") int timeout) {
        this.ossService = ossService;
        this.userService = userService;
        this.taskMapper = taskMapper;
        this.imageGenerateExecutor = imageGenerateExecutor;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000);
        factory.setReadTimeout(timeout);
        this.restTemplate = new RestTemplate(factory);
    }

    public GptImage2ServiceImpl(OssService ossService, UserService userService) {
        this(ossService, userService, null, Runnable::run, 300000);
    }

    public GptImage2ServiceImpl(
            OssService ossService,
            UserService userService,
            GptImage2TaskMapper taskMapper,
            Executor imageGenerateExecutor) {
        this(ossService, userService, taskMapper, imageGenerateExecutor, 300000);
    }

    @Override
    public GptImage2GenerateResponse generate(GptImage2GenerateRequest request) {
        if (request == null || !hasText(request.getPrompt())) {
            throw new BusinessException(400, "请输入提示词");
        }
        if (!hasText(apiKey)) {
            throw new BusinessException(500, "GPT-Image2 API Key 未配置");
        }
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }

        String aspectRatio = normalizeAspectRatio(request.getAspectRatio());
        String resolution = normalizeResolution(request.getResolution());
        String mode = hasText(request.getReferenceImageUrl()) ? "image-to-image" : "text-to-image";
        GptImage2Task task = new GptImage2Task();
        task.setUserId(userId);
        task.setPrompt(request.getPrompt().trim());
        task.setAspectRatio(aspectRatio);
        task.setResolution(resolution);
        task.setReferenceImageUrl(hasText(request.getReferenceImageUrl()) ? request.getReferenceImageUrl().trim() : null);
        task.setStatus("pending");
        task.setModel(model);
        task.setMode(mode);
        task.setCreditCost(GENERATE_CREDIT_COST);
        task.setCreditsRefunded(false);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);

        try {
            userService.deductCredits(userId, GENERATE_CREDIT_COST, CreditUsageType.IMAGE_GENERATION.getCode(),
                    "GPT-Image2生图-任务" + task.getId(), task.getId(), CREDIT_REFERENCE_TYPE);
        } catch (RuntimeException e) {
            task.setStatus("failed");
            task.setErrorMessage(e.getMessage());
            task.setCompletedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            throw e;
        }

        imageGenerateExecutor.execute(() -> executeTask(task.getId()));
        return toResponse(task);
    }

    public void executeTask(Long taskId) {
        if (taskId == null) {
            return;
        }
        GptImage2Task task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("GPT-Image2任务不存在: taskId={}", taskId);
            return;
        }

        long startTime = System.currentTimeMillis();
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", hasText(task.getModel()) ? task.getModel() : model);
        requestBody.put("prompt", task.getPrompt());
        requestBody.put("size", convertAspectRatioToSize(task.getAspectRatio(), task.getResolution()));
        requestBody.put("n", 1);
        requestBody.put("response_format", "url");
        requestBody.put("stream", false);
        requestBody.put("watermark", false);
        if (hasText(task.getReferenceImageUrl())) {
            requestBody.put("image", task.getReferenceImageUrl().trim());
        }

        try {
            task.setStatus("running");
            task.setSubmittedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            String responseBody = callGenerateApi(requestBody);
            String outputUrl = extractImageUrl(responseBody);
            String ossUrl = persistGeneratedImage(outputUrl);
            task.setStatus("succeeded");
            task.setImageUrl(ossUrl);
            task.setGenerationDuration((int) ((System.currentTimeMillis() - startTime) / 1000));
            task.setCompletedAt(LocalDateTime.now());
            log.info("GPT-Image2任务完成: taskId={}", taskId);
        } catch (BusinessException e) {
            task.setStatus("failed");
            task.setErrorMessage(e.getMessage());
            task.setCompletedAt(LocalDateTime.now());
            refundCreditsIfNeeded(task);
            log.warn("GPT-Image2任务失败: taskId={}, error={}", taskId, e.getMessage());
        } catch (Exception e) {
            log.error("GPT-Image2任务异常: taskId={}, userId={}, mode={}", taskId, task.getUserId(), task.getMode(), e);
            task.setStatus("failed");
            task.setErrorMessage(friendlyExceptionMessage(e));
            task.setCompletedAt(LocalDateTime.now());
            refundCreditsIfNeeded(task);
        } finally {
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        }
    }

    @Override
    public GptImage2GenerateResponse getTask(Long taskId) {
        if (taskId == null) {
            throw new BusinessException(400, "任务ID不能为空");
        }
        return toResponse(getOwnedTask(taskId));
    }

    @Override
    public List<GptImage2GenerateResponse> listMyTasks(Integer limit) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        int safeLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 50));
        LambdaQueryWrapper<GptImage2Task> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GptImage2Task::getUserId, userId)
                .orderByDesc(GptImage2Task::getCreatedAt)
                .last("LIMIT " + safeLimit);
        List<GptImage2Task> tasks = taskMapper.selectList(wrapper);
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }
        return tasks.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Override
    public GptImage2GenerateResponse getLatestTask() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        LambdaQueryWrapper<GptImage2Task> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GptImage2Task::getUserId, userId)
                .orderByDesc(GptImage2Task::getCreatedAt)
                .last("LIMIT 1");
        GptImage2Task task = taskMapper.selectOne(wrapper);
        return task == null ? null : toResponse(task);
    }

    @Override
    public String uploadReference(MultipartFile file) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        validateImageFile(file);
        try {
            String extension = extensionFromFilename(file.getOriginalFilename());
            String url = ossService.uploadImage(file.getBytes(), "gpt-image2/references", file.getContentType(), extension);
            if (!hasText(url)) {
                throw new BusinessException("参考图上传失败");
            }
            return url;
        } catch (IOException e) {
            throw new BusinessException("读取参考图失败");
        }
    }

    private String callGenerateApi(JSONObject requestBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey.trim());

        HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);
        String url = baseUrl.replaceAll("/+$", "") + "/images/generations";
        for (int attempt = 1; attempt <= MAX_GENERATE_ATTEMPTS; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
                if (response.getStatusCode() == HttpStatus.OK && hasText(response.getBody())) {
                    return response.getBody();
                }
                throw new BusinessException(500, "GPT-Image2生成失败: " + response.getStatusCode());
            } catch (HttpClientErrorException | HttpServerErrorException e) {
                log.warn("GPT-Image2 API返回错误: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
                throw new BusinessException((int) e.getStatusCode().value(), friendlyApiError(e.getResponseBodyAsString()));
            } catch (RestClientException e) {
                if (attempt < MAX_GENERATE_ATTEMPTS && isRetryableGenerateException(e)) {
                    log.warn("GPT-Image2请求连接中断，准备重试: attempt={}/{}, error={}",
                            attempt, MAX_GENERATE_ATTEMPTS, collectThrowableMessages(e));
                    continue;
                }
                throw e;
            }
        }
        throw new BusinessException(500, "GPT-Image2服务连接中断，请稍后重试");
    }

    private String extractImageUrl(String responseBody) {
        JSONObject json = JSON.parseObject(responseBody);
        JSONArray data = json.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            throw new BusinessException(500, "GPT-Image2未返回图片");
        }

        JSONObject imageData = data.getJSONObject(0);
        String imageUrl = imageData.getString("url");
        if (hasText(imageUrl)) {
            return imageUrl.trim();
        }

        String base64Data = imageData.getString("b64_json");
        if (hasText(base64Data)) {
            byte[] bytes = Base64.getDecoder().decode(base64Data);
            String ossUrl = ossService.uploadImage(bytes, "gpt-image2/results", "image/png", "png");
            if (!hasText(ossUrl)) {
                throw new BusinessException("生成图片保存失败");
            }
            return ossUrl;
        }

        throw new BusinessException(500, "GPT-Image2未返回可用图片地址");
    }

    String persistGeneratedImage(String imageUrl) {
        if (isDataImageUrl(imageUrl)) {
            return uploadDataImage(imageUrl);
        }
        String ossUrl = ossService.uploadImageFromUrl(imageUrl, "gpt-image2/results");
        if (!hasText(ossUrl)) {
            throw new BusinessException("生成图片保存失败");
        }
        return ossUrl;
    }

    private String uploadDataImage(String dataUrl) {
        int commaIndex = dataUrl.indexOf(',');
        if (commaIndex < 0) {
            throw new BusinessException(500, "GPT-Image2返回的图片数据格式异常");
        }
        String metadata = dataUrl.substring(0, commaIndex);
        String base64Data = dataUrl.substring(commaIndex + 1);
        String contentType = "image/png";
        String extension = "png";
        int colonIndex = metadata.indexOf(':');
        int semicolonIndex = metadata.indexOf(';');
        if (colonIndex >= 0 && semicolonIndex > colonIndex) {
            contentType = metadata.substring(colonIndex + 1, semicolonIndex);
            extension = extensionFromContentType(contentType);
        }
        byte[] bytes = Base64.getDecoder().decode(base64Data);
        String ossUrl = ossService.uploadImage(bytes, "gpt-image2/results", contentType, extension);
        if (!hasText(ossUrl)) {
            throw new BusinessException("生成图片保存失败");
        }
        return ossUrl;
    }

    private boolean isDataImageUrl(String imageUrl) {
        return imageUrl != null && imageUrl.startsWith("data:image/") && imageUrl.contains(";base64,");
    }

    private void validateImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的参考图");
        }
        String contentType = file.getContentType();
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if ((contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType))
                && !filename.endsWith(".jpg")
                && !filename.endsWith(".jpeg")
                && !filename.endsWith(".png")
                && !filename.endsWith(".webp")) {
            throw new BusinessException(400, "只支持 JPG、PNG、WEBP 格式的图片");
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new BusinessException(400, "参考图大小不能超过10MB");
        }
    }

    private String normalizeAspectRatio(String aspectRatio) {
        if (!hasText(aspectRatio)) {
            return "1:1";
        }
        String ratio = aspectRatio.trim();
        if (!ALLOWED_ASPECT_RATIOS.contains(ratio)) {
            throw new BusinessException(400, "不支持的图片比例");
        }
        return ratio;
    }

    private String normalizeResolution(String resolution) {
        if (!hasText(resolution)) {
            return "2k";
        }
        String normalized = resolution.trim().toLowerCase();
        if (!ALLOWED_RESOLUTIONS.contains(normalized)) {
            throw new BusinessException(400, "不支持的图片清晰度");
        }
        return normalized;
    }

    private String convertAspectRatioToSize(String aspectRatio, String resolution) {
        String normalizedResolution = normalizeResolution(resolution);
        switch (normalizedResolution) {
            case "1k":
                switch (aspectRatio) {
                    case "16:9": return "1536x864";
                    case "9:16": return "864x1536";
                    case "4:3": return "1344x1008";
                    case "3:4": return "1008x1344";
                    case "1:1":
                    default: return "1024x1024";
                }
            case "4k":
                switch (aspectRatio) {
                    case "16:9": return "4032x2268";
                    case "9:16": return "2268x4032";
                    case "4:3": return "3584x2688";
                    case "3:4": return "2016x2688";
                    case "1:1":
                    default: return "4096x4096";
                }
            case "2k":
            default:
                switch (aspectRatio) {
                    case "16:9": return "2848x1600";
                    case "9:16": return "1600x2848";
                    case "4:3": return "2304x1728";
                    case "3:4": return "1728x2304";
                    case "1:1":
                    default: return "2048x2048";
                }
        }
    }

    private String friendlyApiError(String responseBody) {
        if (!hasText(responseBody)) {
            return "GPT-Image2服务暂时不可用，请稍后重试";
        }
        try {
            JSONObject json = JSON.parseObject(responseBody);
            String code = firstText(json, "code", "Code", "error_code");
            String message = firstText(json, "message", "Message", "error");
            if (hasText(message)) {
                return "GPT-Image2生成失败：" + message + (hasText(code) ? "（" + code + "）" : "");
            }
        } catch (Exception ignored) {
            // 兜底返回通用文案
        }
        return "GPT-Image2生成失败，请调整提示词或参考图后重试";
    }

    private String friendlyExceptionMessage(Exception e) {
        String message = collectThrowableMessages(e);
        String lowerMessage = message.toLowerCase();
        if (lowerMessage.contains("premature eof") || lowerMessage.contains("unexpected end of file")
                || lowerMessage.contains("connection reset") || lowerMessage.contains("connection prematurely closed")) {
            return "GPT-Image2服务连接中断，请稍后重试";
        }
        if (lowerMessage.contains("timeout") || lowerMessage.contains("timed out")) {
            return "GPT-Image2服务响应超时，请稍后重试";
        }
        return "GPT-Image2生成失败，请稍后重试";
    }

    private boolean isRetryableGenerateException(RestClientException e) {
        String message = collectThrowableMessages(e).toLowerCase();
        return message.contains("premature eof")
                || message.contains("unexpected end of file")
                || message.contains("connection reset")
                || message.contains("connection prematurely closed")
                || message.contains("timeout")
                || message.contains("timed out");
    }

    private String collectThrowableMessages(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (hasText(current.getMessage())) {
                if (builder.length() > 0) {
                    builder.append(" | ");
                }
                builder.append(current.getMessage());
            }
            current = current.getCause();
        }
        return builder.toString();
    }

    private GptImage2Task getOwnedTask(Long taskId) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        GptImage2Task task = taskMapper.selectById(taskId);
        if (task == null || !userId.equals(task.getUserId())) {
            throw new BusinessException(404, "任务不存在");
        }
        return task;
    }

    private GptImage2GenerateResponse toResponse(GptImage2Task task) {
        GptImage2GenerateResponse response = new GptImage2GenerateResponse();
        response.setId(task.getId());
        response.setPrompt(task.getPrompt());
        response.setAspectRatio(task.getAspectRatio());
        response.setResolution(hasText(task.getResolution()) ? task.getResolution() : "2k");
        response.setReferenceImageUrl(task.getReferenceImageUrl());
        response.setImageUrl(task.getImageUrl());
        response.setStatus(task.getStatus());
        response.setStatusDesc(statusDesc(task.getStatus()));
        response.setProgressPercent(progressPercent(task.getStatus()));
        response.setModel(task.getModel());
        response.setMode(task.getMode());
        response.setCreditCost(task.getCreditCost() == null ? GENERATE_CREDIT_COST : task.getCreditCost());
        response.setErrorMessage(task.getErrorMessage());
        response.setSubmittedAt(task.getSubmittedAt());
        response.setCompletedAt(task.getCompletedAt());
        response.setGenerationDuration(task.getGenerationDuration());
        response.setCreatedAt(task.getCreatedAt());
        response.setUpdatedAt(task.getUpdatedAt());
        return response;
    }

    private void refundCreditsIfNeeded(GptImage2Task task) {
        if (task == null || userService == null) {
            return;
        }
        Integer creditCost = task.getCreditCost();
        if (creditCost == null || creditCost <= 0 || Boolean.TRUE.equals(task.getCreditsRefunded())) {
            return;
        }
        userService.refundCredits(task.getUserId(), creditCost,
                "GPT-Image2生图失败返还-任务" + task.getId(), task.getId(), CREDIT_REFERENCE_TYPE);
        task.setCreditsRefunded(true);
        log.info("GPT-Image2任务失败返还积分: userId={}, taskId={}, credits={}",
                task.getUserId(), task.getId(), creditCost);
    }

    private String statusDesc(String status) {
        if ("succeeded".equals(status)) {
            return "已生成";
        }
        if ("failed".equals(status)) {
            return "生成失败";
        }
        if ("running".equals(status)) {
            return "生成中";
        }
        return "排队中";
    }

    private Integer progressPercent(String status) {
        if ("succeeded".equals(status) || "failed".equals(status)) {
            return 100;
        }
        if ("running".equals(status)) {
            return 35;
        }
        return 5;
    }

    private String firstText(JSONObject json, String... keys) {
        for (String key : keys) {
            Object value = json.get(key);
            if (value instanceof String && hasText((String) value)) {
                return ((String) value).trim();
            }
            if (value instanceof JSONObject) {
                String nested = firstText((JSONObject) value, "message", "Message", "code", "Code");
                if (hasText(nested)) {
                    return nested;
                }
            }
        }
        return null;
    }

    private String extensionFromFilename(String filename) {
        if (!hasText(filename) || !filename.contains(".")) {
            return "png";
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        if ("jpeg".equals(extension)) {
            return "jpg";
        }
        if (!Arrays.asList("jpg", "png", "webp").contains(extension)) {
            return "png";
        }
        return extension;
    }

    private String extensionFromContentType(String contentType) {
        if ("image/jpeg".equals(contentType)) {
            return "jpg";
        }
        if ("image/webp".equals(contentType)) {
            return "webp";
        }
        return "png";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
