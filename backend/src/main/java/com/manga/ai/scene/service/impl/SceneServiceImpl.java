package com.manga.ai.scene.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.manga.ai.common.constants.CreditConstants;
import com.manga.ai.common.enums.CreditUsageType;
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
import com.manga.ai.user.service.UserService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 场景服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SceneServiceImpl implements SceneService {

    private static final List<String> ALLOWED_UPLOAD_IMAGE_TYPES = Arrays.asList(
            "image/jpeg", "image/png", "image/webp"
    );
    private static final long MAX_UPLOAD_IMAGE_SIZE = 5 * 1024 * 1024;

    private final SceneMapper sceneMapper;
    private final SceneAssetMapper sceneAssetMapper;
    private final SceneAssetMetadataMapper sceneAssetMetadataMapper;
    private final SeriesMapper seriesMapper;
    private final ImageGenerateService imageGenerateService;
    private final OssService ossService;
    private final ImagePromptGenerateService imagePromptGenerateService;
    private final UserService userService;

    @Qualifier("imageGenerateExecutor")
    private final Executor imageGenerateExecutor;

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

            SceneAsset asset = ensureGeneratingPlaceholderAsset(sceneId, scene.getSceneName());
            int actualVersion = asset.getVersion() != null ? asset.getVersion() : 1;

            // 调用图片生成服务
            ImageGenerateRequest request = new ImageGenerateRequest();
            request.setCustomPrompt(prompt);
            request.setAspectRatio("16:9"); // 场景使用宽屏比例
            request.setQuality("hd");
            request.setStyleKeywords(styleKeywords);

            long startTime = System.currentTimeMillis();
            ImageGenerateResponse response = imageGenerateService.generateSceneImage(request);
            long generationTime = System.currentTimeMillis() - startTime;

            if (response != null && response.getImageUrl() != null) {
                // 上传到OSS
                String ossUrl = ossService.uploadImageFromUrl(response.getImageUrl(), "scenes");

                deactivateSceneAssets(sceneId);

                // 更新资产记录
                asset.setFilePath(ossUrl);
                asset.setThumbnailPath(ossUrl); // 缩略图暂用原图
                asset.setFileName(scene.getSceneName() + "_v" + actualVersion + ".png");
                asset.setStatus(1); // 已完成
                asset.setIsActive(1);
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
    public void generateSceneAssetsWithCredit(Long sceneId, Long userId) {
        log.info("生成场景资产（含积分扣费）: sceneId={}, userId={}", sceneId, userId);

        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BusinessException("场景不存在");
        }

        // 扣除积分（场景生成1张图）
        int requiredCredits = CreditConstants.CREDITS_PER_IMAGE;
        if (userId == null) {
            userId = UserContextHolder.getUserId();
        }
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }
        userService.deductCredits(userId, requiredCredits, CreditUsageType.SCENE_CREATE.getCode(),
                "场景创建-" + scene.getSceneName(), sceneId, "SCENE");
        log.info("场景生成扣费: userId={}, sceneId={}, credits={}", userId, sceneId, requiredCredits);

        // 记录扣除的积分，并在接口返回前落库生成中状态和占位资产。
        scene.setDeductedCredits(requiredCredits);
        scene.setStatus(SceneStatus.GENERATING.getCode());
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);
        SceneAsset placeholderAsset = ensureGeneratingPlaceholderAsset(sceneId, scene.getSceneName());
        log.info("场景生成占位资产已就绪: sceneId={}, version={}, assetId={}",
                sceneId, placeholderAsset.getVersion(), placeholderAsset.getId());

        // 提交后台生成任务，避免 HTTP 请求线程同步等待火山接口。
        imageGenerateExecutor.execute(() -> generateSceneAssets(sceneId));
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

            asset = findLatestGeneratingPlaceholder(sceneId);

            int actualVersion;
            if (asset != null) {
                // 已存在占位资产，使用它
                actualVersion = asset.getVersion();
                log.info("使用已存在的占位资产: sceneId={}, version={}, assetId={}", sceneId, actualVersion, asset.getId());
            } else {
                // 没有占位资产，创建新资产记录
                actualVersion = nextVersion;
                asset = new SceneAsset();
                asset.setSceneId(sceneId);
                asset.setAssetType("background");
                asset.setViewType("main");
                asset.setVersion(actualVersion);
                asset.setStatus(0);
                asset.setIsActive(0);
                asset.setCreatedAt(LocalDateTime.now());
                asset.setUpdatedAt(LocalDateTime.now());
                sceneAssetMapper.insert(asset);
                log.info("创建新资产记录: sceneId={}, version={}, assetId={}", sceneId, actualVersion, asset.getId());
            }

            // 调用图片生成服务
            ImageGenerateRequest request = new ImageGenerateRequest();
            request.setCustomPrompt(prompt);
            request.setAspectRatio(finalAspectRatio);
            request.setQuality(finalQuality);
            request.setStyleKeywords(styleKeywords);

            long startTime = System.currentTimeMillis();
            ImageGenerateResponse response = imageGenerateService.generateSceneImage(request);
            long generationTime = System.currentTimeMillis() - startTime;

            if (response != null && response.getImageUrl() != null) {
                // 上传到OSS
                String ossUrl = ossService.uploadImageFromUrl(response.getImageUrl(), "scenes");

                deactivateSceneAssets(sceneId);

                // 激活新资产
                asset.setFilePath(ossUrl);
                asset.setThumbnailPath(ossUrl);
                asset.setFileName(scene.getSceneName() + "_v" + actualVersion + ".png");
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

                log.info("场景资产重新生成完成: sceneId={}, assetId={}, version={}", sceneId, asset.getId(), actualVersion);
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
    public void regenerateSceneAssetWithCredit(Long sceneId, String customPrompt, String aspectRatio, String quality, Long userId) {
        log.info("重新生成场景资产（含积分扣费）: sceneId={}, aspectRatio={}, quality={}", sceneId, aspectRatio, quality);

        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BusinessException("场景不存在");
        }

        // 检查是否已锁定
        if (SceneStatus.LOCKED.getCode().equals(scene.getStatus())) {
            throw new BusinessException("场景已锁定，无法重新生成");
        }

        // 扣除积分（场景生成1张图）
        int requiredCredits = CreditConstants.CREDITS_PER_IMAGE;
        if (userId == null) {
            userId = UserContextHolder.getUserId();
        }
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }
        userService.deductCredits(userId, requiredCredits, CreditUsageType.SCENE_CREATE.getCode(),
                "场景重新生成-" + scene.getSceneName(), sceneId, "SCENE");
        log.info("场景重新生成扣费: userId={}, sceneId={}, credits={}", userId, sceneId, requiredCredits);

        // 记录扣除的积分，并在接口返回前落库生成中状态和占位资产。
        scene.setDeductedCredits(requiredCredits);
        scene.setStatus(SceneStatus.GENERATING.getCode());
        if (aspectRatio != null && !aspectRatio.isEmpty()) {
            scene.setAspectRatio(aspectRatio);
        }
        if (quality != null && !quality.isEmpty()) {
            scene.setQuality(quality);
        }
        if (customPrompt != null && !customPrompt.trim().isEmpty()) {
            scene.setCustomPrompt(customPrompt);
        }
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);
        SceneAsset placeholderAsset = ensureGeneratingPlaceholderAsset(sceneId, scene.getSceneName());
        log.info("场景重新生成占位资产已就绪: sceneId={}, version={}, assetId={}",
                sceneId, placeholderAsset.getVersion(), placeholderAsset.getId());

        // 提交后台生成任务，避免 HTTP 请求线程同步等待火山接口。
        imageGenerateExecutor.execute(() -> regenerateSceneAsset(sceneId, customPrompt, aspectRatio, quality));
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

        // 查询关联的资产ID列表
        LambdaQueryWrapper<SceneAsset> assetWrapper = new LambdaQueryWrapper<>();
        assetWrapper.eq(SceneAsset::getSceneId, sceneId)
                .select(SceneAsset::getId);
        List<SceneAsset> assets = sceneAssetMapper.selectList(assetWrapper);

        if (!assets.isEmpty()) {
            // 批量删除资产元数据
            List<Long> assetIds = assets.stream()
                    .map(SceneAsset::getId)
                    .collect(Collectors.toList());
            LambdaQueryWrapper<SceneAssetMetadata> metadataWrapper = new LambdaQueryWrapper<>();
            metadataWrapper.in(SceneAssetMetadata::getAssetId, assetIds);
            sceneAssetMetadataMapper.delete(metadataWrapper);

            // 批量删除关联的资产
            LambdaQueryWrapper<SceneAsset> deleteAssetWrapper = new LambdaQueryWrapper<>();
            deleteAssetWrapper.eq(SceneAsset::getSceneId, sceneId);
            sceneAssetMapper.delete(deleteAssetWrapper);
        }

        // 删除场景
        sceneMapper.deleteById(sceneId);

        log.info("场景已删除: sceneId={}", sceneId);
    }

    /**
     * 构建场景生成提示词
     */
    private String buildScenePrompt(Scene scene, String styleKeywords) {
        // 获取系列信息，用于获取背景设定和剧本大纲
        Series series = seriesMapper.selectById(scene.getSeriesId());
        String background = series != null ? series.getBackground() : null;
        String outline = series != null ? series.getOutline() : null;

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

        // 背景设定/世界观
        if (background != null && !background.isEmpty()) {
            prompt.append("World setting: ");
            prompt.append(background);
            prompt.append(", ");
        }

        // 剧本大纲（用于理解故事背景）
        if (outline != null && !outline.isEmpty()) {
            prompt.append("Story context: ");
            prompt.append(outline.substring(0, Math.min(200, outline.length()))); // 限制长度避免过长
            if (outline.length() > 200) {
                prompt.append("...");
            }
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

        // 风格关键词（确保使用系列风格）
        if (styleKeywords != null && !styleKeywords.isEmpty()) {
            prompt.append(styleKeywords);
            prompt.append(", ");
        }

        // 质量提升词和人物排除（非常重要）
        prompt.append("high quality, detailed, cinematic, professional, 4K, ");
        prompt.append("no people, no characters, no figures, no humans, empty scene, background only, no portraits");

        return prompt.toString();
    }

    private String resolveSceneStyleKeywords(Scene scene) {
        if (scene != null && scene.getStyleKeywords() != null && !scene.getStyleKeywords().trim().isEmpty()) {
            return scene.getStyleKeywords();
        }
        Series series = scene != null ? seriesMapper.selectById(scene.getSeriesId()) : null;
        return series != null ? series.getStyleKeywords() : null;
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

    private SceneAsset createGeneratingPlaceholderAsset(Long sceneId, String sceneName, int version) {
        SceneAsset placeholderAsset = new SceneAsset();
        placeholderAsset.setSceneId(sceneId);
        placeholderAsset.setAssetType("background");
        placeholderAsset.setViewType("main");
        placeholderAsset.setVersion(version);
        placeholderAsset.setStatus(0);
        placeholderAsset.setIsActive(0);
        placeholderAsset.setFileName(sceneName + "_v" + version + "_generating.png");
        placeholderAsset.setCreatedAt(LocalDateTime.now());
        placeholderAsset.setUpdatedAt(LocalDateTime.now());
        sceneAssetMapper.insert(placeholderAsset);
        return placeholderAsset;
    }

    private SceneAsset findLatestGeneratingPlaceholder(Long sceneId) {
        LambdaQueryWrapper<SceneAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SceneAsset::getSceneId, sceneId)
                .eq(SceneAsset::getStatus, 0)
                .eq(SceneAsset::getIsActive, 0)
                .orderByDesc(SceneAsset::getVersion)
                .last("LIMIT 1");
        return sceneAssetMapper.selectOne(wrapper);
    }

    private SceneAsset ensureGeneratingPlaceholderAsset(Long sceneId, String sceneName) {
        SceneAsset asset = findLatestGeneratingPlaceholder(sceneId);
        if (asset != null) {
            return asset;
        }
        int nextVersion = getNextVersion(sceneId);
        return createGeneratingPlaceholderAsset(sceneId, sceneName, nextVersion);
    }

    private void deactivateSceneAssets(Long sceneId) {
        LambdaQueryWrapper<SceneAsset> updateWrapper = new LambdaQueryWrapper<>();
        updateWrapper.eq(SceneAsset::getSceneId, sceneId);
        SceneAsset updateAsset = new SceneAsset();
        updateAsset.setIsActive(0);
        updateAsset.setUpdatedAt(LocalDateTime.now());
        sceneAssetMapper.update(updateAsset, updateWrapper);
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

        // 检查最新版本是否正在生成中（版本最高且 status=0）
        if (!assetVOs.isEmpty()) {
            SceneDetailVO.SceneAssetVO latestAsset = assetVOs.stream()
                    .max((a, b) -> Integer.compare(a.getVersion(), b.getVersion()))
                    .orElse(null);
            if (latestAsset != null && latestAsset.getStatus() != null && latestAsset.getStatus() == 0) {
                vo.setStatus(0);
            }
        }

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

        // 检查最新版本是否正在生成中（版本最高且 status=0）
        if (!assetVOs.isEmpty()) {
            SceneDetailVO.SceneAssetVO latestAsset = assetVOs.stream()
                    .max((a, b) -> Integer.compare(a.getVersion(), b.getVersion()))
                    .orElse(null);
            if (latestAsset != null && latestAsset.getStatus() != null && latestAsset.getStatus() == 0) {
                vo.setStatus(0);
            }
        }

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
        // 刷新OSS URL
        vo.setFilePath(ossService.refreshUrl(asset.getFilePath()));
        vo.setThumbnailPath(ossService.refreshUrl(asset.getThumbnailPath()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createScene(Long seriesId, Long episodeId, String sceneName, String aspectRatio, String quality, String customPrompt) {
        log.info("手动创建场景: seriesId={}, episodeId={}, sceneName={}, aspectRatio={}, quality={}, customPrompt={}",
                seriesId, episodeId, sceneName, aspectRatio, quality, customPrompt);

        // 获取系列信息
        Series series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new BusinessException("系列不存在");
        }

        // 检查同一系列下是否已存在同名场景
        LambdaQueryWrapper<Scene> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Scene::getSeriesId, seriesId)
                .eq(Scene::getSceneName, sceneName);
        Scene existingScene = sceneMapper.selectOne(wrapper);

        if (existingScene != null) {
            // 已存在同名场景，先同步创建占位资产记录，然后异步生成
            Long sceneId = existingScene.getId();
            log.info("场景名称已存在，触发重新生成: sceneId={}, sceneName={}", sceneId, sceneName);

            // 更新场景状态为生成中
            existingScene.setStatus(SceneStatus.GENERATING.getCode());
            if (aspectRatio != null && !aspectRatio.isEmpty()) {
                existingScene.setAspectRatio(aspectRatio);
            }
            if (quality != null && !quality.isEmpty()) {
                existingScene.setQuality(quality);
            }
            existingScene.setUpdatedAt(LocalDateTime.now());
            sceneMapper.updateById(existingScene);

            int nextVersion = getNextVersion(sceneId);
            SceneAsset placeholderAsset = createGeneratingPlaceholderAsset(sceneId, sceneName, nextVersion);

            log.info("已创建占位资产记录: sceneId={}, version={}, assetId={}", sceneId, nextVersion, placeholderAsset.getId());

            // 异步生成图片（使用配置的线程池确保立即执行）
            final Long finalSceneId = sceneId;
            final Long finalEpisodeId = episodeId;
            final String finalAspectRatio = aspectRatio;
            final String finalQuality = quality;
            final String finalCustomPrompt = customPrompt;
            imageGenerateExecutor.execute(() -> regenerateSceneWithLLMPrompt(finalSceneId, finalEpisodeId, finalAspectRatio, finalQuality, finalCustomPrompt));
            return sceneId;
        }

        // 创建场景记录（先创建，后续异步生成）
        Scene scene = new Scene();
        scene.setSeriesId(seriesId);
        scene.setSceneName(sceneName);
        scene.setSceneCode("MANUAL_" + System.currentTimeMillis());
        scene.setStyleKeywords(series.getStyleKeywords());
        scene.setAspectRatio(aspectRatio != null ? aspectRatio : "16:9");
        scene.setQuality(quality != null ? quality : "2k");
        scene.setStatus(SceneStatus.GENERATING.getCode());
        scene.setCreatedAt(LocalDateTime.now());
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.insert(scene);

        log.info("场景创建成功: sceneId={}", scene.getId());

        SceneAsset placeholderAsset = createGeneratingPlaceholderAsset(scene.getId(), sceneName, 1);
        log.info("已创建新场景占位资产记录: sceneId={}, version=1, assetId={}", scene.getId(), placeholderAsset.getId());

        // 异步生成提示词和图片（使用配置的线程池确保立即执行）
        final Long sceneId = scene.getId();
        final Long finalEpisodeId = episodeId;
        final String finalAspectRatio = aspectRatio;
        final String finalQuality = quality;
        final String finalCustomPrompt = customPrompt;
        imageGenerateExecutor.execute(() -> generateSceneWithLLMPrompt(sceneId, finalEpisodeId, finalAspectRatio, finalQuality, finalCustomPrompt));

        return scene.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SceneDetailVO uploadSceneAsset(Long seriesId, Long episodeId, String sceneName, String aspectRatio, String quality, String customPrompt, MultipartFile file) {
        if (sceneName == null || sceneName.trim().isEmpty()) {
            throw new BusinessException("场景名称不能为空");
        }

        Series series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new BusinessException("系列不存在");
        }

        String normalizedAspectRatio = normalizeSceneUploadAspectRatio(aspectRatio);
        LambdaQueryWrapper<Scene> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Scene::getSeriesId, seriesId)
                .eq(Scene::getSceneName, sceneName.trim())
                .last("LIMIT 1");
        Scene scene = sceneMapper.selectOne(wrapper);

        if (scene == null) {
            scene = new Scene();
            scene.setSeriesId(seriesId);
            scene.setSceneName(sceneName.trim());
            scene.setSceneCode("UPLOAD_" + System.currentTimeMillis());
            scene.setStyleKeywords(series.getStyleKeywords());
            scene.setAspectRatio(normalizedAspectRatio);
            scene.setQuality((quality != null && !quality.isBlank()) ? quality : "2k");
            scene.setStatus(SceneStatus.PENDING_REVIEW.getCode());
            scene.setCreatedAt(LocalDateTime.now());
            scene.setUpdatedAt(LocalDateTime.now());
            sceneMapper.insert(scene);
        } else {
            scene.setAspectRatio(normalizedAspectRatio);
            if (quality != null && !quality.isBlank()) {
                scene.setQuality(quality);
            }
            if (customPrompt != null && !customPrompt.isBlank()) {
                scene.setCustomPrompt(customPrompt.trim());
            }
            scene.setStatus(SceneStatus.PENDING_REVIEW.getCode());
            scene.setUpdatedAt(LocalDateTime.now());
            sceneMapper.updateById(scene);
        }

        return uploadSceneAsset(scene.getId(), normalizedAspectRatio, customPrompt, file);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SceneDetailVO uploadSceneAsset(Long sceneId, String aspectRatio, String customPrompt, MultipartFile file) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BusinessException("场景不存在");
        }
        if (SceneStatus.LOCKED.getCode().equals(scene.getStatus())) {
            throw new BusinessException("场景已锁定，无法上传新版本");
        }

        String normalizedAspectRatio = normalizeSceneUploadAspectRatio(aspectRatio != null ? aspectRatio : scene.getAspectRatio());
        validateUploadedSceneImage(file, normalizedAspectRatio);

        try {
            String ossUrl = ossService.uploadImage(file.getBytes(), "scenes");
            if (ossUrl == null || ossUrl.isBlank()) {
                throw new BusinessException("上传图片失败");
            }

            int nextVersion = getNextVersion(sceneId);
            deactivateSceneAssets(sceneId);

            SceneAsset asset = new SceneAsset();
            asset.setSceneId(sceneId);
            asset.setAssetType("background");
            asset.setViewType("main");
            asset.setVersion(nextVersion);
            asset.setFilePath(ossUrl);
            asset.setThumbnailPath(ossUrl);
            asset.setFileName(scene.getSceneName() + "_upload_v" + nextVersion + ".png");
            asset.setStatus(1);
            asset.setIsActive(1);
            asset.setCreatedAt(LocalDateTime.now());
            asset.setUpdatedAt(LocalDateTime.now());
            sceneAssetMapper.insert(asset);

            SceneAssetMetadata metadata = new SceneAssetMetadata();
            metadata.setAssetId(asset.getId());
            metadata.setPrompt("用户手动上传场景图片");
            metadata.setUserPrompt(customPrompt != null && !customPrompt.isBlank() ? customPrompt.trim() : scene.getCustomPrompt());
            metadata.setModelVersion("manual-upload");
            metadata.setAspectRatio(normalizedAspectRatio);
            metadata.setCreatedAt(LocalDateTime.now());
            sceneAssetMetadataMapper.insert(metadata);

            if (customPrompt != null && !customPrompt.isBlank()) {
                scene.setCustomPrompt(customPrompt.trim());
            }
            scene.setAspectRatio(normalizedAspectRatio);
            scene.setStatus(SceneStatus.PENDING_REVIEW.getCode());
            scene.setUpdatedAt(LocalDateTime.now());
            sceneMapper.updateById(scene);

            log.info("场景手动上传完成: sceneId={}, assetId={}, version={}, aspectRatio={}",
                    sceneId, asset.getId(), nextVersion, normalizedAspectRatio);
            return getSceneDetail(sceneId);
        } catch (IOException e) {
            log.error("读取上传场景图片失败: sceneId={}", sceneId, e);
            throw new BusinessException("读取上传图片失败");
        }
    }

    private String normalizeSceneUploadAspectRatio(String aspectRatio) {
        if (aspectRatio == null || aspectRatio.isBlank()) {
            return "16:9";
        }
        String trimmed = aspectRatio.trim();
        List<String> allowedRatios = Arrays.asList("16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "21:9", "1:1");
        if (!allowedRatios.contains(trimmed)) {
            throw new BusinessException("不支持的图片比例");
        }
        return trimmed;
    }

    private void validateUploadedSceneImage(MultipartFile file, String aspectRatio) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传图片不能为空");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_UPLOAD_IMAGE_TYPES.contains(contentType)) {
            throw new BusinessException("只支持 JPG、PNG、WEBP 格式的图片");
        }
        if (file.getSize() > MAX_UPLOAD_IMAGE_SIZE) {
            throw new BusinessException("图片大小不能超过5MB");
        }
        try {
            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                throw new BusinessException("无法识别图片内容");
            }
            if (!matchesAspectRatio(image.getWidth(), image.getHeight(), aspectRatio)) {
                throw new BusinessException("场景图片比例必须为" + aspectRatio + "，请先裁剪后上传");
            }
        } catch (IOException e) {
            throw new BusinessException("读取上传图片失败");
        }
    }

    private boolean matchesAspectRatio(int width, int height, String aspectRatio) {
        String[] parts = aspectRatio.split(":");
        if (parts.length != 2) {
            return false;
        }
        double expected = Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
        double actual = (double) width / (double) height;
        return Math.abs(actual - expected) < 0.01;
    }

    /**
     * 异步生成场景（LLM提示词 + 图片生成）
     */
    @Async("imageGenerateExecutor")
    public void generateSceneWithLLMPrompt(Long sceneId, Long episodeId, String aspectRatio, String quality, String customPrompt) {
        log.info("开始异步生成场景: sceneId={}, customPrompt={}", sceneId, customPrompt != null);

        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            log.error("场景不存在: sceneId={}", sceneId);
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
                prompt = imagePromptGenerateService.generateScenePrompt(scene.getSeriesId(), scene.getSceneName(), episodeId);
                log.info("LLM 生成的场景提示词: {}", prompt);
            }

            // 更新场景：description存实际使用的提示词，customPrompt只存用户输入的
            scene.setDescription(prompt);
            scene.setCustomPrompt(userPrompt);  // 只存用户输入的，没输入则为null
            scene.setUpdatedAt(LocalDateTime.now());
            sceneMapper.updateById(scene);

            // 生成图片
            generateSceneAssetsWithPrompt(sceneId, prompt, aspectRatio, quality);
        } catch (Exception e) {
            log.error("场景生成失败: sceneId={}", sceneId, e);
            scene.setStatus(SceneStatus.PENDING_REVIEW.getCode());
            scene.setUpdatedAt(LocalDateTime.now());
            sceneMapper.updateById(scene);
        }
    }

    /**
     * 异步重新生成场景（LLM提示词 + 图片生成，版本+1）
     */
    @Async("imageGenerateExecutor")
    public void regenerateSceneWithLLMPrompt(Long sceneId, Long episodeId, String aspectRatio, String quality, String customPrompt) {
        log.info("开始异步重新生成场景: sceneId={}, customPrompt={}", sceneId, customPrompt != null);

        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            log.error("场景不存在: sceneId={}", sceneId);
            return;
        }

        try {
            // 更新比例和清晰度
            if (aspectRatio != null && !aspectRatio.isEmpty()) {
                scene.setAspectRatio(aspectRatio);
            }
            if (quality != null && !quality.isEmpty()) {
                scene.setQuality(quality);
            }
            scene.setStatus(SceneStatus.GENERATING.getCode());
            scene.setUpdatedAt(LocalDateTime.now());
            sceneMapper.updateById(scene);

            String prompt;
            String userPrompt = null;  // 用户输入的自定义提示词
            if (customPrompt != null && !customPrompt.trim().isEmpty()) {
                // 使用用户提供的自定义提示词
                prompt = customPrompt;
                userPrompt = customPrompt;
                log.info("使用自定义提示词: {}", prompt);
            } else {
                // 使用 LLM 生成提示词
                prompt = imagePromptGenerateService.generateScenePrompt(scene.getSeriesId(), scene.getSceneName(), episodeId);
                log.info("LLM 生成的场景提示词: {}", prompt);
            }

            // 更新场景：description存实际使用的提示词，customPrompt只存用户输入的
            scene.setDescription(prompt);
            scene.setCustomPrompt(userPrompt);  // 只存用户输入的，没输入则为null
            scene.setUpdatedAt(LocalDateTime.now());
            sceneMapper.updateById(scene);

            // 调用重新生成逻辑（版本+1）
            regenerateSceneAsset(sceneId, prompt, aspectRatio, quality);
        } catch (Exception e) {
            log.error("场景重新生成失败: sceneId={}", sceneId, e);
            scene.setStatus(SceneStatus.PENDING_REVIEW.getCode());
            scene.setUpdatedAt(LocalDateTime.now());
            sceneMapper.updateById(scene);
        }
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
            scene.setStatus(SceneStatus.GENERATING.getCode());
            scene.setUpdatedAt(LocalDateTime.now());
            sceneMapper.updateById(scene);

            SceneAsset asset = ensureGeneratingPlaceholderAsset(sceneId, scene.getSceneName());
            int actualVersion = asset.getVersion() != null ? asset.getVersion() : 1;

            // 调用图片生成服务
            ImageGenerateRequest request = new ImageGenerateRequest();
            request.setCustomPrompt(prompt);
            request.setAspectRatio(aspectRatio != null ? aspectRatio : "16:9");
            request.setQuality(quality != null ? quality : "2k");
            request.setStyleKeywords(resolveSceneStyleKeywords(scene));

            long startTime = System.currentTimeMillis();
            ImageGenerateResponse response = imageGenerateService.generateSceneImage(request);
            long generationTime = System.currentTimeMillis() - startTime;

            if (response != null && response.getImageUrl() != null) {
                // 上传到OSS
                String ossUrl = ossService.uploadImageFromUrl(response.getImageUrl(), "scenes");

                deactivateSceneAssets(sceneId);

                // 更新资产记录
                asset.setFilePath(ossUrl);
                asset.setThumbnailPath(ossUrl);
                asset.setFileName(scene.getSceneName() + "_v" + actualVersion + ".png");
                asset.setStatus(1);
                asset.setIsActive(1);
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
