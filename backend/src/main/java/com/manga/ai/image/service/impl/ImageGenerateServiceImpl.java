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
    private final TransactionTemplate transactionTemplate;

    public ImageGenerateServiceImpl(RoleMapper roleMapper, SeriesMapper seriesMapper,
                                    RoleAssetMapper roleAssetMapper, AssetMetadataMapper assetMetadataMapper,
                                    @Lazy AssetService assetService, OssService ossService,
                                    TransactionTemplate transactionTemplate) {
        // 配置 RestTemplate 超时
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000);  // 连接超时 30秒
        factory.setReadTimeout(180000);    // 读取超时 180秒 (图片生成可能需要较长时间)
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
        log.info("========== 图生图开始 ==========");
        log.info("roleName: {}", request.getRoleName());
        log.info("referenceImageUrl: {}", request.getReferenceImageUrl());
        log.info("clothingPrompt: {}", request.getClothingPrompt());
        log.info("================================");

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
                // 值越高，保留原图特征越多；0.85 能较好地保持角色特征
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
            // 禁止文字
            prompt.append("NO text, NO words, NO letters, NO numbers, NO captions, NO labels, NO watermarks. ");
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
        prompt.append("clear outline, high quality, detailed. ");

        // 禁止文字
        prompt.append("NO text, NO words, NO letters, NO numbers, NO captions, NO labels, NO watermarks, NO Chinese characters, NO English text.");

        return prompt.toString();
    }

    /**
     * 构建换装提示词（图生图）
     */
    private String buildClothingChangePrompt(ImageGenerateRequest request) {
        StringBuilder prompt = new StringBuilder();

        // 强调保持参考图的所有特征和风格
        prompt.append("Keep EXACTLY the same person from the reference image. ");
        prompt.append("Same face, same age, same gender, same body type, same ethnicity, same art style. ");
        prompt.append("Do NOT change the person's appearance, age, or art style. ");
        prompt.append("Maintain the exact same visual style as the reference image. ");

        // 新服装描述
        if (request.getClothingPrompt() != null && !request.getClothingPrompt().trim().isEmpty()) {
            prompt.append("Only change the outfit to: ").append(request.getClothingPrompt()).append(". ");
        }

        // 三视图要求
        prompt.append("Generate a character sheet with three views: front, side, and back. ");
        prompt.append("White background, high quality, detailed. ");

        // 禁止文字
        prompt.append("NO text, NO words, NO letters, NO numbers, NO captions, NO labels, NO watermarks, NO Chinese characters, NO English text.");

        return prompt.toString();
    }

    @Override
    @Async("taskExecutor")
    public void generateCharacterAssets(Long roleId) {
        generateCharacterAssets(roleId, null, null, null);
    }

    @Override
    public void generateCharacterAssets(Long roleId, Integer clothingId) {
        generateCharacterAssets(roleId, clothingId, null, null);
    }

    @Override
    public void generateCharacterAssets(Long roleId, Integer clothingId, Long generatingAssetId) {
        generateCharacterAssets(roleId, clothingId, generatingAssetId, null);
    }

    @Override
    @Async("taskExecutor")
    public void generateCharacterAssets(Long roleId, Integer clothingId, Long generatingAssetId, Long previousActiveAssetId) {
        log.info("异步生成角色资产: roleId={}, clothingId={}, previousActiveAssetId={}", roleId, clothingId, previousActiveAssetId);

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
            long[] result = createGeneratingAssetInternal(role, finalClothingId, clothingName);
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

            // 同时恢复角色的 customPrompt
            String previousPrompt = assetService.getAssetPrompt(previousAsset.getId());
            if (previousPrompt != null && !previousPrompt.trim().isEmpty()) {
                Role role = roleMapper.selectById(roleId);
                if (role != null) {
                    role.setCustomPrompt(previousPrompt);
                    role.setUpdatedAt(LocalDateTime.now());
                    roleMapper.updateById(role);
                    log.info("已恢复角色提示词: roleId={}, prompt={}", roleId, previousPrompt);
                }
            }
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
        log.info("异步生成新服装（图生图）: roleId={}, clothingId={}, referenceUrl={}, clothingName={}", roleId, clothingId, referenceImageUrl, clothingName);

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
            long[] result = createGeneratingAssetInternal(role, finalClothingId, clothingName);
            generatingAssetId = result[0];
            if (previousActiveAssetId == null) {
                previousActiveAssetId = result[1] > 0 ? result[1] : null;
            }
        }

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
                // 已存在，更新提示词
                String userPrompt = request.getClothingPrompt() != null ? request.getClothingPrompt() : request.getCustomPrompt();
                existMetadata.setUserPrompt(userPrompt);
                existMetadata.setPrompt(userPrompt);
                assetMetadataMapper.updateById(existMetadata);
                log.info("更新失败资产的元数据: assetId={}", assetId);
            } else {
                // 不存在，创建新的元数据
                String userPrompt = request.getClothingPrompt() != null ? request.getClothingPrompt() : request.getCustomPrompt();
                AssetMetadata metadata = new AssetMetadata();
                metadata.setAssetId(assetId);
                metadata.setPrompt(userPrompt);
                metadata.setUserPrompt(userPrompt);
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
        log.info("createGeneratingAsset 开始: roleId={}, clothingId={}, clothingName={}", roleId, clothingId, clothingName);

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

        long[] result = createGeneratingAssetInternal(role, clothingId, clothingName);
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
    private long[] createGeneratingAssetInternal(Role role, Integer clothingId, String clothingName) {
        log.info("开始创建生成中资产: roleId={}, clothingId={}", role.getId(), clothingId);

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
            failedAsset.setUpdatedAt(LocalDateTime.now());
            roleAssetMapper.updateById(failedAsset);
            asset = failedAsset;

            // 更新提示词元数据（如果角色有新的提示词）
            String newPrompt = role.getCustomPrompt();
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
            asset.setVersion(version);
            asset.setFileName(role.getRoleName() + "_C" + String.format("%02d", clothingId) + "_V" + String.format("%02d", version) + ".png");
            asset.setStatus(AssetStatus.GENERATING.getCode()); // 生成中状态
            asset.setIsActive(1);
            asset.setValidationPassed(0);
            asset.setCreatedAt(LocalDateTime.now());
            asset.setUpdatedAt(LocalDateTime.now());

            roleAssetMapper.insert(asset);

            // 创建新资产时，立即保存提示词到metadata
            if (role.getCustomPrompt() != null && !role.getCustomPrompt().trim().isEmpty()) {
                AssetMetadata newMetadata = new AssetMetadata();
                newMetadata.setAssetId(asset.getId());
                newMetadata.setPrompt(role.getCustomPrompt());
                newMetadata.setUserPrompt(role.getCustomPrompt());
                newMetadata.setCreatedAt(LocalDateTime.now());
                assetMetadataMapper.insert(newMetadata);
                log.info("创建新资产的提示词元数据: assetId={}, prompt={}", asset.getId(), role.getCustomPrompt());
            }
        }

        log.info("生成中资产准备完成: assetId={}, roleId={}, clothingId={}, version={}",
                asset.getId(), role.getId(), clothingId, asset.getVersion());
        return new long[]{asset.getId(), previousActiveAssetId != null ? previousActiveAssetId : 0};
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
                usedPrompt = buildCharacterSheetPrompt(request);
            }

            // 用户原始输入的提示词
            String userPrompt = request.getCustomPrompt();
            if (userPrompt == null || userPrompt.trim().isEmpty()) {
                userPrompt = role.getCustomPrompt();
            }

            // 保存元数据
            AssetMetadata metadata = new AssetMetadata();
            metadata.setAssetId(asset.getId());
            metadata.setPrompt(usedPrompt);
            metadata.setUserPrompt(userPrompt);
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
}
