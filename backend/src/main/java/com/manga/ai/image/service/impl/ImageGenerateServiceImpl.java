package com.manga.ai.image.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.manga.ai.asset.entity.AssetMetadata;
import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.asset.mapper.AssetMetadataMapper;
import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.asset.service.AssetService;
import com.manga.ai.common.enums.AssetStatus;
import com.manga.ai.common.enums.RoleStatus;
import com.manga.ai.common.service.OssService;
import com.manga.ai.image.dto.ImageGenerateRequest;
import com.manga.ai.image.dto.ImageGenerateResponse;
import com.manga.ai.image.service.ImageGenerateService;
import com.manga.ai.role.entity.Role;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.series.entity.Series;
import com.manga.ai.series.mapper.SeriesMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;

/**
 * 火山方舟图像生成服务实现 - 使用HTTP API
 */
@Slf4j
@Service
public class ImageGenerateServiceImpl implements ImageGenerateService {

    @Value("${volcengine.ark.api-key}")
    private String apiKey;

    @Value("${volcengine.ark.model:doubao-seedream-5-0-260128}")
    private String model;

    @Value("${volcengine.ark.base-url:https://ark.cn-beijing.volces.com/api/v3}")
    private String baseUrl;

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

    public ImageGenerateServiceImpl(RoleMapper roleMapper, SeriesMapper seriesMapper,
                                    RoleAssetMapper roleAssetMapper, AssetMetadataMapper assetMetadataMapper,
                                    @Lazy AssetService assetService, OssService ossService) {
        this.restTemplate = new RestTemplate();
        this.roleMapper = roleMapper;
        this.seriesMapper = seriesMapper;
        this.roleAssetMapper = roleAssetMapper;
        this.assetMetadataMapper = assetMetadataMapper;
        this.assetService = assetService;
        this.ossService = ossService;
    }

