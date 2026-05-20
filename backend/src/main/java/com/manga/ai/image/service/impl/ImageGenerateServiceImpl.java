package com.manga.ai.image.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.manga.ai.asset.entity.AssetMetadata;
import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.asset.mapper.AssetMetadataMapper;
import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.asset.service.AssetService;
import com.manga.ai.common.enums.AssetStatus;
import com.manga.ai.common.enums.RoleStatus;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.service.OssService;
import com.manga.ai.image.dto.ImageGenerateRequest;
import com.manga.ai.image.dto.ImageGenerateResponse;
import com.manga.ai.image.service.ImageGenerateService;
import com.manga.ai.role.entity.Role;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.series.entity.Series;
import com.manga.ai.series.mapper.SeriesMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;

/**
 * 火山方舟图像生成服务实现 - 使用HTTP API
 */
@Slf4j
@Service
public class ImageGenerateServiceImpl implements ImageGenerateService {

    private static final String CHARACTER_EMPTY_HANDS_CONSTRAINT =
            "CRITICAL HANDS CONSTRAINT: empty hands only, hands relaxed and visible, "
                    + "arms naturally down or A-pose/T-pose. NO objects in hands, "
                    + "NO holding anything, no computer, no laptop, no documents, no papers, "
                    + "no folders, no books, no phone, no bag, no weapon, no prop held by the character. ";

    @Value("${volcengine.ark.api-key}")
    private String apiKey;

    @Value("${volcengine.ark.model:doubao-seedream-5-0-260128}")
    private String model;

    @Value("${volcengine.ark.lite-model:doubao-seedream-5-0-lite-260128}")
    private String liteModel;

    @Value("${volcengine.ark.base-url:https://ark.cn-beijing.volces.com/api/v3}")
    private String baseUrl;

    @Value("${commerce.model-image.api-key:${volcengine.ark.api-key}}")
    private String commerceModelImageApiKey;

    @Value("${commerce.model-image.base-url:https://ark.cn-beijing.volces.com/api/v3}")
    private String commerceModelImageBaseUrl;

    @Value("${commerce.model-image.model:doubao-seedream-5-0-260128}")
    private String commerceModelImageModel;

    @Value("${commerce.try-on-first-frame.model:doubao-seedream-5-0-lite-260128}")
    private String commerceTryOnFirstFrameModel;

    @Value("${storage.project-path:./storage/projects}")
    private String projectPath;

    private final RestTemplate restTemplate;
    private final RoleMapper roleMapper;
    private final SeriesMapper seriesMapper;
    private final RoleAssetMapper roleAssetMapper;
    private final AssetMetadataMapper assetMetadataMapper;
    @Lazy
    private final AssetService assetService;
    private final OssService ossService;
    private final TransactionTemplate transactionTemplate;

    public ImageGenerateServiceImpl(RoleMapper roleMapper, SeriesMapper seriesMapper,
                                    RoleAssetMapper roleAssetMapper, AssetMetadataMapper assetMetadataMapper,
                                    @Lazy AssetService assetService, OssService ossService,
                                    TransactionTemplate transactionTemplate) {
        // 配置 RestTemplate 超时
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(60000);  // 连接超时 60秒
        factory.setReadTimeout(300000);    // 读取超时 300秒 (5分钟，图生图需要更长时间)
        this.restTemplate = new RestTemplate(factory);

        this.roleMapper = roleMapper;
        this.seriesMapper = seriesMapper;
        this.roleAssetMapper = roleAssetMapper;
        this.assetMetadataMapper = assetMetadataMapper;
        this.assetService = assetService;
        this.ossService = ossService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public ImageGenerateResponse generateCharacterSheet(ImageGenerateRequest request) {
        log.info("文生图: role={}, prompt={}", request.getRoleName(), request.getCustomPrompt());

        try {
            String prompt = buildFinalCharacterSheetPrompt(request);
            String size = convertAspectRatioToSize(request.getAspectRatio(), request.getQuality());

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);
            requestBody.put("prompt", prompt);
            requestBody.put("size", size);
            requestBody.put("response_format", "url");
            requestBody.put("n", 1);
            requestBody.put("stream", false);
            requestBody.put("watermark", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);
            String url = baseUrl + "/images/generations";

            log.info("调用火山API: {}", url);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            log.info("火山API响应状态: {}", response.getStatusCode());

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseResponse(response.getBody());
            } else {
                ImageGenerateResponse errorResponse = new ImageGenerateResponse();
                errorResponse.setStatus("failed");
                errorResponse.setErrorMessage("图片生成失败: " + response.getStatusCode());
                return errorResponse;
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("火山API调用失败: statusCode={}, responseBody={}", e.getStatusCode(), e.getResponseBodyAsString());
            ImageGenerateResponse errorResponse = new ImageGenerateResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage(e.getStatusCode() + ": " + e.getResponseBodyAsString());
            return errorResponse;
        } catch (Exception e) {
            log.error("文生图异常", e);
            ImageGenerateResponse errorResponse = new ImageGenerateResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage(e.getMessage());
            return errorResponse;
        }
    }

