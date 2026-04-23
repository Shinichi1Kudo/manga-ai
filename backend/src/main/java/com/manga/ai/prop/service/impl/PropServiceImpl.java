package com.manga.ai.prop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.manga.ai.common.enums.PropStatus;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.service.OssService;
import com.manga.ai.image.dto.ImageGenerateRequest;
import com.manga.ai.image.dto.ImageGenerateResponse;
import com.manga.ai.image.service.ImageGenerateService;
import com.manga.ai.llm.service.ImagePromptGenerateService;
import com.manga.ai.prop.dto.PropDetailVO;
import com.manga.ai.prop.entity.Prop;
import com.manga.ai.prop.entity.PropAsset;
import com.manga.ai.prop.entity.PropAssetMetadata;
import com.manga.ai.prop.mapper.PropMapper;
import com.manga.ai.prop.mapper.PropAssetMapper;
import com.manga.ai.prop.mapper.PropAssetMetadataMapper;
import com.manga.ai.prop.service.PropService;
import com.manga.ai.series.entity.Series;
import com.manga.ai.series.mapper.SeriesMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 道具服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropServiceImpl implements PropService {

    private final PropMapper propMapper;
    private final PropAssetMapper propAssetMapper;
    private final PropAssetMetadataMapper propAssetMetadataMapper;
    private final SeriesMapper seriesMapper;
    private final ImageGenerateService imageGenerateService;
    private final OssService ossService;
    private final ImagePromptGenerateService imagePromptGenerateService;

    @Qualifier("imageGenerateExecutor")
    private final Executor imageGenerateExecutor;

    @Override
    public List<PropDetailVO> getPropsBySeriesId(Long seriesId) {
        // 查询所有道具
        LambdaQueryWrapper<Prop> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Prop::getSeriesId, seriesId)
                .orderByAsc(Prop::getCreatedAt);
        List<Prop> props = propMapper.selectList(wrapper);

        if (props.isEmpty()) {
            return List.of();
        }

        // 批量查询所有资产（解决N+1问题）
        List<Long> propIds = props.stream().map(Prop::getId).collect(Collectors.toList());
        LambdaQueryWrapper<PropAsset> assetWrapper = new LambdaQueryWrapper<>();
        assetWrapper.in(PropAsset::getPropId, propIds)
                .orderByDesc(PropAsset::getIsActive)
                .orderByDesc(PropAsset::getVersion);
        List<PropAsset> allAssets = propAssetMapper.selectList(assetWrapper);

        // 按道具ID分组
        Map<Long, List<PropAsset>> assetsByPropId = allAssets.stream()
                .collect(Collectors.groupingBy(PropAsset::getPropId));

        // 组装VO
        return props.stream()
                .map(prop -> convertToVOWithAssets(prop, assetsByPropId.getOrDefault(prop.getId(), List.of())))
                .collect(Collectors.toList());
    }

    @Override
    public PropDetailVO getPropDetail(Long propId) {
        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            throw new BusinessException("道具不存在");
        }
        return convertToVO(prop);
    }

    @Override
    @Async("imageGenerateExecutor")
    public void generatePropAssets(Long propId) {
        log.info("开始生成道具资产: propId={}", propId);

        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            log.error("道具不存在: propId={}", propId);
            return;
        }

        try {
            // 更新状态为生成中
            prop.setStatus(PropStatus.GENERATING.getCode());
            prop.setUpdatedAt(LocalDateTime.now());
            propMapper.updateById(prop);

            // 获取系列信息（用于获取风格关键词）
            Series series = seriesMapper.selectById(prop.getSeriesId());
            String styleKeywords = series != null ? series.getStyleKeywords() : null;

            // 构建道具生成提示词（透明背景）
            String prompt = buildPropPrompt(prop, styleKeywords);

            // 创建资产记录
            PropAsset asset = new PropAsset();
            asset.setPropId(propId);
            asset.setAssetType("item");
            asset.setViewType("main");
            asset.setVersion(1);
            asset.setStatus(0); // 生成中
            asset.setIsActive(1);
            asset.setCreatedAt(LocalDateTime.now());
            asset.setUpdatedAt(LocalDateTime.now());
            propAssetMapper.insert(asset);

            // 调用图片生成服务
            ImageGenerateRequest request = new ImageGenerateRequest();
            request.setCustomPrompt(prompt);
            request.setAspectRatio("1:1"); // 道具使用正方形比例
            request.setQuality("hd");

            long startTime = System.currentTimeMillis();
            ImageGenerateResponse response = imageGenerateService.generatePropImage(request);
            long generationTime = System.currentTimeMillis() - startTime;

            if (response != null && response.getImageUrl() != null) {
                // 上传到OSS
                String ossUrl = ossService.uploadImageFromUrl(response.getImageUrl(), "props");

                // 更新资产记录
                asset.setFilePath(ossUrl);
                asset.setTransparentPath(ossUrl); // 透明PNG暂用原图
                asset.setThumbnailPath(ossUrl);
                asset.setFileName(prop.getPropName() + "_v1.png");
                asset.setStatus(1); // 已完成
                asset.setUpdatedAt(LocalDateTime.now());
                propAssetMapper.updateById(asset);

                // 保存元数据
                PropAssetMetadata metadata = new PropAssetMetadata();
                metadata.setAssetId(asset.getId());
                metadata.setPrompt(prompt);
                metadata.setUserPrompt(prop.getDescription());
                metadata.setSeed(response.getSeed());
                metadata.setModelVersion("seedream-5.0");
                metadata.setAspectRatio("1:1");
                metadata.setGenerationTimeMs(generationTime);
                metadata.setCreatedAt(LocalDateTime.now());
                propAssetMetadataMapper.insert(metadata);

                // 更新道具状态为待审核
                prop.setStatus(PropStatus.PENDING_REVIEW.getCode());
                prop.setUpdatedAt(LocalDateTime.now());
                propMapper.updateById(prop);

                log.info("道具资产生成完成: propId={}, assetId={}", propId, asset.getId());
            } else {
                throw new RuntimeException("图片生成失败: 响应为空");
            }

        } catch (Exception e) {
            log.error("道具资产生成失败: propId={}", propId, e);
            prop.setStatus(PropStatus.GENERATING.getCode()); // 保持生成中状态，允许重试
            prop.setUpdatedAt(LocalDateTime.now());
            propMapper.updateById(prop);
        }
    }

    @Override
    @Async("imageGenerateExecutor")
    public void regeneratePropAsset(Long propId, String customPrompt, String quality) {
        log.info("重新生成道具资产: propId={}, quality={}, customPrompt={}",
                propId, quality, customPrompt != null);

        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            log.error("道具不存在: propId={}", propId);
            return;
        }

        // 检查是否已锁定
        if (PropStatus.LOCKED.getCode().equals(prop.getStatus())) {
            log.warn("道具已锁定，无法重新生成: propId={}", propId);
            return;
        }

        // 更新清晰度（如果提供了新值）
        if (quality != null && !quality.isEmpty()) {
            prop.setQuality(quality);
        }

        try {
            // 如果提供了自定义提示词，更新道具
            if (customPrompt != null && !customPrompt.trim().isEmpty()) {
                prop.setCustomPrompt(customPrompt);
            }

            // 设置状态为生成中
            prop.setStatus(PropStatus.GENERATING.getCode());
            prop.setUpdatedAt(LocalDateTime.now());
            propMapper.updateById(prop);

            // 获取系列信息
            Series series = seriesMapper.selectById(prop.getSeriesId());
            String styleKeywords = series != null ? series.getStyleKeywords() : null;

            // 构建提示词
            String prompt = buildPropPrompt(prop, styleKeywords);

            // 获取下一个版本号
            int nextVersion = getNextVersion(propId);

            // 道具固定1:1比例，使用道具实体的清晰度（已更新）
            String finalAspectRatio = "1:1";
            String finalQuality = prop.getQuality() != null ? prop.getQuality() : "2k";

            // 检查是否已存在占位资产（从createProp同步创建的）
            LambdaQueryWrapper<PropAsset> placeholderWrapper = new LambdaQueryWrapper<>();
            placeholderWrapper.eq(PropAsset::getPropId, propId)
                    .eq(PropAsset::getStatus, 0)
                    .eq(PropAsset::getIsActive, 0)
                    .orderByDesc(PropAsset::getVersion)
                    .last("LIMIT 1");
            PropAsset asset = propAssetMapper.selectOne(placeholderWrapper);

            int actualVersion;
            if (asset != null) {
                // 已存在占位资产，使用它
                actualVersion = asset.getVersion();
                log.info("使用已存在的占位资产: propId={}, version={}, assetId={}", propId, actualVersion, asset.getId());
            } else {
                // 没有占位资产，创建新资产记录
                actualVersion = nextVersion;
                asset = new PropAsset();
                asset.setPropId(propId);
                asset.setAssetType("item");
                asset.setViewType("main");
                asset.setVersion(actualVersion);
                asset.setStatus(0);
                asset.setIsActive(0);
                asset.setCreatedAt(LocalDateTime.now());
                asset.setUpdatedAt(LocalDateTime.now());
                propAssetMapper.insert(asset);
                log.info("创建新资产记录: propId={}, version={}, assetId={}", propId, actualVersion, asset.getId());
            }

            // 调用图片生成服务
            ImageGenerateRequest request = new ImageGenerateRequest();
            request.setCustomPrompt(prompt);
            request.setAspectRatio(finalAspectRatio);
            request.setQuality(finalQuality);

            long startTime = System.currentTimeMillis();
            ImageGenerateResponse response = imageGenerateService.generatePropImage(request);
            long generationTime = System.currentTimeMillis() - startTime;

            if (response != null && response.getImageUrl() != null) {
                // 上传到OSS
                String ossUrl = ossService.uploadImageFromUrl(response.getImageUrl(), "props");

                // 停用旧资产
                LambdaQueryWrapper<PropAsset> updateWrapper = new LambdaQueryWrapper<>();
                updateWrapper.eq(PropAsset::getPropId, propId);
                PropAsset updateAsset = new PropAsset();
                updateAsset.setIsActive(0);
                updateAsset.setUpdatedAt(LocalDateTime.now());
                propAssetMapper.update(updateAsset, updateWrapper);

                // 激活新资产
                asset.setFilePath(ossUrl);
                asset.setTransparentPath(ossUrl);
                asset.setThumbnailPath(ossUrl);
                asset.setFileName(prop.getPropName() + "_v" + actualVersion + ".png");
                asset.setStatus(1);
                asset.setIsActive(1);
                asset.setUpdatedAt(LocalDateTime.now());
                propAssetMapper.updateById(asset);

                // 保存元数据
                PropAssetMetadata metadata = new PropAssetMetadata();
                metadata.setAssetId(asset.getId());
                metadata.setPrompt(prompt);
                metadata.setUserPrompt(customPrompt != null ? customPrompt : prop.getDescription());
                metadata.setSeed(response.getSeed());
                metadata.setModelVersion("seedream-5.0");
                metadata.setAspectRatio(finalAspectRatio);
                metadata.setGenerationTimeMs(generationTime);
                metadata.setCreatedAt(LocalDateTime.now());
                propAssetMetadataMapper.insert(metadata);

                // 更新道具状态为待审核
                prop.setStatus(PropStatus.PENDING_REVIEW.getCode());
                prop.setUpdatedAt(LocalDateTime.now());
                propMapper.updateById(prop);

                log.info("道具资产重新生成完成: propId={}, assetId={}, version={}", propId, asset.getId(), actualVersion);
            } else {
                throw new RuntimeException("图片生成失败: 响应为空");
            }

        } catch (Exception e) {
            log.error("道具资产重新生成失败: propId={}", propId, e);
            // 生成失败，恢复到待审核状态
            prop.setStatus(PropStatus.PENDING_REVIEW.getCode());
            prop.setUpdatedAt(LocalDateTime.now());
            propMapper.updateById(prop);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reviewProp(Long propId, boolean approved) {
        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            throw new BusinessException("道具不存在");
        }

        if (approved) {
            prop.setStatus(PropStatus.LOCKED.getCode());
        } else {
            prop.setStatus(PropStatus.PENDING_REVIEW.getCode());
        }
        prop.setUpdatedAt(LocalDateTime.now());
        propMapper.updateById(prop);

        log.info("道具审核完成: propId={}, approved={}", propId, approved);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void lockProp(Long propId) {
        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            throw new BusinessException("道具不存在");
        }

        prop.setStatus(PropStatus.LOCKED.getCode());
        prop.setUpdatedAt(LocalDateTime.now());
        propMapper.updateById(prop);

        log.info("道具已锁定: propId={}", propId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlockProp(Long propId) {
        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            throw new BusinessException("道具不存在");
        }

        prop.setStatus(PropStatus.PENDING_REVIEW.getCode());
        prop.setUpdatedAt(LocalDateTime.now());
        propMapper.updateById(prop);

        log.info("道具已解锁: propId={}", propId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePropName(Long propId, String propName) {
        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            throw new BusinessException("道具不存在");
        }

        if (propName == null || propName.trim().isEmpty()) {
            throw new BusinessException("道具名称不能为空");
        }

        prop.setPropName(propName.trim());
        prop.setUpdatedAt(LocalDateTime.now());
        propMapper.updateById(prop);

        log.info("道具名称已更新: propId={}, propName={}", propId, propName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProp(Long propId) {
        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            throw new BusinessException("道具不存在");
        }

        // 查询关联的资产ID列表（只查询ID，减少数据传输）
        LambdaQueryWrapper<PropAsset> assetWrapper = new LambdaQueryWrapper<>();
        assetWrapper.eq(PropAsset::getPropId, propId)
                .select(PropAsset::getId);
        List<PropAsset> assets = propAssetMapper.selectList(assetWrapper);

        if (!assets.isEmpty()) {
            // 批量删除资产元数据
            List<Long> assetIds = assets.stream()
                    .map(PropAsset::getId)
                    .collect(Collectors.toList());
            LambdaQueryWrapper<PropAssetMetadata> metadataWrapper = new LambdaQueryWrapper<>();
            metadataWrapper.in(PropAssetMetadata::getAssetId, assetIds);
            propAssetMetadataMapper.delete(metadataWrapper);

            // 批量删除关联的资产
            LambdaQueryWrapper<PropAsset> deleteAssetWrapper = new LambdaQueryWrapper<>();
            deleteAssetWrapper.eq(PropAsset::getPropId, propId);
            propAssetMapper.delete(deleteAssetWrapper);
        }

        // 删除道具
        propMapper.deleteById(propId);

        log.info("道具已删除: propId={}", propId);
    }

    /**
     * 构建道具生成提示词（强调透明背景）
     */
    private String buildPropPrompt(Prop prop, String styleKeywords) {
        StringBuilder prompt = new StringBuilder();

        // 道具主体
        prompt.append("Product photography, isolated object, ");
        prompt.append(prop.getPropName() != null ? prop.getPropName() : "");
        prompt.append(", ");

        // 道具描述
        if (prop.getDescription() != null && !prop.getDescription().isEmpty()) {
            prompt.append(prop.getDescription());
            prompt.append(", ");
        }

        // 道具类型
        if (prop.getPropType() != null) {
            prompt.append(prop.getPropType());
            prompt.append(", ");
        }

        // 颜色
        if (prop.getColor() != null) {
            prompt.append(prop.getColor());
            prompt.append(" color, ");
        }

        // 尺寸
        if (prop.getSize() != null) {
            prompt.append(prop.getSize());
            prompt.append(" size, ");
        }

        // 自定义提示词
        if (prop.getCustomPrompt() != null && !prop.getCustomPrompt().isEmpty()) {
            prompt.append(prop.getCustomPrompt());
            prompt.append(", ");
        }

        // 风格关键词
        if (styleKeywords != null && !styleKeywords.isEmpty()) {
            prompt.append(styleKeywords);
            prompt.append(", ");
        }

        // 透明背景关键词（重要）
        prompt.append("transparent background, isolated on white background, clean cutout, ");
        // 排除人物/手部，确保只生成单品道具
        prompt.append("no hands, no hands holding, no one holding, no person holding, no fingers, ");
        prompt.append("no people, no characters, standalone prop, single object, ");
        prompt.append("high quality, detailed, professional product shot, no shadows, centered");

        return prompt.toString();
    }

    /**
     * 获取下一个版本号
     */
    private int getNextVersion(Long propId) {
        LambdaQueryWrapper<PropAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PropAsset::getPropId, propId)
                .orderByDesc(PropAsset::getVersion)
                .last("LIMIT 1");
        PropAsset latestAsset = propAssetMapper.selectOne(wrapper);
        return latestAsset != null ? latestAsset.getVersion() + 1 : 1;
    }

    /**
     * 转换为VO
     */
    /**
     * 转换为VO（使用预先查询的资产列表，避免N+1查询）
     */
    private PropDetailVO convertToVOWithAssets(Prop prop, List<PropAsset> assets) {
        PropDetailVO vo = new PropDetailVO();
        BeanUtils.copyProperties(prop, vo);

        List<PropDetailVO.PropAssetVO> assetVOs = assets.stream()
                .map(this::convertAssetToVO)
                .collect(Collectors.toList());
        vo.setAssets(assetVOs);

        // 设置激活资产URL
        for (PropDetailVO.PropAssetVO assetVO : assetVOs) {
            if (assetVO.getIsActive() != null && assetVO.getIsActive() == 1) {
                vo.setActiveAssetUrl(assetVO.getFilePath());
                vo.setTransparentUrl(assetVO.getTransparentPath());
                break;
            }
        }

        return vo;
    }

    /**
     * 转换为VO（单个查询，用于详情页）
     */
    private PropDetailVO convertToVO(Prop prop) {
        PropDetailVO vo = new PropDetailVO();
        BeanUtils.copyProperties(prop, vo);

        // 获取资产列表
        LambdaQueryWrapper<PropAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PropAsset::getPropId, prop.getId())
                .orderByDesc(PropAsset::getIsActive)
                .orderByDesc(PropAsset::getVersion);
        List<PropAsset> assets = propAssetMapper.selectList(wrapper);

        List<PropDetailVO.PropAssetVO> assetVOs = assets.stream()
                .map(this::convertAssetToVO)
                .collect(Collectors.toList());
        vo.setAssets(assetVOs);

        // 检查最新版本是否正在生成中（版本最高且 status=0）
        if (!assetVOs.isEmpty()) {
            PropDetailVO.PropAssetVO latestAsset = assetVOs.stream()
                    .max((a, b) -> Integer.compare(a.getVersion(), b.getVersion()))
                    .orElse(null);
            if (latestAsset != null && latestAsset.getStatus() != null && latestAsset.getStatus() == 0) {
                vo.setStatus(0);
            }
        }

        // 设置激活资产URL
        for (PropDetailVO.PropAssetVO assetVO : assetVOs) {
            if (assetVO.getIsActive() != null && assetVO.getIsActive() == 1) {
                vo.setActiveAssetUrl(assetVO.getFilePath());
                vo.setTransparentUrl(assetVO.getTransparentPath());
                break;
            }
        }

        return vo;
    }

    /**
     * 转换资产为VO
     */
    private PropDetailVO.PropAssetVO convertAssetToVO(PropAsset asset) {
        PropDetailVO.PropAssetVO vo = new PropDetailVO.PropAssetVO();
        BeanUtils.copyProperties(asset, vo);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createProp(Long seriesId, Long episodeId, String propName, String quality, String customPrompt) {
        log.info("手动创建道具: seriesId={}, episodeId={}, propName={}, quality={}, customPrompt={}",
                seriesId, episodeId, propName, quality, customPrompt);

        // 获取系列信息
        Series series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new BusinessException("系列不存在");
        }

        // 检查同一系列下是否已存在同名道具
        LambdaQueryWrapper<Prop> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Prop::getSeriesId, seriesId)
                .eq(Prop::getPropName, propName);
        Prop existingProp = propMapper.selectOne(wrapper);

        if (existingProp != null) {
            // 已存在同名道具，先同步创建占位资产记录，然后异步生成
            Long propId = existingProp.getId();
            log.info("道具名称已存在，触发重新生成: propId={}, propName={}", propId, propName);

            // 更新道具状态为生成中
            existingProp.setStatus(PropStatus.GENERATING.getCode());
            if (quality != null && !quality.isEmpty()) {
                existingProp.setQuality(quality);
            }
            existingProp.setUpdatedAt(LocalDateTime.now());
            propMapper.updateById(existingProp);

            // 获取下一个版本号并创建占位资产记录
            int nextVersion = getNextVersion(propId);
            PropAsset placeholderAsset = new PropAsset();
            placeholderAsset.setPropId(propId);
            placeholderAsset.setAssetType("item");
            placeholderAsset.setViewType("main");
            placeholderAsset.setVersion(nextVersion);
            placeholderAsset.setStatus(0); // 生成中
            placeholderAsset.setIsActive(0); // 先不激活
            placeholderAsset.setFileName(propName + "_v" + nextVersion + "_generating.png");
            placeholderAsset.setCreatedAt(LocalDateTime.now());
            placeholderAsset.setUpdatedAt(LocalDateTime.now());
            propAssetMapper.insert(placeholderAsset);

            log.info("已创建占位资产记录: propId={}, version={}, assetId={}", propId, nextVersion, placeholderAsset.getId());

            // 异步生成图片（使用配置的线程池确保立即执行）
            final Long finalPropId = propId;
            final Long finalEpisodeId = episodeId;
            final String finalQuality = quality;
            final String finalCustomPrompt = customPrompt;
            imageGenerateExecutor.execute(() -> regeneratePropWithLLMPrompt(finalPropId, finalEpisodeId, finalQuality, finalCustomPrompt));
            return propId;
        }

        // 创建道具记录（先创建，后续异步生成）
        Prop prop = new Prop();
        prop.setSeriesId(seriesId);
        prop.setPropName(propName);
        prop.setPropCode("MANUAL_" + System.currentTimeMillis());
        prop.setStyleKeywords(series.getStyleKeywords());
        prop.setAspectRatio("1:1"); // 道具固定 1:1
        prop.setQuality(quality != null ? quality : "2k");
        prop.setStatus(PropStatus.GENERATING.getCode());
        prop.setCreatedAt(LocalDateTime.now());
        prop.setUpdatedAt(LocalDateTime.now());
        propMapper.insert(prop);

        log.info("道具创建成功: propId={}", prop.getId());

        // 异步生成提示词和图片（使用配置的线程池确保立即执行）
        final Long propId = prop.getId();
        final Long finalEpisodeId = episodeId;
        final String finalQuality = quality;
        final String finalCustomPrompt = customPrompt;
        imageGenerateExecutor.execute(() -> generatePropWithLLMPrompt(propId, finalEpisodeId, finalQuality, finalCustomPrompt));

        return prop.getId();
    }

    /**
     * 异步生成道具（LLM提示词 + 图片生成）
     */
    @Async("imageGenerateExecutor")
    public void generatePropWithLLMPrompt(Long propId, Long episodeId, String quality, String customPrompt) {
        log.info("开始异步生成道具: propId={}, customPrompt={}", propId, customPrompt != null);

        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            log.error("道具不存在: propId={}", propId);
            return;
        }

        try {
            String prompt;
            String userPrompt = null;  // 用户输入的自定义提示词
            if (customPrompt != null && !customPrompt.trim().isEmpty()) {
                // 使用用户提供的自定义提示词
                prompt = customPrompt;
                userPrompt = customPrompt;
                log.info("使用自定义提示词: {}", prompt);
            } else {
                // 使用 LLM 生成提示词
                prompt = imagePromptGenerateService.generatePropPrompt(prop.getSeriesId(), prop.getPropName(), episodeId);
                log.info("LLM 生成的道具提示词: {}", prompt);
            }

            // 更新道具：description存实际使用的提示词，customPrompt只存用户输入的
            prop.setDescription(prompt);
            prop.setCustomPrompt(userPrompt);  // 只存用户输入的，没输入则为null
            prop.setUpdatedAt(LocalDateTime.now());
            propMapper.updateById(prop);

            // 生成图片
            generatePropAssetsWithPrompt(propId, prompt, quality);
        } catch (Exception e) {
            log.error("道具生成失败: propId={}", propId, e);
            prop.setStatus(PropStatus.PENDING_REVIEW.getCode());
            prop.setUpdatedAt(LocalDateTime.now());
            propMapper.updateById(prop);
        }
    }

    /**
     * 异步重新生成道具（LLM提示词 + 图片生成，版本+1）
     */
    @Async("imageGenerateExecutor")
    public void regeneratePropWithLLMPrompt(Long propId, Long episodeId, String quality, String customPrompt) {
        log.info("开始异步重新生成道具: propId={}, customPrompt={}", propId, customPrompt != null);

        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            log.error("道具不存在: propId={}", propId);
            return;
        }

        try {
            // 更新清晰度
            if (quality != null && !quality.isEmpty()) {
                prop.setQuality(quality);
            }
            prop.setStatus(PropStatus.GENERATING.getCode());
            prop.setUpdatedAt(LocalDateTime.now());
            propMapper.updateById(prop);

            String prompt;
            String userPrompt = null;  // 用户输入的自定义提示词
            if (customPrompt != null && !customPrompt.trim().isEmpty()) {
                // 使用用户提供的自定义提示词
                prompt = customPrompt;
                userPrompt = customPrompt;
                log.info("使用自定义提示词: {}", prompt);
            } else {
                // 使用 LLM 生成提示词
                prompt = imagePromptGenerateService.generatePropPrompt(prop.getSeriesId(), prop.getPropName(), episodeId);
                log.info("LLM 生成的道具提示词: {}", prompt);
            }

            // 更新道具：description存实际使用的提示词，customPrompt只存用户输入的
            prop.setDescription(prompt);
            prop.setCustomPrompt(userPrompt);  // 只存用户输入的，没输入则为null
            prop.setUpdatedAt(LocalDateTime.now());
            propMapper.updateById(prop);

            // 调用重新生成逻辑（版本+1）
            regeneratePropAsset(propId, prompt, quality);
        } catch (Exception e) {
            log.error("道具重新生成失败: propId={}", propId, e);
            prop.setStatus(PropStatus.PENDING_REVIEW.getCode());
            prop.setUpdatedAt(LocalDateTime.now());
            propMapper.updateById(prop);
        }
    }

    /**
     * 使用指定提示词生成道具资产
     */
    @Async("imageGenerateExecutor")
    public void generatePropAssetsWithPrompt(Long propId, String prompt, String quality) {
        log.info("开始生成道具资产: propId={}", propId);

        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            log.error("道具不存在: propId={}", propId);
            return;
        }

        try {
            // 创建资产记录
            PropAsset asset = new PropAsset();
            asset.setPropId(propId);
            asset.setAssetType("item");
            asset.setViewType("main");
            asset.setVersion(1);
            asset.setStatus(0);
            asset.setIsActive(1);
            asset.setCreatedAt(LocalDateTime.now());
            asset.setUpdatedAt(LocalDateTime.now());
            propAssetMapper.insert(asset);

            // 调用图片生成服务
            ImageGenerateRequest request = new ImageGenerateRequest();
            request.setCustomPrompt(prompt);
            request.setAspectRatio("1:1"); // 道具固定 1:1
            request.setQuality(quality != null ? quality : "2k");

            long startTime = System.currentTimeMillis();
            ImageGenerateResponse response = imageGenerateService.generatePropImage(request);
            long generationTime = System.currentTimeMillis() - startTime;

            if (response != null && response.getImageUrl() != null) {
                // 上传到OSS
                String ossUrl = ossService.uploadImageFromUrl(response.getImageUrl(), "props");

                // 更新资产记录
                asset.setFilePath(ossUrl);
                asset.setTransparentPath(ossUrl);
                asset.setThumbnailPath(ossUrl);
                asset.setFileName(prop.getPropName() + "_v1.png");
                asset.setStatus(1);
                asset.setUpdatedAt(LocalDateTime.now());
                propAssetMapper.updateById(asset);

                // 保存元数据
                PropAssetMetadata metadata = new PropAssetMetadata();
                metadata.setAssetId(asset.getId());
                metadata.setPrompt(prompt);
                metadata.setUserPrompt(prop.getDescription());
                metadata.setSeed(response.getSeed());
                metadata.setModelVersion("seedream-5.0");
                metadata.setAspectRatio("1:1");
                metadata.setGenerationTimeMs(generationTime);
                metadata.setCreatedAt(LocalDateTime.now());
                propAssetMetadataMapper.insert(metadata);

                // 更新道具状态为待审核
                prop.setStatus(PropStatus.PENDING_REVIEW.getCode());
                prop.setUpdatedAt(LocalDateTime.now());
                propMapper.updateById(prop);

                log.info("道具资产生成完成: propId={}, assetId={}", propId, asset.getId());
            } else {
                throw new RuntimeException("图片生成失败: 响应为空");
            }
        } catch (Exception e) {
            log.error("道具资产生成失败: propId={}", propId, e);
            prop.setStatus(PropStatus.GENERATING.getCode());
            prop.setUpdatedAt(LocalDateTime.now());
            propMapper.updateById(prop);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollbackToVersion(Long propId, Long assetId) {
        log.info("回滚道具资产版本: propId={}, assetId={}", propId, assetId);

        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            throw new BusinessException("道具不存在");
        }

        // 检查是否已锁定
        if (PropStatus.LOCKED.getCode().equals(prop.getStatus())) {
            throw new BusinessException("道具已锁定，无法回滚");
        }

        // 检查目标资产是否存在且属于该道具
        PropAsset targetAsset = propAssetMapper.selectById(assetId);
        if (targetAsset == null || !propId.equals(targetAsset.getPropId())) {
            throw new BusinessException("目标资产不存在或不属于该道具");
        }

        // 检查目标资产是否已完成
        if (targetAsset.getStatus() != 1) {
            throw new BusinessException("只能回滚到已完成的版本");
        }

        // 停用所有资产
        LambdaQueryWrapper<PropAsset> updateWrapper = new LambdaQueryWrapper<>();
        updateWrapper.eq(PropAsset::getPropId, propId);
        PropAsset updateAsset = new PropAsset();
        updateAsset.setIsActive(0);
        updateAsset.setUpdatedAt(LocalDateTime.now());
        propAssetMapper.update(updateAsset, updateWrapper);

        // 激活目标资产
        targetAsset.setIsActive(1);
        targetAsset.setUpdatedAt(LocalDateTime.now());
        propAssetMapper.updateById(targetAsset);

        log.info("道具资产回滚成功: propId={}, assetId={}, version={}", propId, assetId, targetAsset.getVersion());
    }
}