    @Override
    public ImageGenerateResponse generateCharacterSheet(ImageGenerateRequest request) {
        log.info("文生图: role={}, prompt={}", request.getRoleName(), request.getCustomPrompt());

        try {
            String prompt = buildCharacterSheetPrompt(request);
            String size = convertAspectRatioToSize(request.getAspectRatio());

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
    public ImageGenerateResponse generateCharacterSheetWithReference(ImageGenerateRequest request) {
        log.info("图生图: role={}, referenceUrl={}, clothingPrompt={}",
                request.getRoleName(), request.getReferenceImageUrl(), request.getClothingPrompt());

        try {
            String prompt = buildClothingChangePrompt(request);
            String size = convertAspectRatioToSize(request.getAspectRatio());

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);
            requestBody.put("prompt", prompt);
            requestBody.put("size", size);
            requestBody.put("response_format", "url");
            requestBody.put("n", 1);
            requestBody.put("stream", false);
            requestBody.put("watermark", false);

            // 如果有参考图，添加image参数（图生图）
            if (request.getReferenceImageUrl() != null && !request.getReferenceImageUrl().isEmpty()) {
                requestBody.put("image", request.getReferenceImageUrl());
                // 图生图的强度参数，控制保留原特征的程度
                requestBody.put("strength", 0.7);
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
     */
    private String convertAspectRatioToSize(String aspectRatio) {
        if ("16:9".equals(aspectRatio)) {
            return "2560x1440"; // 2K横图
        } else {
            return "2048x2048"; // 正方形
        }
    }

    /**
     * 解析响应
     */
    private ImageGenerateResponse parseResponse(String responseBody) {
        JSONObject json = JSON.parseObject(responseBody);
        ImageGenerateResponse response = new ImageGenerateResponse();
        response.setStatus("success");

        JSONArray data = json.getJSONArray("data");
        if (data != null && !data.isEmpty()) {
            JSONObject imageData = data.getJSONObject(0);
            String imageUrl = imageData.getString("url");
            response.setImageUrl(imageUrl);
            log.info("图片URL: {}", imageUrl);
        }

        return response;
    }

    /**
     * 构建角色三视图提示词（文生图）
     */
    private String buildCharacterSheetPrompt(ImageGenerateRequest request) {
        StringBuilder prompt = new StringBuilder();

        // 如果有自定义提示词，优先使用
        if (request.getCustomPrompt() != null && !request.getCustomPrompt().trim().isEmpty()) {
            prompt.append(request.getCustomPrompt());
            // 确保包含三视图要求
            if (!request.getCustomPrompt().toLowerCase().contains("three view") &&
                !request.getCustomPrompt().toLowerCase().contains("character sheet") &&
                !request.getCustomPrompt().toLowerCase().contains("front") &&
                !request.getCustomPrompt().toLowerCase().contains("side") &&
                !request.getCustomPrompt().toLowerCase().contains("back")) {
                prompt.append(". Character design sheet with three views in ONE image: ");
                prompt.append("front view (center), side view (left), back view (right). ");
            }
            return prompt.toString();
        }

        // 默认提示词构建
        prompt.append("Character design sheet with three views in ONE image: ");
        prompt.append("front view (center), side view (left), back view (right). ");
        prompt.append("Full body character reference sheet. ");

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
        prompt.append("White background, professional character sheet layout, ");
        prompt.append("consistent style across all views, ");
        prompt.append("A-pose standing pose for front view, ");
        prompt.append("clear outline, high quality, detailed.");

        return prompt.toString();
    }

    /**
     * 构建换装提示词（图生图）
     */
    private String buildClothingChangePrompt(ImageGenerateRequest request) {
        StringBuilder prompt = new StringBuilder();

        // 基于参考图生成，保持角色特征不变，只换服装
        prompt.append("Same character as reference image, keep the same face, body type, and pose. ");

        // 新服装描述
        if (request.getClothingPrompt() != null && !request.getClothingPrompt().trim().isEmpty()) {
            prompt.append("New outfit: ").append(request.getClothingPrompt()).append(". ");
        }

        // 角色信息
        prompt.append("Character: ").append(request.getRoleName()).append(". ");

        // 风格
        if (request.getStyleKeywords() != null && !request.getStyleKeywords().isEmpty()) {
            prompt.append("Style: ").append(request.getStyleKeywords()).append(". ");
        }

        // 三视图要求
        prompt.append("Character design sheet with three views in ONE image: ");
        prompt.append("front view (center), side view (left), back view (right). ");
        prompt.append("White background, professional character sheet layout, ");
        prompt.append("consistent character across all views, high quality, detailed.");

        return prompt.toString();
    }

    @Override
    @Async("taskExecutor")
    public void generateCharacterAssets(Long roleId) {
        generateCharacterAssets(roleId, null);
    }

    @Override
    @Async("taskExecutor")
    public void generateCharacterAssets(Long roleId, Integer clothingId) {
        log.info("异步生成角色资产: roleId={}, clothingId={}", roleId, clothingId);

        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            log.error("角色不存在: roleId={}", roleId);
            return;
        }

        Series series = seriesMapper.selectById(role.getSeriesId());

        // 构建角色描述
        StringBuilder description = new StringBuilder();
        if (role.getAge() != null) description.append(role.getAge()).append(", ");
        if (role.getGender() != null) description.append(role.getGender()).append(", ");
        if (role.getAppearance() != null) description.append(role.getAppearance()).append(", ");
        if (role.getClothing() != null) description.append("wearing ").append(role.getClothing());

        // 构建请求
        String aspectRatio = series != null && series.getAspectRatio() != null ? series.getAspectRatio() : "3:4";
        String quality = series != null && series.getQuality() != null ? series.getQuality() : "hd";
        String styleKeywords = role.getStyleKeywords() != null ? role.getStyleKeywords() :
                               (series != null ? series.getStyleKeywords() : "");

        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .roleName(role.getRoleName())
                .characterDescription(description.toString())
                .aspectRatio(aspectRatio)
                .quality(quality)
                .styleKeywords(styleKeywords)
                .seriesId(role.getSeriesId())
                .roleId(roleId)
                .customPrompt(role.getCustomPrompt())
                .clothingId(clothingId)
                .build();

        // 调用文生图生成
        ImageGenerateResponse response = generateCharacterSheet(request);

        if ("success".equals(response.getStatus())) {
            saveAsset(role, response, request);

            // 更新角色状态为待审核（只有当角色状态是生成中时才更新）
            if (role.getStatus().equals(RoleStatus.EXTRACTING.getCode())) {
                role.setStatus(RoleStatus.PENDING_REVIEW.getCode());
                role.setUpdatedAt(LocalDateTime.now());
                roleMapper.updateById(role);
            }
            log.info("角色资产生成完成: roleId={}", roleId);
        } else {
            log.error("生成失败: {}", response.getErrorMessage());
            if (role.getStatus().equals(RoleStatus.EXTRACTING.getCode())) {
                role.setStatus(RoleStatus.PENDING_REVIEW.getCode());
                role.setUpdatedAt(LocalDateTime.now());
                roleMapper.updateById(role);
            }
        }
    }

    @Override
    @Async("taskExecutor")
    public void generateNewClothingWithReference(Long roleId, String referenceImageUrl, String clothingPrompt, String clothingName) {
        log.info("异步生成新服装（图生图）: roleId={}, referenceUrl={}, clothingName={}", roleId, referenceImageUrl, clothingName);

        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            log.error("角色不存在: roleId={}", roleId);
            return;
        }

        Series series = seriesMapper.selectById(role.getSeriesId());

        // 获取下一个服装编号
        Integer clothingId = assetService.getNextClothingId(roleId);

        // 构建请求
        String aspectRatio = series != null && series.getAspectRatio() != null ? series.getAspectRatio() : "3:4";
        String quality = series != null && series.getQuality() != null ? series.getQuality() : "hd";
        String styleKeywords = role.getStyleKeywords() != null ? role.getStyleKeywords() :
                               (series != null ? series.getStyleKeywords() : "");

        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .roleName(role.getRoleName())
                .aspectRatio(aspectRatio)
                .quality(quality)
                .styleKeywords(styleKeywords)
                .seriesId(role.getSeriesId())
                .roleId(roleId)
                .referenceImageUrl(referenceImageUrl)
                .clothingPrompt(clothingPrompt)
                .clothingName(clothingName)
                .clothingId(clothingId)
                .build();

        // 调用图生图生成
        ImageGenerateResponse response = generateCharacterSheetWithReference(request);

        if ("success".equals(response.getStatus())) {
            saveAsset(role, response, request);
            log.info("新服装生成完成: roleId={}, clothingId={}, clothingName={}", roleId, clothingId, clothingName);
        } else {
            log.error("新服装生成失败: {}", response.getErrorMessage());
        }
    }

    /**
     * 保存资产
     */
    private Long saveAsset(Role role, ImageGenerateResponse response, ImageGenerateRequest request) {
        try {
            // 确定服装编号
            Integer clothingId = request.getClothingId();
            if (clothingId == null || clothingId <= 0) {
                clothingId = assetService.getNextClothingId(role.getId());
            }

            // 确定版本号（同一服装的新版本）
            Integer version = getNextVersion(role.getId(), clothingId);

            // 如果是新服装，将该角色的其他服装设为非默认
            boolean isNewClothing = version == 1;

            // 创建资产记录
            RoleAsset asset = new RoleAsset();
            asset.setRoleId(role.getId());
            asset.setAssetType("CHARACTER_SHEET");
            asset.setViewType("ALL");
            asset.setClothingId(clothingId);
            asset.setClothingName(clothingId == 1 ? "默认" : request.getClothingName());
            asset.setVersion(version);
            asset.setFileName(role.getRoleName() + "_C" + String.format("%02d", clothingId) + "_V" + String.format("%02d", version) + ".png");
            asset.setStatus(AssetStatus.PENDING_REVIEW.getCode());
            asset.setIsActive(isNewClothing ? 1 : 0); // 新服装默认为激活
            asset.setValidationPassed(1);
            asset.setCreatedAt(LocalDateTime.now());
            asset.setUpdatedAt(LocalDateTime.now());

            // 上传图片到OSS并保存URL
            if (response.getImageUrl() != null) {
                String ossUrl = ossService.uploadImageFromUrl(response.getImageUrl(), "characters");
                if (ossUrl != null) {
                    asset.setFilePath(ossUrl);
                    asset.setThumbnailPath(ossUrl);
                    asset.setTransparentPath(ossUrl);
                    log.info("图片已上传到OSS: {}", ossUrl.substring(0, Math.min(80, ossUrl.length())));
                } else {
                    // OSS上传失败，使用原始URL
                    log.warn("OSS上传失败，使用原始URL");
                    asset.setFilePath(response.getImageUrl());
                    asset.setThumbnailPath(response.getImageUrl());
                    asset.setTransparentPath(response.getImageUrl());
                }
            }

            roleAssetMapper.insert(asset);

            // 构建并保存提示词
            String usedPrompt;
            if (request.getReferenceImageUrl() != null && !request.getReferenceImageUrl().isEmpty()) {
                usedPrompt = buildClothingChangePrompt(request);
            } else {
                usedPrompt = buildCharacterSheetPrompt(request);
            }

            // 保存元数据
            AssetMetadata metadata = new AssetMetadata();
            metadata.setAssetId(asset.getId());
            metadata.setPrompt(usedPrompt);
            metadata.setSeed(response.getSeed());
            metadata.setModelVersion("volcengine-" + model);
            metadata.setImageWidth(response.getWidth());
            metadata.setImageHeight(response.getHeight());
            metadata.setAspectRatio(request.getAspectRatio());
            metadata.setGenerationTimeMs(0L);
            metadata.setCreatedAt(LocalDateTime.now());
            assetMetadataMapper.insert(metadata);

            // 如果是新服装，更新其他服装为非默认
            if (isNewClothing) {
                assetService.setDefaultClothing(role.getId(), clothingId);
            }

            log.info("资产保存成功: assetId={}, clothingId={}, version={}", asset.getId(), clothingId, version);
            return asset.getId();
        } catch (Exception e) {
            log.error("保存资产失败", e);
            return null;
        }
    }

    /**
     * 获取下一个版本号
     */
    private Integer getNextVersion(Long roleId, Integer clothingId) {
        LambdaQueryWrapper<RoleAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RoleAsset::getRoleId, roleId)
                .eq(RoleAsset::getClothingId, clothingId)
                .select(RoleAsset::getVersion)
                .orderByDesc(RoleAsset::getVersion)
                .last("LIMIT 1");
        RoleAsset asset = roleAssetMapper.selectOne(wrapper);
        return asset != null ? asset.getVersion() + 1 : 1;
    }
}