    @Override
    public ImageGenerateResponse generateCommerceModelImage(ImageGenerateRequest request) {
        log.info("电商模特文生图: modelName={}, prompt={}", request.getRoleName(), request.getCustomPrompt());

        try {
            String prompt = buildCommerceModelPrompt(request);

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", hasText(commerceModelImageModel) ? commerceModelImageModel.trim() : liteModel);
            requestBody.put("prompt", prompt);
            requestBody.put("size", toCommerceModelImageSize(request.getQuality()));
            requestBody.put("sequential_image_generation", "disabled");
            requestBody.put("output_format", "png");
            requestBody.put("response_format", "url");
            requestBody.put("n", 1);
            requestBody.put("stream", false);
            requestBody.put("watermark", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + resolveCommerceModelImageApiKey());

            HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);
            String url = trimTrailingSlash(hasText(commerceModelImageBaseUrl) ? commerceModelImageBaseUrl : baseUrl) + "/images/generations";

            log.info("调用火山Ark生成电商模特图: {}", url);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseAndPersistGeneratedImage(response.getBody(), "commerce-models", "模特图片保存失败，请稍后重试");
            }
            ImageGenerateResponse errorResponse = new ImageGenerateResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage("图片生成失败: " + response.getStatusCode());
            return errorResponse;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("火山API电商模特图调用失败: statusCode={}, responseBody={}", e.getStatusCode(), e.getResponseBodyAsString());
            ImageGenerateResponse errorResponse = new ImageGenerateResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage(e.getStatusCode() + ": " + e.getResponseBodyAsString());
            return errorResponse;
        } catch (Exception e) {
            log.error("电商模特文生图异常", e);
            ImageGenerateResponse errorResponse = new ImageGenerateResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage(e.getMessage());
            return errorResponse;
        }
    }

    @Override
    public ImageGenerateResponse generateCommerceSceneImage(ImageGenerateRequest request) {
        log.info("电商场景文生图: sceneName={}, prompt={}", request.getRoleName(), request.getCustomPrompt());

        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", hasText(commerceTryOnFirstFrameModel) ? commerceTryOnFirstFrameModel.trim() : liteModel);
            requestBody.put("prompt", request.getCustomPrompt());
            requestBody.put("size", toCommerceTryOnFirstFrameSize(request.getQuality()));
            requestBody.put("sequential_image_generation", "disabled");
            requestBody.put("output_format", "png");
            requestBody.put("response_format", "url");
            requestBody.put("n", 1);
            requestBody.put("stream", false);
            requestBody.put("watermark", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + resolveCommerceModelImageApiKey());

            HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);
            String url = trimTrailingSlash(hasText(commerceModelImageBaseUrl) ? commerceModelImageBaseUrl : baseUrl) + "/images/generations";

            log.info("调用火山Ark生成电商场景图: {}", url);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseAndPersistGeneratedImage(response.getBody(), "commerce/scenes", "场景图片保存失败，请稍后重试");
            }
            ImageGenerateResponse errorResponse = new ImageGenerateResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage("场景图生成失败: " + response.getStatusCode());
            return errorResponse;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("火山API场景图调用失败: statusCode={}, responseBody={}", e.getStatusCode(), e.getResponseBodyAsString());
            ImageGenerateResponse errorResponse = new ImageGenerateResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage(e.getStatusCode() + ": " + e.getResponseBodyAsString());
            return errorResponse;
        } catch (Exception e) {
            log.error("电商场景文生图异常", e);
            ImageGenerateResponse errorResponse = new ImageGenerateResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage(e.getMessage());
            return errorResponse;
        }
    }

    @Override
    public ImageGenerateResponse generateCommerceTryOnFirstFrame(ImageGenerateRequest request) {
        log.info("电商试穿首帧确认图: prompt={}, references={}",
                request.getCustomPrompt(), request.getReferenceImageUrls());

        try {
            if (request.getReferenceImageUrls() == null || request.getReferenceImageUrls().size() < 2) {
                ImageGenerateResponse errorResponse = new ImageGenerateResponse();
                errorResponse.setStatus("failed");
                errorResponse.setErrorMessage("试穿首帧至少需要模特图和商品图");
                return errorResponse;
            }

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", hasText(commerceTryOnFirstFrameModel) ? commerceTryOnFirstFrameModel.trim() : liteModel);
            requestBody.put("prompt", request.getCustomPrompt());
            requestBody.put("image", request.getReferenceImageUrls());
            requestBody.put("size", toCommerceTryOnFirstFrameSize(request.getQuality()));
            requestBody.put("sequential_image_generation", "disabled");
            requestBody.put("output_format", "png");
            requestBody.put("response_format", "url");
            requestBody.put("n", 1);
            requestBody.put("stream", false);
            requestBody.put("watermark", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + resolveCommerceModelImageApiKey());

            HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);
            String url = trimTrailingSlash(hasText(commerceModelImageBaseUrl) ? commerceModelImageBaseUrl : baseUrl) + "/images/generations";

            log.info("调用火山Ark生成电商试穿首帧: {}", url);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseAndPersistGeneratedImage(response.getBody(), "commerce/try-on-first-frames", "试穿首帧图片保存失败，请稍后重试");
            }
            ImageGenerateResponse errorResponse = new ImageGenerateResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage("试穿首帧生成失败: " + response.getStatusCode());
            return errorResponse;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("火山API试穿首帧调用失败: statusCode={}, responseBody={}", e.getStatusCode(), e.getResponseBodyAsString());
            ImageGenerateResponse errorResponse = new ImageGenerateResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage(e.getStatusCode() + ": " + e.getResponseBodyAsString());
            return errorResponse;
        } catch (Exception e) {
            log.error("电商试穿首帧生成异常", e);
            ImageGenerateResponse errorResponse = new ImageGenerateResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage(e.getMessage());
            return errorResponse;
        }
    }

    @Override
    public ImageGenerateResponse generateCharacterSheetWithReference(ImageGenerateRequest request) {
        log.info("========== 图生图开始 ==========");
        log.info("roleName: {}", request.getRoleName());
        log.info("referenceImageUrl: {}", request.getReferenceImageUrl());
        log.info("clothingPrompt: {}", request.getClothingPrompt());
        log.info("================================");

        try {
            String prompt = buildClothingChangePrompt(request);
            String size = convertAspectRatioToSize(request.getAspectRatio(), request.getQuality());

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);
            requestBody.put("prompt", prompt);
            requestBody.put("size", size);
            requestBody.put("response_format", "url");
            requestBody.put("n", 1);
            requestBody.put("stream", false);
            requestBody.put("watermark", false);

            // 添加负向提示词，避免噪点、水波纹、摩尔纹等问题，强调保持肤质
            String negativePrompt = "worst quality, low quality, normal quality, " +
                    "noise, grain, artifacts, distortion, blur, " +
                    "water ripples, moire pattern, weird patterns, strange textures, " +
                    "dots, speckles, spots, halftone, dithering, " +
                    "jpeg artifacts, compression artifacts, pixelated, " +
                    "bad skin texture, rough skin, skin blemishes, uneven skin tone, " +
                    "text, watermark, signature, logo";
            requestBody.put("negative_prompt", negativePrompt);

            // 如果有参考图，添加image参数（图生图）
            if (request.getReferenceImageUrl() != null && !request.getReferenceImageUrl().isEmpty()) {
                requestBody.put("image", request.getReferenceImageUrl());
                // 图生图的强度参数，控制对参考图的改变程度
                // 0.85 在换装场景下能保留人物面部特征，同时有足够自由度改变服装
                requestBody.put("strength", 0.85);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);
            String url = baseUrl + "/images/generations";

            log.info("调用火山API（图生图）: {}", url);
            log.info("请求体: {}", requestBody.toJSONString());

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            log.info("火山API响应状态: {}", response.getStatusCode());

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseResponse(response.getBody());
            } else {
                ImageGenerateResponse errorResponse = new ImageGenerateResponse();
                errorResponse.setStatus("failed");
                errorResponse.setErrorMessage("图片生成失败: " + response.getStatusCode());
                return errorResponse;
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("火山API调用失败: statusCode={}, responseBody={}", e.getStatusCode(), e.getResponseBodyAsString());
            ImageGenerateResponse errorResponse = new ImageGenerateResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage(e.getStatusCode() + ": " + e.getResponseBodyAsString());
            return errorResponse;
        } catch (Exception e) {
            log.error("图生图异常", e);
            ImageGenerateResponse errorResponse = new ImageGenerateResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage(e.getMessage());
            return errorResponse;
        }
    }

    /**
     * 转换图片比例为SDK支持的size格式
     * @param aspectRatio 图片比例
     * @param quality 清晰度 (hd/uhd)
     */
    private String convertAspectRatioToSize(String aspectRatio, String quality) {
        // 支持 3k, 3K, uhd 表示超清
        boolean isUHD = "3k".equalsIgnoreCase(quality) || "uhd".equalsIgnoreCase(quality);

        if (isUHD) {
            // 超清 (3K)
            switch (aspectRatio) {
                case "1:1": return "3072x3072";
                case "3:4": return "2592x3456";
                case "4:3": return "3456x2592";
                case "16:9": return "4096x2304";
                case "9:16": return "2304x4096";
                case "2:3": return "2496x3744";
                case "3:2": return "3744x2496";
                case "21:9": return "4704x2016";
                default: return "3072x3072"; // 默认1:1
            }
        } else {
            // 高清 (2K)
            switch (aspectRatio) {
                case "1:1": return "2048x2048";
                case "3:4": return "1728x2304";
                case "4:3": return "2304x1728";
                case "16:9": return "2848x1600";
                case "9:16": return "1600x2848";
                case "2:3": return "1664x2496";
                case "3:2": return "2496x1664";
                case "21:9": return "3136x1344";
                default: return "2048x2048"; // 默认1:1
            }
        }
    }

    /**
     * 转换图片比例为SDK支持的size格式（兼容旧接口）
     */
    private String convertAspectRatioToSize(String aspectRatio) {
        return convertAspectRatioToSize(aspectRatio, "hd");
    }

    /**
     * 解析响应
     */
    private ImageGenerateResponse parseResponse(String responseBody) {
        JSONObject json = JSON.parseObject(responseBody);
        ImageGenerateResponse response = new ImageGenerateResponse();

        String providerError = extractProviderError(json);
        if (hasText(providerError)) {
            response.setStatus("failed");
            response.setErrorMessage(providerError);
            log.warn("图片生成接口返回错误: {}", providerError);
            return response;
        }

        JSONArray data = json.getJSONArray("data");
        if (data != null && !data.isEmpty()) {
            JSONObject imageData = data.getJSONObject(0);
            String imageUrl = imageData.getString("url");
            if (hasText(imageUrl)) {
                response.setStatus("success");
                response.setImageUrl(imageUrl);
                log.info("图片URL: {}", imageUrl);
                return response;
            }
        }

        response.setStatus("failed");
        response.setErrorMessage(defaultIfBlank(json.getString("message"), "图片生成接口未返回图片地址"));
        log.warn("图片生成接口未返回图片地址: {}", responseBody);
        return response;
    }

    private ImageGenerateResponse parseAndPersistGeneratedImage(String responseBody, String folder, String saveErrorMessage) {
        ImageGenerateResponse response = parseResponse(responseBody);
        if (response == null || response.getImageUrl() == null || response.getImageUrl().trim().isEmpty()) {
            return response;
        }

        String sourceImageUrl = response.getImageUrl();
        String ossUrl = null;
        for (int retry = 0; retry < 3 && (ossUrl == null || ossUrl.trim().isEmpty()); retry++) {
            ossUrl = ossService.uploadImageFromUrl(sourceImageUrl, folder);
            if ((ossUrl == null || ossUrl.trim().isEmpty()) && retry < 2) {
                log.warn("电商模特图OSS上传失败，第{}次重试...", retry + 2);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (ossUrl == null || ossUrl.trim().isEmpty()) {
            ImageGenerateResponse errorResponse = new ImageGenerateResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage(saveErrorMessage);
            return errorResponse;
        }

        response.setImageUrl(ossUrl.trim());
        log.info("生成图片已上传到OSS: {}", ossUrl.substring(0, Math.min(80, ossUrl.length())));
        return response;
    }

    private String extractProviderError(JSONObject json) {
        JSONObject error = json.getJSONObject("error");
        if (error == null) {
            return null;
        }
        String code = error.getString("code");
        String message = error.getString("message");
        if (hasText(code) && hasText(message)) {
            return code.trim() + ": " + message.trim();
        }
        return defaultIfBlank(message, code);
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return hasText(value) ? value.trim() : defaultValue;
    }

    /**
     * 构建角色三视图提示词（文生图）
     */
    private String buildCharacterSheetPrompt(ImageGenerateRequest request) {
        StringBuilder prompt = new StringBuilder();

        // 检查是否指定了外国人/西方人特征
        String customPromptLower = request.getCustomPrompt() != null ? request.getCustomPrompt().toLowerCase() : "";
        boolean hasForeignerHint = customPromptLower.contains("western") ||
                customPromptLower.contains("european") ||
                customPromptLower.contains("american") ||
                customPromptLower.contains("caucasian") ||
                customPromptLower.contains("white person") ||
                customPromptLower.contains("african") ||
                customPromptLower.contains("black person") ||
                customPromptLower.contains("latin") ||
                customPromptLower.contains("middle eastern") ||
                customPromptLower.contains("外国人") ||
                customPromptLower.contains("西方人") ||
                customPromptLower.contains("欧美");

        // 默认添加中国人面孔要求（除非用户指定了外国人）
        String chineseFaceHint = "";
        if (!hasForeignerHint) {
            chineseFaceHint = "MUST be Chinese/Asian appearance, Chinese facial features, East Asian face. DO NOT generate Western or non-Asian faces. ";
        }

        // 判断是否使用大头特写+三视图布局
        boolean useFaceCloseupLayout = Boolean.TRUE.equals(request.getFaceCloseupView());

        // 如果有自定义提示词，优先使用
        if (request.getCustomPrompt() != null && !request.getCustomPrompt().trim().isEmpty()) {
            prompt.append(request.getCustomPrompt());

            // 风格关键词 - 放在用户提示词后面，确保风格生效
            if (request.getStyleKeywords() != null && !request.getStyleKeywords().trim().isEmpty()) {
                prompt.append(". Style: ").append(request.getStyleKeywords()).append(". ");
            }

            // 根据布局选项添加布局要求
            if (useFaceCloseupLayout) {
                // 大头特写+三视图布局
                if (!request.getCustomPrompt().toLowerCase().contains("layout") &&
                    !request.getCustomPrompt().toLowerCase().contains("face close") &&
                    !request.getCustomPrompt().toLowerCase().contains("face closeup")) {
                    prompt.append("STRICT LAYOUT in ONE image: ");
                    prompt.append("LEFT 1/3: enlarged head-and-shoulders character view for identity reference, same art style as the body views. ");
                    prompt.append("RIGHT 2/3: full body three-view showing front view, side view, and back view. ");
                }
                // 中国人面孔要求（如果用户没有指定外国人）
                prompt.append(chineseFaceHint);
                // 关键：强调一致性
                prompt.append("CRITICAL CONSISTENCY: The left identity reference and all three body views MUST show the SAME EXACT character. ");
                prompt.append("SAME identity, SAME clothing color, SAME hair color, SAME skin tone. ");
                prompt.append("All panels MUST share the same selected art style. ");
            } else {
                // 普通三视图布局
                if (!request.getCustomPrompt().toLowerCase().contains("three view") &&
                    !request.getCustomPrompt().toLowerCase().contains("character sheet") &&
                    !request.getCustomPrompt().toLowerCase().contains("front") &&
                    !request.getCustomPrompt().toLowerCase().contains("side") &&
                    !request.getCustomPrompt().toLowerCase().contains("back")) {
                    prompt.append("Character design sheet with three views in ONE image: ");
                    prompt.append("front view (center), side view (left), back view (right). ");
                }
                // 中国人面孔要求（如果用户没有指定外国人）
                prompt.append(chineseFaceHint);
                // 关键：强调颜色一致性
                prompt.append("CRITICAL CONSISTENCY: The SAME EXACT character in all three views. ");
                prompt.append("SAME clothing color, SAME hair color, SAME skin tone, SAME accessories in EVERY view. ");
                prompt.append("The front, side, and back views MUST show the IDENTICAL outfit with IDENTICAL colors. ");
            }

            // 强调完整全身照 - 必须显示脚
            prompt.append("CRITICAL: Full body views MUST show COMPLETE body from head to FEET. ");
            prompt.append("FEET MUST BE VISIBLE in all body views. ");
            prompt.append(CHARACTER_EMPTY_HANDS_CONSTRAINT);
            // 强制白色背景
            prompt.append("MUST have PURE WHITE BACKGROUND (#FFFFFF). NO colored background, NO gradient background. ");
            // 禁止文字
            prompt.append("NO text, NO words, NO letters, NO numbers, NO captions, NO labels, NO watermarks. ");
            return prompt.toString();
        }

        // 默认提示词构建 - 根据布局选项
        if (useFaceCloseupLayout) {
            // 大头特写+三视图布局
            prompt.append("Character design sheet in ONE image with STRICT LAYOUT: ");
            prompt.append("LEFT 1/3 of image: enlarged head-and-shoulders character view for identity reference, same art style as the body views. ");
            prompt.append("RIGHT 2/3 of image: full body three-view showing front view, side view, and back view arranged horizontally. ");

            // 中国人面孔要求（如果用户没有指定外国人）
            prompt.append(chineseFaceHint);

            // 关键：强调一致性
            prompt.append("CRITICAL CONSISTENCY: The left identity reference and all three body views MUST show the SAME EXACT character. ");
            prompt.append("SAME identity, SAME hair, SAME clothing color across all views. ");
            prompt.append("All panels MUST share the same selected art style. ");
        } else {
            // 普通三视图布局
            prompt.append("Character design sheet with three views in ONE image: ");
            prompt.append("front view (center), side view (left), back view (right). ");

            // 中国人面孔要求（如果用户没有指定外国人）
            prompt.append(chineseFaceHint);

            // 关键：强调颜色一致性
            prompt.append("CRITICAL CONSISTENCY: The SAME EXACT character in all three views. ");
            prompt.append("SAME clothing color, SAME hair color, SAME skin tone, SAME accessories in EVERY view. ");
            prompt.append("The front, side, and back views MUST show the IDENTICAL outfit with IDENTICAL colors. ");
        }

        prompt.append("CRITICAL: Full body views show COMPLETE body from head to FEET. ");
        prompt.append("FEET MUST BE FULLY VISIBLE. ");
        prompt.append(CHARACTER_EMPTY_HANDS_CONSTRAINT);
        prompt.append("MUST have PURE WHITE BACKGROUND (#FFFFFF). NO colored background, NO gradient background. ");

        // 角色描述
        prompt.append("Character: ").append(request.getRoleName()).append(". ");
        if (request.getCharacterDescription() != null) {
            prompt.append(request.getCharacterDescription()).append(". ");
        }

        // 风格
        if (request.getStyleKeywords() != null && !request.getStyleKeywords().isEmpty()) {
            prompt.append("Style: ").append(request.getStyleKeywords()).append(". ");
        }

        // 强调要求
        prompt.append("CRITICAL: PURE WHITE BACKGROUND (#FFFFFF) - NO exceptions. ");
        prompt.append("Professional character sheet layout, ");
        prompt.append("consistent style across all views, ");
        prompt.append("A-pose or T-pose standing with FEET CLEARLY VISIBLE. ");

        // 禁止文字
        prompt.append("NO text, NO words, NO letters, NO numbers, NO captions, NO labels, NO watermarks, NO Chinese characters, NO English text.");

        return prompt.toString();
    }

    @Override
    public String buildFinalCharacterSheetPrompt(ImageGenerateRequest request) {
        return buildCharacterSheetPrompt(request);
    }

    private String buildCommerceModelPrompt(ImageGenerateRequest request) {
        StringBuilder prompt = new StringBuilder();
        boolean childModel = isCommerceChildModel(request);
        boolean maleModel = !childModel && isCommerceMaleModel(request);
        if (request.getCustomPrompt() != null && !request.getCustomPrompt().trim().isEmpty()) {
            prompt.append(request.getCustomPrompt().trim()).append("\n\n");
        }

        prompt.append("真人写实电商服装带货模特基础形象图，人物设定板风格，左侧大头特写+右侧三视图布局，纯白背景，干净极简，高级棚拍质感。");
        prompt.append("左侧约1/3为大头特写，清晰展示五官、肤质、发型和神态；右侧约2/3为正面、侧面、背面完整全身三视图，横向排列。");
        prompt.append("大头特写与右侧三视图必须是同一个真人模特，五官、发型、肤色、妆容、身形比例一致。");
        prompt.append("右侧每个视图都是自然站姿、平视镜头，从头顶到脚趾完整入画，显示手臂、手、腿部、脚踝、脚和脚趾，不裁切，脚下保留清晰白色留白。");
        prompt.append("右侧正面五官尽量与左侧大头特写一致，面部自然、正向平视；侧面为标准侧身侧脸，不回头；背面不露正脸五官。");
        prompt.append("模特必须惊艳但真实，骨相优越，五官精致有辨识度，镜头吸引力强，气质高级，不要普通路人感。");
        if (childModel) {
            prompt.append("儿童模特只穿浅色、低干扰、不透的儿童基础打底背心短裤或童装打底服，清爽自然，绝不成人化。");
            prompt.append("儿童打底服不能抢占人物识别，便于后续商品图换装和试穿生成。");
        } else if (maleModel) {
            prompt.append("成人男模特上半身不穿，必须上半身裸露，胸部、肩膀、手臂自然可见；只穿普通浅色四角内裤，简洁纯色、基础男士款，低干扰、贴身，覆盖必要部位，不带品牌和图案。");
            prompt.append("整体服装极简，仅保留这一条四角内裤，不添加其他服装层次、复杂花纹或装饰材质，便于后续商品图换装和试穿生成。");
        } else {
            prompt.append("成人女模特仅穿白色微透蕾丝内衣（white semi-sheer lace underwear），低干扰、贴身，覆盖必要部位，不带品牌和图案。");
            prompt.append("女模内衣不能抢占人物识别，便于后续商品图换装和试穿生成。");
        }
        prompt.append("模特表情自然亲和，适合短视频口播种草和试穿展示，镜头表现真实可信。");
        prompt.append("皮肤、头发、五官真实自然，精致耐看，避免网红过度磨皮、廉价妆容和夸张幻想元素。");
        prompt.append(CHARACTER_EMPTY_HANDS_CONSTRAINT);
        prompt.append("MUST have PURE WHITE BACKGROUND (#FFFFFF). NO colored background, NO gradient background. ");
        prompt.append("CRITICAL: one single person only, no duplicate bodies, no extra limbs, no cropped head, no cropped feet. ");
        prompt.append("NO text, NO words, NO letters, NO numbers, NO captions, NO labels, NO logo, NO watermark, NO brand marks.");

        return prompt.toString();
    }

    private boolean isCommerceChildModel(ImageGenerateRequest request) {
        String styleKeywords = request.getStyleKeywords() == null ? "" : request.getStyleKeywords();
        String customPrompt = request.getCustomPrompt() == null ? "" : request.getCustomPrompt();
        return styleKeywords.contains("儿童") || customPrompt.contains("儿童") || customPrompt.contains("小女孩") || customPrompt.contains("小男孩");
    }

    private boolean isCommerceMaleModel(ImageGenerateRequest request) {
        String styleKeywords = request.getStyleKeywords() == null ? "" : request.getStyleKeywords();
        String customPrompt = request.getCustomPrompt() == null ? "" : request.getCustomPrompt();
        return styleKeywords.contains("男模特")
                || styleKeywords.contains("男模")
                || styleKeywords.contains("男性")
                || styleKeywords.contains("男生")
                || styleKeywords.contains("男士")
                || styleKeywords.contains("帅哥")
                || styleKeywords.contains("老头")
                || styleKeywords.contains("老爷爷")
                || customPrompt.contains("男性")
                || customPrompt.contains("男模")
                || customPrompt.contains("男生")
                || customPrompt.contains("男士")
                || customPrompt.contains("帅哥")
                || customPrompt.contains("老头")
                || customPrompt.contains("老爷爷")
                || customPrompt.contains("男装");
    }

    private String toCommerceModelImageSize(String quality) {
        return "4096x2304";
    }

    private String toCommerceTryOnFirstFrameSize(String resolution) {
        if ("4k".equalsIgnoreCase(resolution)) {
            return "4K";
        }
        if ("3k".equalsIgnoreCase(resolution)) {
            return "3K";
        }
        return "2K";
    }

    private String resolveCommerceModelImageApiKey() {
        return hasText(commerceModelImageApiKey) ? commerceModelImageApiKey.trim() : apiKey;
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * 构建换装提示词（图生图）
     */
    private String buildClothingChangePrompt(ImageGenerateRequest request) {
        StringBuilder prompt = new StringBuilder();

        // 保持人物面部特征和风格，但允许改变服装
        prompt.append("Keep the same person's face, age, gender, body type, ethnicity, and art style from the reference image. ");
        prompt.append("Do NOT change the person's face or body proportions. ");

        // 关键：强调面部一致性
        prompt.append("CRITICAL CONSISTENCY: All views MUST show the SAME character with identical facial features, hair color, and skin tone. ");

        // 强调保持肤质
        prompt.append("IMPORTANT: Maintain smooth skin texture. ");

        // 强调完整全身照 - 必须显示脚
        prompt.append("CRITICAL: Full body views MUST show COMPLETE body from head to FEET. ");
        prompt.append("FEET MUST BE FULLY VISIBLE. ");
        prompt.append(CHARACTER_EMPTY_HANDS_CONSTRAINT);

        // 强制白色背景
        prompt.append("MUST have PURE WHITE BACKGROUND (#FFFFFF). NO colored background, NO gradient background. ");

        // 新服装描述 - 优先使用clothingPrompt，如果没有则使用clothingName
        if (request.getClothingPrompt() != null && !request.getClothingPrompt().trim().isEmpty()) {
            prompt.append("IMPORTANT: Change the outfit to: ").append(request.getClothingPrompt()).append(". ");
            prompt.append("The character MUST wear the new outfit described above, NOT the outfit from the reference image. ");
        } else if (request.getClothingName() != null && !request.getClothingName().trim().isEmpty()) {
            prompt.append("IMPORTANT: Change the outfit to: ").append(request.getClothingName()).append(" style. ");
            prompt.append("The character MUST wear the new outfit described above, NOT the outfit from the reference image. ");
        }

        // 风格关键词
        if (request.getStyleKeywords() != null && !request.getStyleKeywords().trim().isEmpty()) {
            prompt.append("Style: ").append(request.getStyleKeywords()).append(". ");
        }

        // 强调画面纯净度
        prompt.append("CRITICAL QUALITY: Ultra clean, smooth, pristine image with perfect clarity. ");
        prompt.append("NO noise, NO grain, NO artifacts, NO distortion, NO blur. ");
        prompt.append("NO water ripples, NO moire patterns, NO weird patterns, NO strange textures. ");
        prompt.append("NO dots, NO speckles, NO spots, NO halftone, NO dithering effects. ");
        prompt.append("NO grid patterns, NO repeating patterns, NO texture overlays. ");
        prompt.append("Perfectly smooth gradients, even solid colors, professional studio quality. ");

        prompt.append("CRITICAL: PURE WHITE BACKGROUND (#FFFFFF) - NO exceptions. High quality, detailed. ");

        // 禁止文字
        prompt.append("NO text, NO words, NO letters, NO numbers, NO captions, NO labels, NO watermarks, NO Chinese characters, NO English text.");

        return prompt.toString();
    }

    @Override
    @Async("taskExecutor")
    public void generateCharacterAssets(Long roleId) {
        generateCharacterAssets(roleId, null, null, null, null, null);
    }

    @Override
    public void generateCharacterAssets(Long roleId, Integer clothingId) {
        generateCharacterAssets(roleId, clothingId, null, null, null, null);
    }

    @Override
    public void generateCharacterAssets(Long roleId, Integer clothingId, Long generatingAssetId) {
        generateCharacterAssets(roleId, clothingId, generatingAssetId, null, null, null);
    }

    @Override
    @Async("taskExecutor")
    public void generateCharacterAssets(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId) {
        generateCharacterAssets(roleId, clothingId, generatingAssetId, previousActiveAssetId, null, null);
    }

    @Override
    @Async("taskExecutor")
    public void generateCharacterAssets(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String aspectRatio, String quality) {
        generateCharacterAssets(roleId, clothingId, generatingAssetId, previousActiveAssetId, aspectRatio, quality, null);
    }

    @Override
    @Async("taskExecutor")
    public void generateCharacterAssets(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String aspectRatio, String quality, String styleKeywords) {
        generateCharacterAssets(roleId, clothingId, generatingAssetId, previousActiveAssetId, aspectRatio, quality, styleKeywords, null);
    }

    @Override
    @Async("taskExecutor")
    public void generateCharacterAssets(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String aspectRatio, String quality, String styleKeywords, String originalPrompt) {
        generateCharacterAssets(roleId, clothingId, generatingAssetId, previousActiveAssetId, aspectRatio, quality, styleKeywords, originalPrompt, null, null);
    }

    @Override
    @Async("taskExecutor")
    public void generateCharacterAssets(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String aspectRatio, String quality, String styleKeywords, String originalPrompt, Boolean detailedView) {
        generateCharacterAssets(roleId, clothingId, generatingAssetId, previousActiveAssetId, aspectRatio, quality, styleKeywords, originalPrompt, detailedView, null);
    }

    @Override
    @Async("taskExecutor")
    public void generateCharacterAssets(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String aspectRatio, String quality, String styleKeywords, String originalPrompt, Boolean detailedView, Boolean faceCloseupView) {
        generateCharacterAssets(roleId, clothingId, generatingAssetId, previousActiveAssetId, aspectRatio, quality, null, styleKeywords, originalPrompt, detailedView, faceCloseupView);
    }

    @Override
    @Async("taskExecutor")
    public void generateCharacterAssets(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String aspectRatio, String quality, String styleKey, String styleKeywords, String originalPrompt, Boolean detailedView, Boolean faceCloseupView) {
        log.info("异步生成角色资产: roleId={}, clothingId={}, previousActiveAssetId={}, aspectRatio={}, quality={}, styleKeywords={}, originalPrompt={}, detailedView={}, faceCloseupView={}", roleId, clothingId, previousActiveAssetId, aspectRatio, quality, styleKeywords, originalPrompt, detailedView, faceCloseupView);

        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            log.error("角色不存在: roleId={}", roleId);
            return;
        }

        Series series = seriesMapper.selectById(role.getSeriesId());

        // 获取已有服装的名称（用于重新生成时保持名称）
        String clothingName = null;
        if (clothingId != null && clothingId > 1) {
            LambdaQueryWrapper<RoleAsset> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(RoleAsset::getRoleId, roleId)
                    .eq(RoleAsset::getClothingId, clothingId)
                    .select(RoleAsset::getClothingName)
                    .last("LIMIT 1");
            RoleAsset existingAsset = roleAssetMapper.selectOne(wrapper);
            if (existingAsset != null) {
                clothingName = existingAsset.getClothingName();
            }
        }

        // 确定服装编号
        Integer finalClothingId = clothingId;
        if (finalClothingId == null || finalClothingId <= 0) {
            finalClothingId = assetService.getNextClothingId(roleId);
        }

        // 创建生成中的资产记录（用于追踪状态），如果还没创建的话
        if (generatingAssetId == null) {
            long[] result = createGeneratingAssetInternal(role, finalClothingId, clothingName, null);
            generatingAssetId = result[0];
            if (previousActiveAssetId == null) {
                previousActiveAssetId = result[1] > 0 ? result[1] : null;
            }
        }

        // 构建角色描述
        StringBuilder description = new StringBuilder();
        if (role.getAge() != null) description.append(role.getAge()).append(", ");
        if (role.getGender() != null) description.append(role.getGender()).append(", ");
        if (role.getAppearance() != null) description.append(role.getAppearance()).append(", ");
        if (role.getClothing() != null) description.append("wearing ").append(role.getClothing());

        // 构建请求 - 优先使用传入的参数，否则使用系列默认值
        String requestAspectRatio = aspectRatio != null ? aspectRatio :
                (series != null && series.getAspectRatio() != null ? series.getAspectRatio() : "3:4");
        String requestQuality = quality != null ? quality :
                (series != null && series.getQuality() != null ? series.getQuality() : "hd");
        String requestStyleKeywords = styleKeywords != null && !styleKeywords.trim().isEmpty() ? styleKeywords :
                (role.getStyleKeywords() != null ? role.getStyleKeywords() : "");

        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .roleName(role.getRoleName())
                .characterDescription(description.toString())
                .aspectRatio(requestAspectRatio)
                .quality(requestQuality)
                .styleKey(styleKey != null && !styleKey.trim().isEmpty() ? styleKey : role.getStyleKey())
                .styleKeywords(requestStyleKeywords)
                .seriesId(role.getSeriesId())
                .roleId(roleId)
                .customPrompt(role.getCustomPrompt())
                .originalPrompt(originalPrompt)
                .detailedView(detailedView)
                .faceCloseupView(faceCloseupView)
                .clothingId(finalClothingId)
                .clothingName(clothingName)
                .build();

        // 调用文生图生成
        ImageGenerateResponse response = generateCharacterSheet(request);

        if ("success".equals(response.getStatus())) {
            saveAsset(role, response, request, generatingAssetId);

            // 更新角色状态为待审核（只有当角色状态是生成中时才更新）
            if (role.getStatus().equals(RoleStatus.EXTRACTING.getCode())) {
                role.setStatus(RoleStatus.PENDING_REVIEW.getCode());
                role.setUpdatedAt(LocalDateTime.now());
                roleMapper.updateById(role);
            }
            log.info("角色资产生成完成: roleId={}", roleId);
        } else {
            log.error("生成失败: {}", response.getErrorMessage());
            // 使用事务确保失败处理的原子性
            final Long finalGeneratingAssetId = generatingAssetId;
            final Long finalPreviousActiveAssetId = previousActiveAssetId;
            final Integer finalClothingIdFinal = finalClothingId;
            final Role finalRole = role;
            final ImageGenerateRequest finalRequest = request;

            transactionTemplate.executeWithoutResult(status -> {
                // 将生成中的资产状态改为失败，并保存错误信息
                if (finalGeneratingAssetId != null) {
                    RoleAsset failedAsset = roleAssetMapper.selectById(finalGeneratingAssetId);
                    if (failedAsset != null) {
                        // 判断是否是新服装的第一个版本
                        boolean isFirstVersion = failedAsset.getVersion() != null && failedAsset.getVersion() == 1;

                        failedAsset.setStatus(AssetStatus.FAILED.getCode());
                        failedAsset.setValidationResult(response.getErrorMessage());
                        // 新服装的第一个版本失败时，保持 isActive=1，让用户能看到并重新生成
                        // 非第一个版本失败时，设为 isActive=0，后续会恢复之前的版本
                        failedAsset.setIsActive(isFirstVersion ? 1 : 0);
                        failedAsset.setUpdatedAt(LocalDateTime.now());
                        roleAssetMapper.updateById(failedAsset);
                        log.info("已标记资产为失败状态: assetId={}, isFirstVersion={}, isActive={}",
                                finalGeneratingAssetId, isFirstVersion, failedAsset.getIsActive());

                        // 保存失败资产的提示词元数据
                        saveFailedAssetMetadata(failedAsset.getId(), finalRequest);

                        // 只有非第一个版本才恢复之前的版本
                        if (!isFirstVersion) {
                            restorePreviousActiveAsset(finalRole.getId(), finalClothingIdFinal, finalPreviousActiveAssetId);
                        }
                    }
                }
                if (finalRole.getStatus().equals(RoleStatus.EXTRACTING.getCode())) {
                    finalRole.setStatus(RoleStatus.PENDING_REVIEW.getCode());
                    finalRole.setUpdatedAt(LocalDateTime.now());
                    roleMapper.updateById(finalRole);
                }
            });
        }
    }

    /**
     * 恢复之前的版本为激活状态（无指定ID时自动查找最新有效版本）
     */
    private void restorePreviousActiveAsset(Long roleId, Integer clothingId) {
        restorePreviousActiveAsset(roleId, clothingId, null);
    }

    /**
     * 恢复之前的版本为激活状态
     * @param previousActiveAssetId 之前激活的资产ID，如果为null则自动查找
     */
    private void restorePreviousActiveAsset(Long roleId, Integer clothingId, Long previousActiveAssetId) {
        log.info("开始恢复之前的激活版本: roleId={}, clothingId={}, previousActiveAssetId={}", roleId, clothingId, previousActiveAssetId);

        RoleAsset previousAsset = null;

        // 优先使用传入的 previousActiveAssetId
        if (previousActiveAssetId != null && previousActiveAssetId > 0) {
            previousAsset = roleAssetMapper.selectById(previousActiveAssetId);
            if (previousAsset != null && (previousAsset.getStatus() == AssetStatus.FAILED.getCode()
                    || previousAsset.getStatus() == AssetStatus.GENERATING.getCode())) {
                log.warn("保存的 previousActiveAssetId 对应的资产状态无效，将重新查找");
                previousAsset = null;
            }
        }

        // 如果没有有效的 previousActiveAssetId，自动查找最新的有效版本
        if (previousAsset == null) {
            LambdaQueryWrapper<RoleAsset> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(RoleAsset::getRoleId, roleId)
                    .eq(RoleAsset::getClothingId, clothingId)
                    .ne(RoleAsset::getStatus, AssetStatus.FAILED.getCode())
                    .ne(RoleAsset::getStatus, AssetStatus.GENERATING.getCode())
                    .orderByDesc(RoleAsset::getVersion)
                    .last("LIMIT 1");
            previousAsset = roleAssetMapper.selectOne(wrapper);
        }

        if (previousAsset != null) {
            previousAsset.setIsActive(1);
            previousAsset.setUpdatedAt(LocalDateTime.now());
            roleAssetMapper.updateById(previousAsset);
            log.info("已恢复之前版本为激活状态: assetId={}, version={}, status={}",
                    previousAsset.getId(), previousAsset.getVersion(), previousAsset.getStatus());
            // 不再更新 role.customPrompt，因为每个服装版本的提示词独立存储在 RoleAsset.clothingPrompt 中
        } else {
            log.warn("未找到可恢复的版本: roleId={}, clothingId={}", roleId, clothingId);

            // 查询所有版本进行调试
            LambdaQueryWrapper<RoleAsset> debugWrapper = new LambdaQueryWrapper<>();
            debugWrapper.eq(RoleAsset::getRoleId, roleId)
                    .eq(RoleAsset::getClothingId, clothingId)
                    .orderByDesc(RoleAsset::getVersion);
            List<RoleAsset> allVersions = roleAssetMapper.selectList(debugWrapper);
            for (RoleAsset asset : allVersions) {
                log.warn("  - version={}, status={}, isActive={}", asset.getVersion(), asset.getStatus(), asset.getIsActive());
            }
        }
    }

    @Override
    @Async("taskExecutor")
    public void generateNewClothingWithReference(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String referenceImageUrl, String clothingPrompt, String clothingName) {
        generateNewClothingWithReference(roleId, clothingId, generatingAssetId, previousActiveAssetId, referenceImageUrl, clothingPrompt, clothingName, null, null);
    }

    @Override
    @Async("taskExecutor")
    public void generateNewClothingWithReference(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String referenceImageUrl, String clothingPrompt, String clothingName, String aspectRatio, String quality) {
        generateNewClothingWithReference(roleId, clothingId, generatingAssetId, previousActiveAssetId, referenceImageUrl, clothingPrompt, clothingName, aspectRatio, quality, null);
    }

    @Override
    @Async("taskExecutor")
    public void generateNewClothingWithReference(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String referenceImageUrl, String clothingPrompt, String clothingName, String aspectRatio, String quality, String styleKeywords) {
        log.info("异步生成新服装（图生图）: roleId={}, clothingId={}, referenceUrl={}, clothingName={}, aspectRatio={}, quality={}, styleKeywords={}", roleId, clothingId, referenceImageUrl, clothingName, aspectRatio, quality, styleKeywords);

        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            log.error("角色不存在: roleId={}", roleId);
            return;
        }

        Series series = seriesMapper.selectById(role.getSeriesId());

        // 如果没有传入 clothingId，获取下一个服装编号
        Integer finalClothingId = clothingId;
        if (finalClothingId == null) {
            finalClothingId = assetService.getNextClothingId(roleId);
        }

        // 如果没有传入 generatingAssetId，创建生成中的资产记录
        if (generatingAssetId == null) {
            long[] result = createGeneratingAssetInternal(role, finalClothingId, clothingName, null);
            generatingAssetId = result[0];
            if (previousActiveAssetId == null) {
                previousActiveAssetId = result[1] > 0 ? result[1] : null;
            }
        }

        // 构建请求 - 优先使用传入的参数，否则使用系列默认值
        String requestAspectRatio = aspectRatio != null ? aspectRatio :
                (series != null && series.getAspectRatio() != null ? series.getAspectRatio() : "3:4");
        String requestQuality = quality != null ? quality :
                (series != null && series.getQuality() != null ? series.getQuality() : "hd");
        String requestStyleKeywords = styleKeywords != null && !styleKeywords.trim().isEmpty() ? styleKeywords :
                (role.getStyleKeywords() != null ? role.getStyleKeywords() : "");

        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .roleName(role.getRoleName())
                .aspectRatio(requestAspectRatio)
                .quality(requestQuality)
                .styleKeywords(requestStyleKeywords)
                .seriesId(role.getSeriesId())
                .roleId(roleId)
                .referenceImageUrl(referenceImageUrl)
                .clothingPrompt(clothingPrompt)
                .clothingName(clothingName)
                .clothingId(finalClothingId)
                .build();

        // 调用图生图生成
        ImageGenerateResponse response = generateCharacterSheetWithReference(request);

        if ("success".equals(response.getStatus())) {
            saveAsset(role, response, request, generatingAssetId);
            log.info("新服装生成完成: roleId={}, clothingId={}, clothingName={}", roleId, finalClothingId, clothingName);
        } else {
            log.error("新服装生成失败: {}", response.getErrorMessage());
            // 使用事务确保失败处理的原子性
            final Long finalGeneratingAssetId = generatingAssetId;
            final Long finalPreviousActiveAssetId = previousActiveAssetId;
            final Integer finalClothingIdFinal = finalClothingId;
            final Role finalRole = role;
            final ImageGenerateRequest finalRequest = request;

            transactionTemplate.executeWithoutResult(status -> {
                // 将生成中的资产状态改为失败，并保存错误信息
                if (finalGeneratingAssetId != null) {
                    RoleAsset failedAsset = roleAssetMapper.selectById(finalGeneratingAssetId);
                    if (failedAsset != null) {
                        // 判断是否是新服装的第一个版本
                        boolean isFirstVersion = failedAsset.getVersion() != null && failedAsset.getVersion() == 1;

                        failedAsset.setStatus(AssetStatus.FAILED.getCode());
                        failedAsset.setValidationResult(response.getErrorMessage());
                        // 新服装的第一个版本失败时，保持 isActive=1，让用户能看到并重新生成
                        // 非第一个版本失败时，设为 isActive=0，后续会恢复之前的版本
                        failedAsset.setIsActive(isFirstVersion ? 1 : 0);
                        failedAsset.setUpdatedAt(LocalDateTime.now());
                        roleAssetMapper.updateById(failedAsset);
                        log.info("已标记资产为失败状态: assetId={}, isFirstVersion={}, isActive={}",
                                finalGeneratingAssetId, isFirstVersion, failedAsset.getIsActive());

                        // 保存失败资产的提示词元数据
                        saveFailedAssetMetadata(failedAsset.getId(), finalRequest);

                        // 只有非第一个版本才恢复之前的版本
                        if (!isFirstVersion) {
                            restorePreviousActiveAsset(finalRole.getId(), finalClothingIdFinal, finalPreviousActiveAssetId);
                        }
                    }
                }
            });
        }
    }

    @Override
    @Async("taskExecutor")
    public void generateNewClothingWithReference(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String referenceImageUrl, String clothingPrompt, String clothingName, String aspectRatio, String quality, String styleKeywords, String originalPrompt) {
        generateNewClothingWithReference(roleId, clothingId, generatingAssetId, previousActiveAssetId, referenceImageUrl, clothingPrompt, clothingName, aspectRatio, quality, styleKeywords, originalPrompt, null);
    }

    @Override
    @Async("taskExecutor")
    public void generateNewClothingWithReference(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String referenceImageUrl, String clothingPrompt, String clothingName, String aspectRatio, String quality, String styleKeywords, String originalPrompt, Boolean detailedView) {
        generateNewClothingWithReference(roleId, clothingId, generatingAssetId, previousActiveAssetId, referenceImageUrl, clothingPrompt, clothingName, aspectRatio, quality, null, styleKeywords, originalPrompt, detailedView);
    }

    @Override
    @Async("taskExecutor")
    public void generateNewClothingWithReference(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId, String referenceImageUrl, String clothingPrompt, String clothingName, String aspectRatio, String quality, String styleKey, String styleKeywords, String originalPrompt, Boolean detailedView) {
        log.info("异步生成新服装（图生图）: roleId={}, clothingId={}, referenceUrl={}, clothingName={}, aspectRatio={}, quality={}, styleKey={}, styleKeywords={}, originalPrompt={}, detailedView={}", roleId, clothingId, referenceImageUrl, clothingName, aspectRatio, quality, styleKey, styleKeywords, originalPrompt, detailedView);

        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            log.error("角色不存在: roleId={}", roleId);
            return;
        }

        Series series = seriesMapper.selectById(role.getSeriesId());

        // 如果没有传入 clothingId，获取下一个服装编号
        Integer finalClothingId = clothingId;
        if (finalClothingId == null) {
            finalClothingId = assetService.getNextClothingId(roleId);
        }

        // 如果没有传入 generatingAssetId，创建生成中的资产记录
        if (generatingAssetId == null) {
            long[] result = createGeneratingAssetInternal(role, finalClothingId, clothingName, null);
            generatingAssetId = result[0];
            if (previousActiveAssetId == null) {
                previousActiveAssetId = result[1] > 0 ? result[1] : null;
            }
        }

        // 构建请求 - 优先使用传入的参数，否则使用系列默认值
        String requestAspectRatio = aspectRatio != null ? aspectRatio :
                (series != null && series.getAspectRatio() != null ? series.getAspectRatio() : "3:4");
        String requestQuality = quality != null ? quality :
                (series != null && series.getQuality() != null ? series.getQuality() : "hd");
        String requestStyleKeywords = styleKeywords != null && !styleKeywords.trim().isEmpty() ? styleKeywords :
                (role.getStyleKeywords() != null ? role.getStyleKeywords() : "");

        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .roleName(role.getRoleName())
                .aspectRatio(requestAspectRatio)
                .quality(requestQuality)
                .styleKey(styleKey != null && !styleKey.trim().isEmpty() ? styleKey : role.getStyleKey())
                .styleKeywords(requestStyleKeywords)
                .seriesId(role.getSeriesId())
                .roleId(roleId)
                .referenceImageUrl(referenceImageUrl)
                .clothingPrompt(clothingPrompt)
                .originalPrompt(originalPrompt)
                .detailedView(detailedView)
                .clothingName(clothingName)
                .clothingId(finalClothingId)
                .build();

        // 调用图生图生成
        ImageGenerateResponse response = generateCharacterSheetWithReference(request);

        if ("success".equals(response.getStatus())) {
            saveAsset(role, response, request, generatingAssetId);
            log.info("新服装生成完成: roleId={}, clothingId={}, clothingName={}", roleId, finalClothingId, clothingName);
        } else {
            log.error("新服装生成失败: {}", response.getErrorMessage());
            // 使用事务确保失败处理的原子性
            final Long finalGeneratingAssetId = generatingAssetId;
            final Long finalPreviousActiveAssetId = previousActiveAssetId;
            final Integer finalClothingIdFinal = finalClothingId;
            final Role finalRole = role;
            final ImageGenerateRequest finalRequest = request;

            transactionTemplate.executeWithoutResult(status -> {
                // 将生成中的资产状态改为失败，并保存错误信息
                if (finalGeneratingAssetId != null) {
                    RoleAsset failedAsset = roleAssetMapper.selectById(finalGeneratingAssetId);
                    if (failedAsset != null) {
                        // 判断是否是新服装的第一个版本
                        boolean isFirstVersion = failedAsset.getVersion() != null && failedAsset.getVersion() == 1;

                        failedAsset.setStatus(AssetStatus.FAILED.getCode());
                        failedAsset.setValidationResult(response.getErrorMessage());
                        // 新服装的第一个版本失败时，保持 isActive=1，让用户能看到并重新生成
                        // 非第一个版本失败时，设为 isActive=0，后续会恢复之前的版本
                        failedAsset.setIsActive(isFirstVersion ? 1 : 0);
                        failedAsset.setUpdatedAt(LocalDateTime.now());
                        roleAssetMapper.updateById(failedAsset);
                        log.info("已标记资产为失败状态: assetId={}, isFirstVersion={}, isActive={}",
                                finalGeneratingAssetId, isFirstVersion, failedAsset.getIsActive());

                        // 保存失败资产的提示词元数据
                        saveFailedAssetMetadata(failedAsset.getId(), finalRequest);

                        // 只有非第一个版本才恢复之前的版本
                        if (!isFirstVersion) {
                            restorePreviousActiveAsset(finalRole.getId(), finalClothingIdFinal, finalPreviousActiveAssetId);
                        }
                    }
                }
            });
        }
    }

    /**
     * 保存失败资产的元数据（提示词）
     */
    private void saveFailedAssetMetadata(Long assetId, ImageGenerateRequest request) {
        try {
            // 检查是否已存在元数据
            LambdaQueryWrapper<AssetMetadata> existWrapper = new LambdaQueryWrapper<>();
            existWrapper.eq(AssetMetadata::getAssetId, assetId);
            AssetMetadata existMetadata = assetMetadataMapper.selectOne(existWrapper);

            if (existMetadata != null) {
                // 已存在，更新提示词（优先使用originalPrompt）
                String userPrompt = request.getOriginalPrompt();
                if (userPrompt == null || userPrompt.trim().isEmpty()) {
                    userPrompt = request.getClothingPrompt() != null ? request.getClothingPrompt() : request.getCustomPrompt();
                }
                existMetadata.setUserPrompt(userPrompt);
                existMetadata.setPrompt(buildFinalCharacterSheetPrompt(request));
                existMetadata.setAspectRatio(request.getAspectRatio());
                existMetadata.setQuality(request.getQuality());
                existMetadata.setStyleKey(request.getStyleKey());
                existMetadata.setStyleKeywords(request.getStyleKeywords());
                existMetadata.setDetailedView(request.getDetailedView());
                existMetadata.setFaceCloseupView(request.getFaceCloseupView());
                assetMetadataMapper.updateById(existMetadata);
                log.info("更新失败资产的元数据: assetId={}", assetId);
            } else {
                // 不存在，创建新的元数据（优先使用originalPrompt）
                String userPrompt = request.getOriginalPrompt();
                if (userPrompt == null || userPrompt.trim().isEmpty()) {
                    userPrompt = request.getClothingPrompt() != null ? request.getClothingPrompt() : request.getCustomPrompt();
                }
                AssetMetadata metadata = new AssetMetadata();
                metadata.setAssetId(assetId);
                metadata.setPrompt(buildFinalCharacterSheetPrompt(request));
                metadata.setUserPrompt(userPrompt);
                metadata.setAspectRatio(request.getAspectRatio());
                metadata.setQuality(request.getQuality());
                metadata.setStyleKey(request.getStyleKey());
                metadata.setStyleKeywords(request.getStyleKeywords());
                metadata.setDetailedView(request.getDetailedView());
                metadata.setFaceCloseupView(request.getFaceCloseupView());
                metadata.setCreatedAt(LocalDateTime.now());
                assetMetadataMapper.insert(metadata);
                log.info("保存失败资产的元数据: assetId={}, prompt={}", assetId, userPrompt);
            }
        } catch (Exception e) {
            log.error("保存失败资产元数据出错: assetId={}", assetId, e);
        }
    }

    /**
     * 创建生成中的资产记录（公开方法，供外部调用）
     * 返回格式: [assetId, previousActiveAssetId]
     */
    @Override
    public long[] createGeneratingAsset(Long roleId, Integer clothingId, String clothingName) {
        return createGeneratingAsset(roleId, clothingId, clothingName, null);
    }

    @Override
    public long[] createGeneratingAsset(Long roleId, Integer clothingId, String clothingName, String clothingPrompt) {
        log.info("createGeneratingAsset 开始: roleId={}, clothingId={}, clothingName={}, clothingPrompt={}", roleId, clothingId, clothingName, clothingPrompt != null ? "provided" : "null");

        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }

        // 如果没有传入服装名称，尝试获取已有服装的名称
        if (clothingName == null || clothingName.trim().isEmpty()) {
            if (clothingId != null && clothingId > 1) {
                LambdaQueryWrapper<RoleAsset> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(RoleAsset::getRoleId, roleId)
                        .eq(RoleAsset::getClothingId, clothingId)
                        .select(RoleAsset::getClothingName)
                        .last("LIMIT 1");
                RoleAsset existingAsset = roleAssetMapper.selectOne(wrapper);
                if (existingAsset != null) {
                    clothingName = existingAsset.getClothingName();
                }
            }
        }

        long[] result = createGeneratingAssetInternal(role, clothingId, clothingName, clothingPrompt);
        log.info("createGeneratingAsset 完成: assetId={}, previousActiveAssetId={}", result[0], result[1]);
        return result;
    }

    /**
     * 获取下一个版本号（公开方法）
     */
    @Override
    public Integer getNextVersion(Long roleId, Integer clothingId) {
        return getNextVersionInternal(roleId, clothingId);
    }

    /**
     * 创建生成中的资产记录（内部方法）
     * 如果存在失败的版本，复用该版本而不是创建新版本
     * @return [assetId, previousActiveAssetId]
     */
    private long[] createGeneratingAssetInternal(Role role, Integer clothingId, String clothingName, String clothingPrompt) {
        log.info("开始创建生成中资产: roleId={}, clothingId={}, clothingPrompt={}", role.getId(), clothingId, clothingPrompt != null ? "provided" : "null");

        // 先获取当前激活的资产ID（用于失败时恢复）
        Long previousActiveAssetId = null;
        LambdaQueryWrapper<RoleAsset> activeWrapper = new LambdaQueryWrapper<>();
        activeWrapper.eq(RoleAsset::getRoleId, role.getId())
                .eq(RoleAsset::getClothingId, clothingId)
                .eq(RoleAsset::getIsActive, 1)
                .last("LIMIT 1");
        RoleAsset previousActiveAsset = roleAssetMapper.selectOne(activeWrapper);
        if (previousActiveAsset != null) {
            previousActiveAssetId = previousActiveAsset.getId();
            log.info("保存之前激活的资产ID: {}", previousActiveAssetId);
        }

        // 将该服装的所有旧版本设为非激活
        LambdaUpdateWrapper<RoleAsset> deactivateWrapper = new LambdaUpdateWrapper<>();
        deactivateWrapper.eq(RoleAsset::getRoleId, role.getId())
                .eq(RoleAsset::getClothingId, clothingId)
                .set(RoleAsset::getIsActive, 0);
        roleAssetMapper.update(null, deactivateWrapper);

        // 检查是否存在失败的版本，如果存在则复用
        LambdaQueryWrapper<RoleAsset> failedWrapper = new LambdaQueryWrapper<>();
        failedWrapper.eq(RoleAsset::getRoleId, role.getId())
                .eq(RoleAsset::getClothingId, clothingId)
                .eq(RoleAsset::getStatus, AssetStatus.FAILED.getCode())
                .orderByDesc(RoleAsset::getVersion)
                .last("LIMIT 1");
        RoleAsset failedAsset = roleAssetMapper.selectOne(failedWrapper);

        RoleAsset asset;
        if (failedAsset != null) {
            // 复用失败的版本
            log.info("复用失败的版本: assetId={}, version={}", failedAsset.getId(), failedAsset.getVersion());
            failedAsset.setStatus(AssetStatus.GENERATING.getCode());
            failedAsset.setIsActive(1);
            failedAsset.setValidationResult(null); // 清除之前的错误信息
            // 清除旧的文件路径（生成完成后会有新路径）
            failedAsset.setFilePath(null);
            failedAsset.setThumbnailPath(null);
            failedAsset.setTransparentPath(null);
            // 更新服装名称（如果有新名称）
            if (clothingName != null && !clothingName.trim().isEmpty()) {
                failedAsset.setClothingName(clothingName);
            }
            // 更新服装专属提示词
            if (clothingPrompt != null && !clothingPrompt.trim().isEmpty()) {
                failedAsset.setClothingPrompt(clothingPrompt);
            }
            failedAsset.setUpdatedAt(LocalDateTime.now());
            roleAssetMapper.updateById(failedAsset);
            asset = failedAsset;

            // 更新提示词元数据（优先使用服装专属提示词，其次使用用户原始提示词）
            String newPrompt = resolveDisplayPrompt(clothingPrompt, role);
            if (newPrompt != null && !newPrompt.trim().isEmpty()) {
                LambdaQueryWrapper<AssetMetadata> metadataWrapper = new LambdaQueryWrapper<>();
                metadataWrapper.eq(AssetMetadata::getAssetId, failedAsset.getId());
                AssetMetadata existMetadata = assetMetadataMapper.selectOne(metadataWrapper);
                if (existMetadata != null) {
                    existMetadata.setUserPrompt(newPrompt);
                    existMetadata.setPrompt(newPrompt);
                    assetMetadataMapper.updateById(existMetadata);
                    log.info("更新复用资产的提示词: assetId={}, prompt={}", failedAsset.getId(), newPrompt);
                } else {
                    AssetMetadata newMetadata = new AssetMetadata();
                    newMetadata.setAssetId(failedAsset.getId());
                    newMetadata.setPrompt(newPrompt);
                    newMetadata.setUserPrompt(newPrompt);
                    newMetadata.setCreatedAt(LocalDateTime.now());
                    assetMetadataMapper.insert(newMetadata);
                    log.info("创建复用资产的提示词: assetId={}, prompt={}", failedAsset.getId(), newPrompt);
                }
            }
        } else {
            // 没有失败版本，创建新版本
            Integer version = getNextVersionInternal(role.getId(), clothingId);
            log.info("创建新版本: version={}", version);

            asset = new RoleAsset();
            asset.setRoleId(role.getId());
            asset.setAssetType("CHARACTER_SHEET");
            asset.setViewType("ALL");
            asset.setClothingId(clothingId);
            asset.setClothingName(clothingId == 1 ? "默认" : clothingName);
            asset.setClothingPrompt(clothingPrompt);
            asset.setVersion(version);
            asset.setFileName(role.getRoleName() + "_C" + String.format("%02d", clothingId) + "_V" + String.format("%02d", version) + ".png");
            asset.setStatus(AssetStatus.GENERATING.getCode()); // 生成中状态
            asset.setIsActive(1);
            asset.setValidationPassed(0);
            asset.setCreatedAt(LocalDateTime.now());
            asset.setUpdatedAt(LocalDateTime.now());

            roleAssetMapper.insert(asset);

            // 创建新资产时，立即保存展示用提示词，不保存系统附加模板
            String newAssetPrompt = resolveDisplayPrompt(clothingPrompt, role);
            if (newAssetPrompt != null && !newAssetPrompt.trim().isEmpty()) {
                AssetMetadata newMetadata = new AssetMetadata();
                newMetadata.setAssetId(asset.getId());
                newMetadata.setPrompt(newAssetPrompt);
                newMetadata.setUserPrompt(newAssetPrompt);
                newMetadata.setCreatedAt(LocalDateTime.now());
                assetMetadataMapper.insert(newMetadata);
                log.info("创建新资产的提示词元数据: assetId={}, prompt={}", asset.getId(), newAssetPrompt);
            }
        }

        log.info("生成中资产准备完成: assetId={}, roleId={}, clothingId={}, version={}",
                asset.getId(), role.getId(), clothingId, asset.getVersion());
        return new long[]{asset.getId(), previousActiveAssetId != null ? previousActiveAssetId : 0};
    }

    private String resolveDisplayPrompt(String clothingPrompt, Role role) {
        if (clothingPrompt != null && !clothingPrompt.trim().isEmpty()) {
            return clothingPrompt;
        }
        if (role != null && role.getOriginalPrompt() != null && !role.getOriginalPrompt().trim().isEmpty()) {
            return role.getOriginalPrompt();
        }
        return role != null ? role.getCustomPrompt() : null;
    }

    /**
     * 保存资产
     */
    private Long saveAsset(Role role, ImageGenerateResponse response, ImageGenerateRequest request, Long generatingAssetId) {
        try {
            // 确定服装编号
            Integer clothingId = request.getClothingId();
            if (clothingId == null || clothingId <= 0) {
                clothingId = assetService.getNextClothingId(role.getId());
            }

            // 如果是新服装，将该角色的其他服装设为非默认
            boolean isNewClothing;

            RoleAsset asset;
            if (generatingAssetId != null) {
                // 更新已存在的生成中记录
                asset = roleAssetMapper.selectById(generatingAssetId);
                if (asset == null) {
                    log.error("找不到生成中的资产记录: {}", generatingAssetId);
                    return null;
                }
                isNewClothing = asset.getVersion() == 1;
            } else {
                // 创建新资产记录（兼容旧逻辑）
                Integer version = getNextVersionInternal(role.getId(), clothingId);
                isNewClothing = version == 1;

                // 将该服装的所有旧版本设为非激活
                if (!isNewClothing) {
                    LambdaUpdateWrapper<RoleAsset> deactivateWrapper = new LambdaUpdateWrapper<>();
                    deactivateWrapper.eq(RoleAsset::getRoleId, role.getId())
                            .eq(RoleAsset::getClothingId, clothingId)
                            .set(RoleAsset::getIsActive, 0);
                    roleAssetMapper.update(null, deactivateWrapper);
                }

                asset = new RoleAsset();
                asset.setRoleId(role.getId());
                asset.setAssetType("CHARACTER_SHEET");
                asset.setViewType("ALL");
                asset.setClothingId(clothingId);
                asset.setClothingName(clothingId == 1 ? "默认" : request.getClothingName());
                asset.setVersion(version);
                asset.setFileName(role.getRoleName() + "_C" + String.format("%02d", clothingId) + "_V" + String.format("%02d", version) + ".png");
                asset.setIsActive(1);
                asset.setValidationPassed(1);
                asset.setCreatedAt(LocalDateTime.now());
            }

            // 更新资产状态和图片URL
            asset.setStatus(AssetStatus.PENDING_REVIEW.getCode());
            asset.setValidationPassed(1);
            asset.setUpdatedAt(LocalDateTime.now());

            // 设置服装专属提示词（优先使用clothingPrompt，其次originalPrompt，再次customPrompt）
            String assetClothingPrompt = request.getClothingPrompt();
            if (assetClothingPrompt == null || assetClothingPrompt.trim().isEmpty()) {
                assetClothingPrompt = request.getOriginalPrompt();
            }
            if (assetClothingPrompt == null || assetClothingPrompt.trim().isEmpty()) {
                assetClothingPrompt = request.getCustomPrompt();
            }
            if (assetClothingPrompt != null && !assetClothingPrompt.trim().isEmpty()) {
                asset.setClothingPrompt(assetClothingPrompt);
            }

            // 上传图片到OSS并保存URL（重试3次）
            if (response.getImageUrl() != null) {
                String ossUrl = null;
                for (int retry = 0; retry < 3 && ossUrl == null; retry++) {
                    ossUrl = ossService.uploadImageFromUrl(response.getImageUrl(), "characters");
                    if (ossUrl == null && retry < 2) {
                        log.warn("OSS上传失败，第{}次重试...", retry + 2);
                        try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                }
                if (ossUrl != null) {
                    asset.setFilePath(ossUrl);
                    asset.setThumbnailPath(ossUrl);
                    asset.setTransparentPath(ossUrl);
                    log.info("图片已上传到OSS: {}", ossUrl.substring(0, Math.min(80, ossUrl.length())));
                } else {
                    // OSS上传失败，标记为失败状态，不使用临时URL
                    log.error("OSS上传失败3次，资产ID: {}", asset.getId());
                    asset.setStatus(AssetStatus.FAILED.getCode());
                    asset.setValidationPassed(0);
                }
            }

            if (generatingAssetId != null) {
                roleAssetMapper.updateById(asset);
            } else {
                roleAssetMapper.insert(asset);
            }

            // 构建并保存提示词
            String usedPrompt;
            if (request.getReferenceImageUrl() != null && !request.getReferenceImageUrl().isEmpty()) {
                usedPrompt = buildClothingChangePrompt(request);
            } else {
                usedPrompt = buildFinalCharacterSheetPrompt(request);
            }

            // 用户原始输入的提示词（优先使用originalPrompt，这是不含系统模板的用户原始输入）
            String userPrompt = request.getOriginalPrompt();
            if (userPrompt == null || userPrompt.trim().isEmpty()) {
                userPrompt = request.getCustomPrompt();
            }
            if (userPrompt == null || userPrompt.trim().isEmpty()) {
                userPrompt = request.getClothingPrompt();
            }
            if (userPrompt == null || userPrompt.trim().isEmpty()) {
                userPrompt = role.getCustomPrompt();
            }

            // 保存元数据 - 先检查是否已存在，避免唯一键冲突
            LambdaQueryWrapper<AssetMetadata> metadataWrapper = new LambdaQueryWrapper<>();
            metadataWrapper.eq(AssetMetadata::getAssetId, asset.getId());
            AssetMetadata existMetadata = assetMetadataMapper.selectOne(metadataWrapper);

            if (existMetadata != null) {
                // 已存在，更新
                existMetadata.setPrompt(usedPrompt);
                existMetadata.setUserPrompt(userPrompt);
                existMetadata.setSeed(response.getSeed());
                existMetadata.setModelVersion("volcengine-" + model);
                existMetadata.setImageWidth(response.getWidth());
                existMetadata.setImageHeight(response.getHeight());
                existMetadata.setAspectRatio(request.getAspectRatio());
                existMetadata.setQuality(request.getQuality());
                existMetadata.setStyleKey(request.getStyleKey());
                existMetadata.setStyleKeywords(request.getStyleKeywords());
                existMetadata.setDetailedView(request.getDetailedView());
                existMetadata.setFaceCloseupView(request.getFaceCloseupView());
                assetMetadataMapper.updateById(existMetadata);
            } else {
                // 不存在，创建新的
                AssetMetadata metadata = new AssetMetadata();
                metadata.setAssetId(asset.getId());
                metadata.setPrompt(usedPrompt);
                metadata.setUserPrompt(userPrompt);
                metadata.setSeed(response.getSeed());
                metadata.setModelVersion("volcengine-" + model);
                metadata.setImageWidth(response.getWidth());
                metadata.setImageHeight(response.getHeight());
                metadata.setAspectRatio(request.getAspectRatio());
                metadata.setQuality(request.getQuality());
                metadata.setStyleKey(request.getStyleKey());
                metadata.setStyleKeywords(request.getStyleKeywords());
                metadata.setDetailedView(request.getDetailedView());
                metadata.setFaceCloseupView(request.getFaceCloseupView());
                metadata.setGenerationTimeMs(0L);
                metadata.setCreatedAt(LocalDateTime.now());
                assetMetadataMapper.insert(metadata);
            }

            // 新服装生成完成，不做任何默认设置
            // 只有用户主动点击"设为默认"按钮时才调用 setDefaultClothing
            log.info("新服装生成完成: roleId={}, clothingId={}, 不自动设为默认", role.getId(), clothingId);

            log.info("资产保存成功: assetId={}, clothingId={}, version={}", asset.getId(), clothingId, asset.getVersion());
            return asset.getId();
        } catch (Exception e) {
            log.error("保存资产失败", e);
            return null;
        }
    }

    /**
     * 获取下一个版本号（只计算成功的版本，失败版本不计入）
     */
    private Integer getNextVersionInternal(Long roleId, Integer clothingId) {
        LambdaQueryWrapper<RoleAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RoleAsset::getRoleId, roleId)
                .eq(RoleAsset::getClothingId, clothingId)
                // 只计算成功的版本（排除生成中和失败的）
                .ne(RoleAsset::getStatus, AssetStatus.GENERATING.getCode())
                .ne(RoleAsset::getStatus, AssetStatus.FAILED.getCode())
                .select(RoleAsset::getVersion)
                .orderByDesc(RoleAsset::getVersion)
                .last("LIMIT 1");
        RoleAsset asset = roleAssetMapper.selectOne(wrapper);
        return asset != null ? asset.getVersion() + 1 : 1;
    }

    @Override
    public ImageGenerateResponse generateSceneImage(ImageGenerateRequest request) {
        log.info("生成场景图片: prompt={}, styleKeywords={}", request.getCustomPrompt(), request.getStyleKeywords());

        try {
            String prompt = buildFinalScenePrompt(request);
            String size = convertAspectRatioToSize(request.getAspectRatio(), request.getQuality());

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);
            requestBody.put("prompt", prompt);
            requestBody.put("size", size);
            requestBody.put("response_format", "url");
            requestBody.put("n", 1);
            requestBody.put("stream", false);
            requestBody.put("watermark", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);
            String url = baseUrl + "/images/generations";

            log.info("调用火山API生成场景: {}", url);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            log.info("火山API响应状态: {}", response.getStatusCode());

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseResponse(response.getBody());
            } else {
                ImageGenerateResponse errorResponse = new ImageGenerateResponse();
                errorResponse.setStatus("failed");
                errorResponse.setErrorMessage("API调用失败: " + response.getStatusCode());
                return errorResponse;
            }
        } catch (Exception e) {
            log.error("生成场景图片失败", e);
            ImageGenerateResponse errorResponse = new ImageGenerateResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage("生成失败: " + e.getMessage());
            return errorResponse;
        }
    }

    @Override
    public ImageGenerateResponse generatePropImage(ImageGenerateRequest request) {
        log.info("生成道具图片: prompt={}, styleKeywords={}", request.getCustomPrompt(), request.getStyleKeywords());

        try {
            String prompt = buildFinalPropPrompt(request);
            String size = convertAspectRatioToSize(request.getAspectRatio(), request.getQuality());

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);
            requestBody.put("prompt", prompt);
            requestBody.put("size", size);
            requestBody.put("response_format", "url");
            requestBody.put("n", 1);
            requestBody.put("stream", false);
            requestBody.put("watermark", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);
            String url = baseUrl + "/images/generations";

            log.info("调用火山API生成道具: {}", url);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            log.info("火山API响应状态: {}", response.getStatusCode());

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseResponse(response.getBody());
            } else {
                ImageGenerateResponse errorResponse = new ImageGenerateResponse();
                errorResponse.setStatus("failed");
                errorResponse.setErrorMessage("API调用失败: " + response.getStatusCode());
                return errorResponse;
            }
        } catch (Exception e) {
            log.error("生成道具图片失败", e);
            ImageGenerateResponse errorResponse = new ImageGenerateResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage("生成失败: " + e.getMessage());
            return errorResponse;
        }
    }

    private String buildFinalScenePrompt(ImageGenerateRequest request) {
        String prompt = normalizePrompt(request.getCustomPrompt());
        prompt = appendSeriesStyleGuard(prompt, request.getStyleKeywords());
        return appendConstraint(prompt,
                "scene background only, empty scene, no people, no characters, no figures, no humans, no portraits");
    }

    private String buildFinalPropPrompt(ImageGenerateRequest request) {
        String prompt = normalizePrompt(request.getCustomPrompt());
        prompt = appendSeriesStyleGuard(prompt, request.getStyleKeywords());
        prompt = stripBackgroundCues(prompt).trim();
        prompt = appendConstraint(prompt, "transparent background, isolated clean cutout, alpha channel style, no background, no backdrop");
        return appendConstraint(prompt,
                "single standalone prop only, centered object, product asset view, no hands, no hands holding, no people, no characters, no extra objects, no table, no room, no environment");
    }

    private String appendSeriesStyleGuard(String prompt, String styleKeywords) {
        if (!hasText(styleKeywords)) {
            return prompt;
        }

        String style = styleKeywords.trim();
        StringBuilder guard = new StringBuilder();
        guard.append("strict series visual style: ").append(style);
        guard.append(", follow this exact series style consistently");

        if (isStylizedAnimationStyle(style) || isStylizedAnimationStyle(prompt)) {
            guard.append(", ultra-detailed 3D anime render, stylized 3D animation");
            guard.append(", not live action, not photorealistic, not real person, no photo-real human skin, no 真人, no 写实真人");
        }

        return appendConstraint(prompt, guard.toString());
    }

    private String stripBackgroundCues(String prompt) {
        if (prompt == null) {
            return "";
        }
        return prompt
                .replaceAll("(?i)pure white seamless background", " ")
                .replaceAll("(?i)pure white background", " ")
                .replaceAll("(?i)solid white background", " ")
                .replaceAll("(?i)white background", " ")
                .replaceAll("(?i)isolated on white background", " ")
                .replaceAll("(?i)transparent background", " ")
                .replaceAll("(?i)solid pale blue or light gray background", " ")
                .replaceAll("(?i)flat clean background", " ")
                .replaceAll("(?i)#ffffff", " ")
                .replace("纯白背景", " ")
                .replace("白色背景", " ")
                .replace("浅蓝背景", " ")
                .replace("浅灰背景", " ")
                .replace("透明背景", " ");
    }

    private boolean isStylizedAnimationStyle(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("anime")
                || lower.contains("cartoon")
                || lower.contains("animation")
                || lower.contains("stylized")
                || lower.contains("3d")
                || text.contains("动漫")
                || text.contains("动画")
                || text.contains("卡通")
                || text.contains("二次元")
                || text.contains("3D");
    }

    private String appendConstraint(String prompt, String constraint) {
        if (!hasText(prompt)) {
            return constraint;
        }
        if (!hasText(constraint)) {
            return prompt;
        }
        return prompt + ", " + constraint;
    }

    private String normalizePrompt(String prompt) {
        return prompt == null ? "" : prompt.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
