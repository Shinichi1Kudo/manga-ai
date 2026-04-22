package com.manga.ai.scene.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.manga.ai.common.enums.SceneStatus;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.service.OssService;
import com.manga.ai.image.dto.ImageGenerateRequest;
import com.manga.ai.image.dto.ImageGenerateResponse;
import com.manga.ai.image.service.ImageGenerateService;
import com.manga.ai.llm.service.ImagePromptGenerateService;
import com.manga.ai.scene.dto.SceneDetailVO;
import com.manga.ai.scene.entity.Scene;
import com.manga.ai.scene.entity.SceneAsset;
import com.manga.ai.scene.entity.SceneAssetMetadata;
import com.manga.ai.scene.mapper.SceneMapper;
import com.manga.ai.scene.mapper.SceneAssetMapper;
import com.manga.ai.scene.mapper.SceneAssetMetadataMapper;
import com.manga.ai.scene.service.SceneService;
import com.manga.ai.series.entity.Series;
import com.manga.ai.series.mapper.SeriesMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 场景服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SceneServiceImpl implements SceneService {

    private final SceneMapper sceneMapper;
    private final SceneAssetMapper sceneAssetMapper;
    private final SceneAssetMetadataMapper sceneAssetMetadataMapper;
    private final SeriesMapper seriesMapper;
    private final ImageGenerateService imageGenerateService;
    private final OssService ossService;
    private final ImagePromptGenerateService imagePromptGenerateService;

    @Override
    public List<SceneDetailVO> getScenesBySeriesId(Long seriesId) {
        // 查询所有场景
        LambdaQueryWrapper<Scene> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Scene::getSeriesId, seriesId)
                .orderByAsc(Scene::getCreatedAt);
        List<Scene> scenes = sceneMapper.selectList(wrapper);

        if (scenes.isEmpty()) {
            return List.of();
        }

        // 批量查询所有资产（解决N+1问题）
        List<Long> sceneIds = scenes.stream().map(Scene::getId).collect(Collectors.toList());
        LambdaQueryWrapper<SceneAsset> assetWrapper = new LambdaQueryWrapper<>();
        assetWrapper.in(SceneAsset::getSceneId, sceneIds)
                .orderByDesc(SceneAsset::getIsActive)
                .orderByDesc(SceneAsset::getVersion);
        List<SceneAsset> allAssets = sceneAssetMapper.selectList(assetWrapper);

        // 按场景ID分组
        Map<Long, List<SceneAsset>> assetsBySceneId = allAssets.stream()
                .collect(Collectors.groupingBy(SceneAsset::getSceneId));

        // 组装VO
        return scenes.stream()
                .map(scene -> convertToVOWithAssets(scene, assetsBySceneId.getOrDefault(scene.getId(), List.of())))
                .collect(Collectors.toList());
    }

    @Override
    public SceneDetailVO getSceneDetail(Long sceneId) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BusinessException("场景不存在");
        }
        return convertToVO(scene);
    }

    @Override
    @Async("imageGenerateExecutor")
    public void generateSceneAssets(Long sceneId) {
        log.info("开始生成场景资产: sceneId={}", sceneId);

        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            log.error("场景不存在: sceneId={}", sceneId);
            return;
        }

        try {
            // 更新状态为生成中
            scene.setStatus(SceneStatus.GENERATING.getCode());
            scene.setUpdatedAt(LocalDateTime.now());
            sceneMapper.updateById(scene);

            // 获取系列信息（用于获取风格关键词）
            Series series = seriesMapper.selectById(scene.getSeriesId());
            String styleKeywords = series != null ? series.getStyleKeywords() : null;

            // 构建场景生成提示词
            String prompt = buildScenePrompt(scene, styleKeywords);

            // 创建资产记录
            SceneAsset asset = new SceneAsset();
            asset.setSceneId(sceneId);
            asset.setAssetType("background");
            asset.setViewType("main");
            asset.setVersion(1);
            asset.setStatus(0); // 生成中
            asset.setIsActive(1);
            asset.setCreatedAt(LocalDateTime.now());
            asset.setUpdatedAt(LocalDateTime.now());
            sceneAssetMapper.insert(asset);

            // 调用图片生成服务
            ImageGenerateRequest request = new ImageGenerateRequest();
            request.setCustomPrompt(prompt);
            request.setAspectRatio("16:9"); // 场景使用宽屏比例
            request.setQuality("hd");

            long startTime = System.currentTimeMillis();
            ImageGenerateResponse response = imageGenerateService.generateSceneImage(request);
            long generationTime = System.currentTimeMillis() - startTime;

            if (response != null && response.getImageUrl() != null) {
                // 上传到OSS
                String ossUrl = ossService.uploadImageFromUrl(response.getImageUrl(), "scenes");

                // 更新资产记录
                asset.setFilePath(ossUrl);
                asset.setThumbnailPath(ossUrl); // 缩略图暂用原图
                asset.setFileName(scene.getSceneName() + "_v1.png");
                asset.setStatus(1); // 已完成
                asset.setUpdatedAt(LocalDateTime.now());
                sceneAssetMapper.updateById(asset);

                // 保存元数据
                SceneAssetMetadata metadata = new SceneAssetMetadata();
                metadata.setAssetId(asset.getId());
                metadata.setPrompt(prompt);
                metadata.setUserPrompt(scene.getDescription());
                metadata.setSeed(response.getSeed());
                metadata.setModelVersion("seedream-5.0");
                metadata.setAspectRatio("16:9");
                metadata.setGenerationTimeMs(generationTime);
                metadata.setCreatedAt(LocalDateTime.now());
                sceneAssetMetadataMapper.insert(metadata);

                // 更新场景状态为待审核
                scene.setStatus(SceneStatus.PENDING_REVIEW.getCode());
                scene.setUpdatedAt(LocalDateTime.now());
                sceneMapper.updateById(scene);

                log.info("场景资产生成完成: sceneId={}, assetId={}", sceneId, asset.getId());
            } else {
                throw new RuntimeException("图片生成失败: 响应为空");
            }

        } catch (Exception e) {
            log.error("场景资产生成失败: sceneId={}", sceneId, e);
            scene.setStatus(SceneStatus.GENERATING.getCode()); // 保持生成中状态，允许重试
            scene.setUpdatedAt(LocalDateTime.now());
            sceneMapper.updateById(scene);
        }
    }

    @Override
    @Async("imageGenerateExecutor")
    public void regenerateSceneAsset(Long sceneId, String customPrompt, String aspectRatio, String quality) {
        log.info("重新生成场景资产: sceneId={}, aspectRatio={}, quality={}, customPrompt={}",
                sceneId, aspectRatio, quality, customPrompt != null);

        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            log.error("场景不存在: sceneId={}", sceneId);
            return;
        }

        // 检查是否已锁定
        if (SceneStatus.LOCKED.getCode().equals(scene.getStatus())) {
            log.warn("场景已锁定，无法重新生成: sceneId={}", sceneId);
            return;
        }

        // 更新比例和清晰度（如果提供了新值）
        if (aspectRatio != null && !aspectRatio.isEmpty()) {
            scene.setAspectRatio(aspectRatio);
        }
        if (quality != null && !quality.isEmpty()) {
            scene.setQuality(quality);
        }

        // 创建新资产记录（在外部声明以便在catch中访问）
        SceneAsset asset = null;
        try {
            // 如果提供了自定义提示词，更新场景
            if (customPrompt != null && !customPrompt.trim().isEmpty()) {
                scene.setCustomPrompt(customPrompt);
            }

            // 设置状态为生成中
            scene.setStatus(SceneStatus.GENERATING.getCode());
            scene.setUpdatedAt(LocalDateTime.now());
            sceneMapper.updateById(scene);

            // 获取系列信息
            Series series = seriesMapper.selectById(scene.getSeriesId());
            String styleKeywords = series != null ? series.getStyleKeywords() : null;

            // 构建提示词
            String prompt = buildScenePrompt(scene, styleKeywords);

            // 获取下一个版本号
            int nextVersion = getNextVersion(sceneId);

            // 使用场景实体的比例和清晰度（已更新）
            String finalAspectRatio = scene.getAspectRatio() != null ? scene.getAspectRatio() : "16:9";
            String finalQuality = scene.getQuality() != null ? scene.getQuality() : "2k";

            // 创建新资产记录
            asset = new SceneAsset();
            asset.setSceneId(sceneId);
            asset.setAssetType("background");
            asset.setViewType("main");
            asset.setVersion(nextVersion);
            asset.setStatus(0);
            asset.setIsActive(0); // 先不激活，生成成功后再激活
            asset.setCreatedAt(LocalDateTime.now());
            asset.setUpdatedAt(LocalDateTime.now());
            sceneAssetMapper.insert(asset);

            // 调用图片生成服务
            ImageGenerateRequest request = new ImageGenerateRequest();
            request.setCustomPrompt(prompt);
            request.setAspectRatio(finalAspectRatio);
            request.setQuality(finalQuality);

            long startTime = System.currentTimeMillis();
            ImageGenerateResponse response = imageGenerateService.generateSceneImage(request);
            long generationTime = System.currentTimeMillis() - startTime;

            if (response != null && response.getImageUrl() != null) {
                // 上传到OSS
                String ossUrl = ossService.uploadImageFromUrl(response.getImageUrl(), "scenes");

                // 停用旧资产
                LambdaQueryWrapper<SceneAsset> updateWrapper = new LambdaQueryWrapper<>();
                updateWrapper.eq(SceneAsset::getSceneId, sceneId);
                SceneAsset updateAsset = new SceneAsset();
                updateAsset.setIsActive(0);
                updateAsset.setUpdatedAt(LocalDateTime.now());
                sceneAssetMapper.update(updateAsset, updateWrapper);

                // 激活新资产
                asset.setFilePath(ossUrl);
                asset.setThumbnailPath(ossUrl);
                asset.setFileName(scene.getSceneName() + "_v" + nextVersion + ".png");
                asset.setStatus(1);
                asset.setIsActive(1);
                asset.setUpdatedAt(LocalDateTime.now());
                sceneAssetMapper.updateById(asset);

                // 保存元数据
                SceneAssetMetadata metadata = new SceneAssetMetadata();
                metadata.setAssetId(asset.getId());
                metadata.setPrompt(prompt);
                metadata.setUserPrompt(customPrompt != null ? customPrompt : scene.getDescription());
                metadata.setSeed(response.getSeed());
                metadata.setModelVersion("seedream-5.0");
                metadata.setAspectRatio(finalAspectRatio);
                metadata.setGenerationTimeMs(generationTime);
                metadata.setCreatedAt(LocalDateTime.now());
                sceneAssetMetadataMapper.insert(metadata);

                // 更新场景状态为待审核
                scene.setStatus(SceneStatus.PENDING_REVIEW.getCode());
                scene.setUpdatedAt(LocalDateTime.now());
                sceneMapper.updateById(scene);

                log.info("场景资产重新生成完成: sceneId={}, assetId={}, version={}", sceneId, asset.getId(), nextVersion);
            } else {
                throw new RuntimeException("图片生成失败: 响应为空");
            }

        } catch (Exception e) {
            log.error("场景资产重新生成失败: sceneId={}", sceneId, e);
            // 生成失败，恢复到待审核状态
            scene.setStatus(SceneStatus.PENDING_REVIEW.getCode());
            scene.setUpdatedAt(LocalDateTime.now());
            sceneMapper.updateById(scene);

            // 删除生成失败的资产记录（如果已创建）
            if (asset != null && asset.getId() != null) {
                sceneAssetMapper.deleteById(asset.getId());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reviewScene(Long sceneId, boolean approved) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BusinessException("场景不存在");
        }

        if (approved) {
            scene.setStatus(SceneStatus.LOCKED.getCode());
        } else {
            scene.setStatus(SceneStatus.PENDING_REVIEW.getCode());
        }
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);

        log.info("场景审核完成: sceneId={}, approved={}", sceneId, approved);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void lockScene(Long sceneId) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BusinessException("场景不存在");
        }

        scene.setStatus(SceneStatus.LOCKED.getCode());
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);

        log.info("场景已锁定: sceneId={}", sceneId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlockScene(Long sceneId) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BusinessException("场景不存在");
        }

        scene.setStatus(SceneStatus.PENDING_REVIEW.getCode());
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);

        log.info("场景已解锁: sceneId={}", sceneId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSceneName(Long sceneId, String sceneName) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BusinessException("场景不存在");
        }

        if (sceneName == null || sceneName.trim().isEmpty()) {
            throw new BusinessException("场景名称不能为空");
        }

        scene.setSceneName(sceneName.trim());
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);

        log.info("场景名称已更新: sceneId={}, sceneName={}", sceneId, sceneName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteScene(Long sceneId) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BusinessException("场景不存在");
        }

        // 删除关联的资产元数据
        LambdaQueryWrapper<SceneAsset> assetWrapper = new LambdaQueryWrapper<>();
        assetWrapper.eq(SceneAsset::getSceneId, sceneId);
        List<SceneAsset> assets = sceneAssetMapper.selectList(assetWrapper);

        for (SceneAsset asset : assets) {
            LambdaQueryWrapper<SceneAssetMetadata> metadataWrapper = new LambdaQueryWrapper<>();
            metadataWrapper.eq(SceneAssetMetadata::getAssetId, asset.getId());
            sceneAssetMetadataMapper.delete(metadataWrapper);
        }

        // 删除关联的资产
        sceneAssetMapper.delete(assetWrapper);

        // 删除场景
        sceneMapper.deleteById(sceneId);

        log.info("场景已删除: sceneId={}", sceneId);
    }

    /**
     * 构建场景生成提示词
     */
    private String buildScenePrompt(Scene scene, String styleKeywords) {
        StringBuilder prompt = new StringBuilder();

        // 场景类型和环境
        prompt.append("Scene background, ");
        prompt.append(scene.getSceneName() != null ? scene.getSceneName() : "");
        prompt.append(", ");

        // 场景描述
        if (scene.getDescription() != null && !scene.getDescription().isEmpty()) {
            prompt.append(scene.getDescription());
            prompt.append(", ");
        }

        // 地点类型
        if (scene.getLocationType() != null) {
            prompt.append(scene.getLocationType());
            prompt.append(" setting, ");
        }

        // 时间
        if (scene.getTimeOfDay() != null) {
            prompt.append(scene.getTimeOfDay());
            prompt.append(" lighting, ");
        }

        // 天气
        if (scene.getWeather() != null) {
            prompt.append(scene.getWeather());
            prompt.append(" weather, ");
        }

        // 自定义提示词
        if (scene.getCustomPrompt() != null && !scene.getCustomPrompt().isEmpty()) {
            prompt.append(scene.getCustomPrompt());
            prompt.append(", ");
        }

        // 风格关键词
        if (styleKeywords != null && !styleKeywords.isEmpty()) {
            prompt.append(styleKeywords);
            prompt.append(", ");
        }

        // 质量提升词和人物排除（非常重要）
        prompt.append("high quality, detailed, cinematic, professional, 4K, ");
        prompt.append("no people, no characters, no figures, no humans, empty scene, background only, no portraits");

        return prompt.toString();
    }

    /**
     * 获取下一个版本号
     */
    private int getNextVersion(Long sceneId) {
        LambdaQueryWrapper<SceneAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SceneAsset::getSceneId, sceneId)
                .orderByDesc(SceneAsset::getVersion)
                .last("LIMIT 1");
        SceneAsset latestAsset = sceneAssetMapper.selectOne(wrapper);
        return latestAsset != null ? latestAsset.getVersion() + 1 : 1;
    }

    /**
     * 转换为VO（使用预先查询的资产列表，避免N+1查询）
     */
    private SceneDetailVO convertToVOWithAssets(Scene scene, List<SceneAsset> assets) {
        SceneDetailVO vo = new SceneDetailVO();
        BeanUtils.copyProperties(scene, vo);

        List<SceneDetailVO.SceneAssetVO> assetVOs = assets.stream()
                .map(this::convertAssetToVO)
                .collect(Collectors.toList());
        vo.setAssets(assetVOs);

        // 设置激活资产URL
        for (SceneDetailVO.SceneAssetVO assetVO : assetVOs) {
            if (assetVO.getIsActive() != null && assetVO.getIsActive() == 1) {
                vo.setActiveAssetUrl(assetVO.getFilePath());
                break;
            }
        }

        return vo;
    }

    /**
     * 转换为VO（单个查询，用于详情页）
     */
    private SceneDetailVO convertToVO(Scene scene) {
        SceneDetailVO vo = new SceneDetailVO();
        BeanUtils.copyProperties(scene, vo);

        // 获取资产列表
        LambdaQueryWrapper<SceneAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SceneAsset::getSceneId, scene.getId())
                .orderByDesc(SceneAsset::getIsActive)
                .orderByDesc(SceneAsset::getVersion);
        List<SceneAsset> assets = sceneAssetMapper.selectList(wrapper);

        List<SceneDetailVO.SceneAssetVO> assetVOs = assets.stream()
                .map(this::convertAssetToVO)
                .collect(Collectors.toList());
        vo.setAssets(assetVOs);

        // 设置激活资产URL
        for (SceneDetailVO.SceneAssetVO assetVO : assetVOs) {
            if (assetVO.getIsActive() != null && assetVO.getIsActive() == 1) {
                vo.setActiveAssetUrl(assetVO.getFilePath());
                break;
            }
        }

        return vo;
    }

    /**
     * 转换资产为VO
     */
    private SceneDetailVO.SceneAssetVO convertAssetToVO(SceneAsset asset) {
        SceneDetailVO.SceneAssetVO vo = new SceneDetailVO.SceneAssetVO();
        BeanUtils.copyProperties(asset, vo);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createScene(Long seriesId, Long episodeId, String sceneName, String aspectRatio, String quality) {
        log.info("手动创建场景: seriesId={}, episodeId={}, sceneName={}, aspectRatio={}, quality={}",
                seriesId, episodeId, sceneName, aspectRatio, quality);

        // 获取系列信息
        Series series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new BusinessException("系列不存在");
        }

        // 使用 LLM 生成提示词
        String prompt = imagePromptGenerateService.generateScenePrompt(seriesId, sceneName, episodeId);
        log.info("LLM 生成的场景提示词: {}", prompt);

        // 创建场景记录
        Scene scene = new Scene();
        scene.setSeriesId(seriesId);
        scene.setSceneName(sceneName);
        scene.setSceneCode("MANUAL_" + System.currentTimeMillis());
        scene.setDescription(prompt);
        scene.setCustomPrompt(prompt);
        scene.setStyleKeywords(series.getStyleKeywords());
        scene.setAspectRatio(aspectRatio != null ? aspectRatio : "16:9");
        scene.setQuality(quality != null ? quality : "2k");
        scene.setStatus(SceneStatus.GENERATING.getCode());
        scene.setCreatedAt(LocalDateTime.now());
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.insert(scene);

        log.info("场景创建成功: sceneId={}", scene.getId());

        // 异步生成图片
        generateSceneAssetsWithPrompt(scene.getId(), prompt, aspectRatio, quality);

        return scene.getId();
    }

    /**
     * 使用指定提示词生成场景资产
     */
    @Async("imageGenerateExecutor")
    public void generateSceneAssetsWithPrompt(Long sceneId, String prompt, String aspectRatio, String quality) {
        log.info("开始生成场景资产: sceneId={}", sceneId);

        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            log.error("场景不存在: sceneId={}", sceneId);
            return;
        }

        try {
            // 创建资产记录
            SceneAsset asset = new SceneAsset();
            asset.setSceneId(sceneId);
            asset.setAssetType("background");
            asset.setViewType("main");
            asset.setVersion(1);
            asset.setStatus(0);
            asset.setIsActive(1);
            asset.setCreatedAt(LocalDateTime.now());
            asset.setUpdatedAt(LocalDateTime.now());
            sceneAssetMapper.insert(asset);

            // 调用图片生成服务
            ImageGenerateRequest request = new ImageGenerateRequest();
            request.setCustomPrompt(prompt);
            request.setAspectRatio(aspectRatio != null ? aspectRatio : "16:9");
            request.setQuality(quality != null ? quality : "2k");

            long startTime = System.currentTimeMillis();
            ImageGenerateResponse response = imageGenerateService.generateSceneImage(request);
            long generationTime = System.currentTimeMillis() - startTime;

            if (response != null && response.getImageUrl() != null) {
                // 上传到OSS
                String ossUrl = ossService.uploadImageFromUrl(response.getImageUrl(), "scenes");

                // 更新资产记录
                asset.setFilePath(ossUrl);
                asset.setThumbnailPath(ossUrl);
                asset.setFileName(scene.getSceneName() + "_v1.png");
                asset.setStatus(1);
                asset.setUpdatedAt(LocalDateTime.now());
                sceneAssetMapper.updateById(asset);

                // 保存元数据
                SceneAssetMetadata metadata = new SceneAssetMetadata();
                metadata.setAssetId(asset.getId());
                metadata.setPrompt(prompt);
                metadata.setUserPrompt(scene.getDescription());
                metadata.setSeed(response.getSeed());
                metadata.setModelVersion("seedream-5.0");
                metadata.setAspectRatio(aspectRatio != null ? aspectRatio : "16:9");
                metadata.setGenerationTimeMs(generationTime);
                metadata.setCreatedAt(LocalDateTime.now());
                sceneAssetMetadataMapper.insert(metadata);

                // 更新场景状态为待审核
                scene.setStatus(SceneStatus.PENDING_REVIEW.getCode());
                scene.setUpdatedAt(LocalDateTime.now());
                sceneMapper.updateById(scene);

                log.info("场景资产生成完成: sceneId={}, assetId={}", sceneId, asset.getId());
            } else {
                throw new RuntimeException("图片生成失败: 响应为空");
            }
        } catch (Exception e) {
            log.error("场景资产生成失败: sceneId={}", sceneId, e);
            scene.setStatus(SceneStatus.GENERATING.getCode());
            scene.setUpdatedAt(LocalDateTime.now());
            sceneMapper.updateById(scene);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollbackToVersion(Long sceneId, Long assetId) {
        log.info("回滚场景资产版本: sceneId={}, assetId={}", sceneId, assetId);

        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BusinessException("场景不存在");
        }

        // 检查是否已锁定
        if (SceneStatus.LOCKED.getCode().equals(scene.getStatus())) {
            throw new BusinessException("场景已锁定，无法回滚");
        }

        // 检查目标资产是否存在且属于该场景
        SceneAsset targetAsset = sceneAssetMapper.selectById(assetId);
        if (targetAsset == null || !sceneId.equals(targetAsset.getSceneId())) {
            throw new BusinessException("目标资产不存在或不属于该场景");
        }

        // 检查目标资产是否已完成
        if (targetAsset.getStatus() != 1) {
            throw new BusinessException("只能回滚到已完成的版本");
        }

        // 停用所有资产
        LambdaQueryWrapper<SceneAsset> updateWrapper = new LambdaQueryWrapper<>();
        updateWrapper.eq(SceneAsset::getSceneId, sceneId);
        SceneAsset updateAsset = new SceneAsset();
        updateAsset.setIsActive(0);
        updateAsset.setUpdatedAt(LocalDateTime.now());
        sceneAssetMapper.update(updateAsset, updateWrapper);

        // 激活目标资产
        targetAsset.setIsActive(1);
        targetAsset.setUpdatedAt(LocalDateTime.now());
        sceneAssetMapper.updateById(targetAsset);

        log.info("场景资产回滚成功: sceneId={}, assetId={}, version={}", sceneId, assetId, targetAsset.getVersion());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetStuckStatus(Long sceneId) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BusinessException("场景不存在");
        }

        // 重置为待审核状态
        scene.setStatus(SceneStatus.PENDING_REVIEW.getCode());
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);

        log.info("场景状态已重置: sceneId={}", sceneId);
    }
}
