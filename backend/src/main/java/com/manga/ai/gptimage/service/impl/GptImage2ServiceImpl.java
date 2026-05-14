package com.manga.ai.gptimage.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.service.OssService;
import com.manga.ai.gptimage.dto.GptImage2GenerateRequest;
import com.manga.ai.gptimage.dto.GptImage2GenerateResponse;
import com.manga.ai.gptimage.service.GptImage2Service;
import com.manga.ai.user.service.UserService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

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
    private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;

    private final OssService ossService;
    private final UserService userService;
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
            @Value("${gpt-image2.timeout:300000}") int timeout) {
        this.ossService = ossService;
        this.userService = userService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000);
        factory.setReadTimeout(timeout);
        this.restTemplate = new RestTemplate(factory);
    }

    public GptImage2ServiceImpl(OssService ossService, UserService userService) {
        this(ossService, userService, 300000);
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
        String mode = hasText(request.getReferenceImageUrl()) ? "image-to-image" : "text-to-image";
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("prompt", request.getPrompt().trim());
        requestBody.put("size", convertAspectRatioToSize(aspectRatio));
        requestBody.put("n", 1);
        requestBody.put("response_format", "url");
        requestBody.put("stream", false);
        requestBody.put("watermark", false);
        if (hasText(request.getReferenceImageUrl())) {
            requestBody.put("image", request.getReferenceImageUrl().trim());
        }

        try {
            String responseBody = callGenerateApi(requestBody);
            String outputUrl = extractImageUrl(responseBody);
            String ossUrl = persistGeneratedImage(outputUrl);
            return new GptImage2GenerateResponse(ossUrl, model, mode);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("GPT-Image2生成失败: userId={}, mode={}", userId, mode, e);
            throw new BusinessException(500, "GPT-Image2生成失败，请稍后重试");
        }
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
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            if (response.getStatusCode() == HttpStatus.OK && hasText(response.getBody())) {
                return response.getBody();
            }
            throw new BusinessException(500, "GPT-Image2生成失败: " + response.getStatusCode());
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.warn("GPT-Image2 API返回错误: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException((int) e.getStatusCode().value(), friendlyApiError(e.getResponseBodyAsString()));
        }
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

    private String persistGeneratedImage(String imageUrl) {
        if (imageUrl != null && imageUrl.contains("aliyuncs.com")) {
            return ossService.refreshUrl(imageUrl);
        }
        String ossUrl = ossService.uploadImageFromUrl(imageUrl, "gpt-image2/results");
        if (!hasText(ossUrl)) {
            throw new BusinessException("生成图片保存失败");
        }
        return ossUrl;
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

    private String convertAspectRatioToSize(String aspectRatio) {
        switch (aspectRatio) {
            case "16:9":
                return "1536x864";
            case "9:16":
                return "864x1536";
            case "4:3":
                return "1344x1008";
            case "3:4":
                return "1008x1344";
            case "1:1":
            default:
                return "1024x1024";
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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
