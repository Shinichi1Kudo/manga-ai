package com.manga.ai.prop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.manga.ai.common.constants.CreditConstants;
import com.manga.ai.common.enums.CreditUsageType;
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
import com.manga.ai.user.service.UserService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
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

    private static final List<String> ALLOWED_UPLOAD_IMAGE_TYPES = Arrays.asList(
            "image/jpeg", "image/png", "image/webp"
    );
    private static final long MAX_UPLOAD_IMAGE_SIZE = 5 * 1024 * 1024;

    private final PropMapper propMapper;
    private final PropAssetMapper propAssetMapper;
    private final PropAssetMetadataMapper propAssetMetadataMapper;
    private final SeriesMapper seriesMapper;
    private final ImageGenerateService imageGenerateService;
    private final OssService ossService;
    private final ImagePromptGenerateService imagePromptGenerateService;
    private final UserService userService;

    @Qualifier("imageGenerateExecutor")
    private final Executor imageGenerateExecutor;

    @Override
    public List<PropDetailVO> getPropsBySeriesId(Long seriesId) {
        return getPropsBySeriesId(seriesId, null);
    }

    @Override
    public List<PropDetailVO> getPropsBySeriesId(Long seriesId, Long episodeId) {
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
                .map(prop -> convertToVOWithAssets(prop, assetsByPropId.getOrDefault(prop.getId(), List.of()), episodeId))
                .filter(this::isVisibleInEpisodeScope)
                .collect(Collectors.toList());
    }

    @Override
    public PropDetailVO getPropDetail(Long propId) {
        return getPropDetail(propId, null);
    }

    @Override
    public PropDetailVO getPropDetail(Long propId, Long episodeId) {
        return getPropDetail(propId, episodeId, false);
    }

    @Override
    public PropDetailVO getPropDetail(Long propId, Long episodeId, boolean includeHistory) {
        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            throw new BusinessException("道具不存在");
        }
        return convertToVO(prop, episodeId, includeHistory);
    }

    @Override
    @Async("imageGenerateExecutor")
    public void generatePropAssets(Long propId) {
        generatePropAssets(propId, null);
    }

    public void generatePropAssets(Long propId, Long episodeId) {
        log.info("开始生成道具资产: propId={}, episodeId={}", propId, episodeId);

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

            PropAsset asset = ensureGeneratingPlaceholderAsset(propId, prop.getPropName(), episodeId);
            int actualVersion = asset.getVersion() != null ? asset.getVersion() : 1;

            // 调用图片生成服务
            ImageGenerateRequest request = new ImageGenerateRequest();
            request.setCustomPrompt(prompt);
            request.setAspectRatio("1:1"); // 道具使用正方形比例
            request.setQuality("hd");
            request.setStyleKeywords(styleKeywords);

            long startTime = System.currentTimeMillis();
            ImageGenerateResponse response = imageGenerateService.generatePropImage(request);
            long generationTime = System.currentTimeMillis() - startTime;

            if (response != null && response.getImageUrl() != null) {
                // 上传到OSS
                String ossUrl = ossService.uploadImageFromUrl(response.getImageUrl(), "props");

                deactivatePropAssets(propId);

                // 更新资产记录
                asset.setFilePath(ossUrl);
                asset.setTransparentPath(ossUrl); // 透明PNG暂用原图
                asset.setThumbnailPath(ossUrl);
                asset.setFileName(prop.getPropName() + "_v" + actualVersion + ".png");
                asset.setStatus(1); // 已完成
                asset.setIsActive(1);
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
    public void generatePropAssetsWithCredit(Long propId, Long userId) {
        generatePropAssetsWithCredit(propId, userId, null);
    }

    @Override
    public void generatePropAssetsWithCredit(Long propId, Long userId, Long episodeId) {
        log.info("生成道具资产（含积分扣费）: propId={}, userId={}, episodeId={}", propId, userId, episodeId);

        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            throw new BusinessException("道具不存在");
        }

        // 扣除积分（道具生成1张图）
        int requiredCredits = CreditConstants.CREDITS_PER_IMAGE;
        if (userId == null) {
            userId = UserContextHolder.getUserId();
        }
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }
        userService.deductCredits(userId, requiredCredits, CreditUsageType.PROP_CREATE.getCode(),
                "道具创建-" + prop.getPropName(), propId, "PROP");
        log.info("道具生成扣费: userId={}, propId={}, credits={}", userId, propId, requiredCredits);

        // 记录扣除的积分，并在接口返回前落库生成中状态和占位资产。
        prop.setDeductedCredits(requiredCredits);
        prop.setStatus(PropStatus.GENERATING.getCode());
        prop.setUpdatedAt(LocalDateTime.now());
        propMapper.updateById(prop);
        PropAsset placeholderAsset = ensureGeneratingPlaceholderAsset(propId, prop.getPropName(), episodeId);
        log.info("道具生成占位资产已就绪: propId={}, episodeId={}, version={}, assetId={}",
                propId, episodeId, placeholderAsset.getVersion(), placeholderAsset.getId());

        // 提交后台生成任务，避免 HTTP 请求线程同步等待火山接口。
        imageGenerateExecutor.execute(() -> generatePropAssets(propId, episodeId));
    }

    @Override
    @Async("imageGenerateExecutor")
    public void regeneratePropAsset(Long propId, String customPrompt, String quality) {
        regeneratePropAsset(propId, customPrompt, quality, null);
    }

    public void regeneratePropAsset(Long propId, String customPrompt, String quality, Long episodeId) {
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

            PropAsset asset = findLatestGeneratingPlaceholder(propId, episodeId);

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
                asset.setEpisodeId(episodeId);
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
            request.setStyleKeywords(styleKeywords);

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
    public void regeneratePropAssetWithCredit(Long propId, String customPrompt, String quality, Long userId) {
        regeneratePropAssetWithCredit(propId, customPrompt, quality, userId, null);
    }

    @Override
    public void regeneratePropAssetWithCredit(Long propId, String customPrompt, String quality, Long userId, Long episodeId) {
        log.info("重新生成道具资产（含积分扣费）: propId={}, episodeId={}, quality={}", propId, episodeId, quality);

        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            throw new BusinessException("道具不存在");
        }

        // 检查是否已锁定
        if (PropStatus.LOCKED.getCode().equals(prop.getStatus())) {
            throw new BusinessException("道具已锁定，无法重新生成");
        }

        // 扣除积分（道具生成1张图）
        int requiredCredits = CreditConstants.CREDITS_PER_IMAGE;
        if (userId == null) {
            userId = UserContextHolder.getUserId();
        }
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }
        userService.deductCredits(userId, requiredCredits, CreditUsageType.PROP_CREATE.getCode(),
                "道具重新生成-" + prop.getPropName(), propId, "PROP");
        log.info("道具重新生成扣费: userId={}, propId={}, credits={}", userId, propId, requiredCredits);

        // 记录扣除的积分，并在接口返回前落库生成中状态和占位资产。
        prop.setDeductedCredits(requiredCredits);
        prop.setStatus(PropStatus.GENERATING.getCode());
        if (quality != null && !quality.isEmpty()) {
            prop.setQuality(quality);
        }
        if (customPrompt != null && !customPrompt.trim().isEmpty()) {
            prop.setCustomPrompt(customPrompt);
        }
        prop.setUpdatedAt(LocalDateTime.now());
        propMapper.updateById(prop);
        PropAsset placeholderAsset = ensureGeneratingPlaceholderAsset(propId, prop.getPropName(), episodeId);
        log.info("道具重新生成占位资产已就绪: propId={}, episodeId={}, version={}, assetId={}",
                propId, episodeId, placeholderAsset.getVersion(), placeholderAsset.getId());

        // 提交后台生成任务，避免 HTTP 请求线程同步等待火山接口。
        imageGenerateExecutor.execute(() -> regeneratePropAsset(propId, customPrompt, quality, episodeId));
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
        lockProp(propId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void lockProp(Long propId, Long episodeId) {
        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            throw new BusinessException("道具不存在");
        }

        PropAsset asset = selectPropAssetForLock(propId, episodeId);
        if (asset != null) {
            if (episodeId != null && asset.getEpisodeId() == null) {
                asset.setEpisodeId(episodeId);
            }
            activatePropAsset(propId, asset);
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
        // 获取系列信息，用于获取背景设定和剧本大纲
        Series series = seriesMapper.selectById(prop.getSeriesId());
        String background = series != null ? series.getBackground() : null;
        String outline = series != null ? series.getOutline() : null;

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

        // 背景设定/世界观（用于理解道具的背景）
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

        // 风格关键词（确保使用系列风格）
        if (styleKeywords != null && !styleKeywords.isEmpty()) {
            prompt.append(styleKeywords);
            prompt.append(", ");
        }

        // 背景关键词（重要）：道具资产用于贴合分镜和场景，优先生成透明背景抠图。
        prompt.append("transparent background, isolated clean cutout, alpha channel style, no background, no backdrop, ");
        // 排除人物/手部，确保只生成单品道具
        prompt.append("no hands, no hands holding, no one holding, no person holding, no fingers, ");
        prompt.append("no people, no characters, standalone prop, single object, ");
        prompt.append("high quality, detailed, professional product shot, no shadows, centered");

        return prompt.toString();
    }

    private String resolvePropStyleKeywords(Prop prop) {
        if (prop != null && prop.getStyleKeywords() != null && !prop.getStyleKeywords().trim().isEmpty()) {
            return prop.getStyleKeywords();
        }
        Series series = prop != null ? seriesMapper.selectById(prop.getSeriesId()) : null;
        return series != null ? series.getStyleKeywords() : null;
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
     * 创建生成中的资产占位，让刷新/新开页面能看到一致状态。
     */
    private PropAsset createGeneratingPlaceholderAsset(Long propId, String propName, int version) {
        return createGeneratingPlaceholderAsset(propId, propName, version, null);
    }

    private PropAsset createGeneratingPlaceholderAsset(Long propId, String propName, int version, Long episodeId) {
        PropAsset placeholderAsset = new PropAsset();
        placeholderAsset.setPropId(propId);
        placeholderAsset.setEpisodeId(episodeId);
        placeholderAsset.setAssetType("item");
        placeholderAsset.setViewType("main");
        placeholderAsset.setVersion(version);
        placeholderAsset.setStatus(0);
        placeholderAsset.setIsActive(0);
        placeholderAsset.setFileName(propName + "_v" + version + "_generating.png");
        placeholderAsset.setCreatedAt(LocalDateTime.now());
        placeholderAsset.setUpdatedAt(LocalDateTime.now());
        propAssetMapper.insert(placeholderAsset);
        return placeholderAsset;
    }

    /**
     * 事务提交后再启动异步生成，避免生成线程读到未提交的道具/占位资产。
     */
    private void executeImageGenerationAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    imageGenerateExecutor.execute(task);
                }
            });
            return;
        }

        imageGenerateExecutor.execute(task);
    }

    /**
     * 查找同步创建的生成中占位资产，异步生成成功后复用并更新它。
     */
    private PropAsset findLatestGeneratingPlaceholder(Long propId) {
        return findLatestGeneratingPlaceholder(propId, null);
    }

    private PropAsset findLatestGeneratingPlaceholder(Long propId, Long episodeId) {
        LambdaQueryWrapper<PropAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PropAsset::getPropId, propId)
                .eq(PropAsset::getStatus, 0)
                .eq(PropAsset::getIsActive, 0)
                .orderByDesc(PropAsset::getVersion)
                .last("LIMIT 1");
        if (episodeId != null) {
            wrapper.eq(PropAsset::getEpisodeId, episodeId);
        }
        return propAssetMapper.selectOne(wrapper);
    }

    private PropAsset ensureGeneratingPlaceholderAsset(Long propId, String propName, Long episodeId) {
        PropAsset asset = findLatestGeneratingPlaceholder(propId, episodeId);
        if (asset != null) {
            return asset;
        }
        int nextVersion = getNextVersion(propId);
        return createGeneratingPlaceholderAsset(propId, propName, nextVersion, episodeId);
    }

    private void deactivatePropAssets(Long propId) {
        LambdaQueryWrapper<PropAsset> updateWrapper = new LambdaQueryWrapper<>();
        updateWrapper.eq(PropAsset::getPropId, propId);
        PropAsset updateAsset = new PropAsset();
        updateAsset.setIsActive(0);
        updateAsset.setUpdatedAt(LocalDateTime.now());
        propAssetMapper.update(updateAsset, updateWrapper);
    }

    private void activatePropAsset(Long propId, PropAsset asset) {
        if (asset == null) {
            return;
        }
        deactivatePropAssets(propId);
        asset.setIsActive(1);
        asset.setUpdatedAt(LocalDateTime.now());
        propAssetMapper.updateById(asset);
    }

    private PropAsset selectScopedPropAsset(Long propId, Long episodeId, boolean activeOnly) {
        LambdaQueryWrapper<PropAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PropAsset::getPropId, propId)
                .orderByDesc(PropAsset::getVersion)
                .orderByDesc(PropAsset::getId);
        if (activeOnly) {
            wrapper.eq(PropAsset::getIsActive, 1);
        }
        List<PropAsset> assets = propAssetMapper.selectList(wrapper);
        if (assets.isEmpty()) {
            return null;
        }

        if (episodeId != null) {
            PropAsset scopedAsset = assets.stream()
                    .filter(asset -> episodeId.equals(asset.getEpisodeId()))
                    .max(Comparator.comparing(PropAsset::getVersion, Comparator.nullsLast(Integer::compareTo))
                            .thenComparing(PropAsset::getId, Comparator.nullsLast(Long::compareTo)))
                    .orElse(null);
            if (scopedAsset != null) {
                return scopedAsset;
            }
        }

        PropAsset activeAsset = assets.stream()
                .filter(asset -> asset.getIsActive() != null && asset.getIsActive() == 1)
                .findFirst()
                .orElse(null);
        return activeAsset != null ? activeAsset : assets.get(0);
    }

    private PropAsset selectPropAssetForLock(Long propId, Long episodeId) {
        LambdaQueryWrapper<PropAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PropAsset::getPropId, propId)
                .orderByDesc(PropAsset::getIsActive)
                .orderByDesc(PropAsset::getUpdatedAt)
                .orderByDesc(PropAsset::getVersion)
                .orderByDesc(PropAsset::getId);
        List<PropAsset> assets = propAssetMapper.selectList(wrapper);
        if (assets.isEmpty()) {
            return null;
        }

        PropAsset activeAsset = assets.stream()
                .filter(asset -> asset.getIsActive() != null && asset.getIsActive() == 1)
                .findFirst()
                .orElse(null);
        if (activeAsset != null) {
            return activeAsset;
        }

        return selectScopedPropAsset(propId, episodeId, false);
    }

    private List<PropAsset> filterAssetsForEpisodeScope(Prop prop, List<PropAsset> assets, Long episodeId) {
        if (PropStatus.LOCKED.getCode().equals(prop.getStatus()) || episodeId == null) {
            return assets;
        }
        return assets.stream()
                .filter(asset -> episodeId.equals(asset.getEpisodeId()))
                .collect(Collectors.toList());
    }

    private List<PropAsset> filterAssetsForHistoryScope(Prop prop, List<PropAsset> assets, Long episodeId) {
        if (PropStatus.LOCKED.getCode().equals(prop.getStatus()) || episodeId == null) {
            return assets;
        }
        return assets.stream()
                .filter(asset -> episodeId.equals(asset.getEpisodeId()) || asset.getEpisodeId() == null)
                .collect(Collectors.toList());
    }

    private boolean isVisibleInEpisodeScope(PropDetailVO vo) {
        if (vo.getStatus() != null && PropStatus.LOCKED.getCode().equals(vo.getStatus())) {
            return true;
        }
        return vo.getAssets() != null && !vo.getAssets().isEmpty();
    }

    private void applyAssetData(PropDetailVO vo, List<PropDetailVO.PropAssetVO> assetVOs) {
        applyAssetData(vo, assetVOs, false);
    }

    private void applyAssetData(PropDetailVO vo, List<PropDetailVO.PropAssetVO> assetVOs, boolean preferLatestScopedAsset) {
        vo.setAssets(assetVOs);

        // 道具主状态是唯一状态源。旧的生成中占位资产不能覆盖已完成的道具状态。
        if (vo.getStatus() == null && !assetVOs.isEmpty()) {
            PropDetailVO.PropAssetVO latestAsset = assetVOs.stream()
                    .max((a, b) -> Integer.compare(a.getVersion(), b.getVersion()))
                    .orElse(null);
            if (latestAsset != null) {
                vo.setStatus(latestAsset.getStatus());
            }
        }

        PropDetailVO.PropAssetVO displayAsset = null;
        if (!assetVOs.isEmpty()) {
            if (preferLatestScopedAsset) {
                PropDetailVO.PropAssetVO generatingAsset = assetVOs.stream()
                        .filter(assetVO -> assetVO.getStatus() != null && assetVO.getStatus() == 0)
                        .max(Comparator.comparing(PropDetailVO.PropAssetVO::getVersion, Comparator.nullsLast(Integer::compareTo))
                                .thenComparing(PropDetailVO.PropAssetVO::getId, Comparator.nullsLast(Long::compareTo)))
                        .orElse(null);
                displayAsset = generatingAsset != null ? generatingAsset : assetVOs.stream()
                        .filter(assetVO -> assetVO.getIsActive() != null && assetVO.getIsActive() == 1
                                && (assetVO.getFilePath() != null || assetVO.getTransparentPath() != null))
                        .findFirst()
                        .orElse(null);
                if (displayAsset != null) {
                    for (PropDetailVO.PropAssetVO assetVO : assetVOs) {
                        assetVO.setIsActive(assetVO.getId() != null && assetVO.getId().equals(displayAsset.getId()) ? 1 : 0);
                    }
                }
            } else {
                displayAsset = assetVOs.stream()
                        .filter(assetVO -> assetVO.getIsActive() != null && assetVO.getIsActive() == 1
                                && (assetVO.getFilePath() != null || assetVO.getTransparentPath() != null))
                        .findFirst()
                        .orElse(null);
            }

            if (displayAsset == null) {
                displayAsset = assetVOs.stream()
                        .filter(assetVO -> assetVO.getFilePath() != null || assetVO.getTransparentPath() != null)
                        .max(Comparator.comparing(PropDetailVO.PropAssetVO::getVersion, Comparator.nullsLast(Integer::compareTo))
                                .thenComparing(PropDetailVO.PropAssetVO::getId, Comparator.nullsLast(Long::compareTo)))
                        .orElse(assetVOs.get(0));
            }
        }

        if (displayAsset != null) {
            if (preferLatestScopedAsset && displayAsset.getStatus() != null) {
                vo.setStatus(displayAsset.getStatus());
            }
            vo.setActiveAssetEpisodeId(displayAsset.getEpisodeId());
            vo.setActiveAssetUrl(displayAsset.getFilePath());
            vo.setTransparentUrl(displayAsset.getTransparentPath());
        }

        PropDetailVO.PropAssetVO latestAsset = assetVOs.stream()
                .max(Comparator.comparing(PropDetailVO.PropAssetVO::getVersion, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(PropDetailVO.PropAssetVO::getId, Comparator.nullsLast(Long::compareTo)))
                .orElse(null);
        if (latestAsset != null) {
            vo.setLatestAssetEpisodeId(latestAsset.getEpisodeId());
        }
    }

    /**
     * 转换为VO（使用预先查询的资产列表，避免N+1查询）
     */
    private PropDetailVO convertToVOWithAssets(Prop prop, List<PropAsset> assets) {
        return convertToVOWithAssets(prop, assets, null);
    }

    private PropDetailVO convertToVOWithAssets(Prop prop, List<PropAsset> assets, Long episodeId) {
        PropDetailVO vo = new PropDetailVO();
        BeanUtils.copyProperties(prop, vo);

        boolean scopedNonLocked = episodeId != null && !PropStatus.LOCKED.getCode().equals(prop.getStatus());
        List<PropAsset> scopedAssets = filterAssetsForEpisodeScope(prop, assets, episodeId);
        List<PropDetailVO.PropAssetVO> assetVOs = scopedAssets.stream()
                .map(this::convertAssetToVO)
                .collect(Collectors.toList());
        applyAssetData(vo, assetVOs, scopedNonLocked);

        return vo;
    }

    /**
     * 转换为VO（单个查询，用于详情页）
     */
    private PropDetailVO convertToVO(Prop prop) {
        return convertToVO(prop, null, false);
    }

    private PropDetailVO convertToVO(Prop prop, Long episodeId) {
        return convertToVO(prop, episodeId, false);
    }

    private PropDetailVO convertToVO(Prop prop, Long episodeId, boolean includeHistory) {
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
        boolean scopedNonLocked = episodeId != null && !PropStatus.LOCKED.getCode().equals(prop.getStatus());
        boolean preferLatestScopedAsset = scopedNonLocked && !includeHistory;
        if (scopedNonLocked || includeHistory) {
            List<PropAsset> scopedAssets = includeHistory
                    ? filterAssetsForHistoryScope(prop, assets, episodeId)
                    : filterAssetsForEpisodeScope(prop, assets, episodeId);
            assetVOs = scopedAssets.stream()
                    .map(this::convertAssetToVO)
                    .collect(Collectors.toList());
        }
        applyAssetData(vo, assetVOs, preferLatestScopedAsset);

        return vo;
    }

    /**
     * 转换资产为VO
     */
    private PropDetailVO.PropAssetVO convertAssetToVO(PropAsset asset) {
        PropDetailVO.PropAssetVO vo = new PropDetailVO.PropAssetVO();
        BeanUtils.copyProperties(asset, vo);
        // 刷新OSS URL
        vo.setFilePath(ossService.refreshUrl(asset.getFilePath()));
        vo.setTransparentPath(ossService.refreshUrl(asset.getTransparentPath()));
        vo.setThumbnailPath(ossService.refreshUrl(asset.getThumbnailPath()));
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

            int nextVersion = getNextVersion(propId);
            PropAsset placeholderAsset = createGeneratingPlaceholderAsset(propId, propName, nextVersion, episodeId);

            log.info("已创建占位资产记录: propId={}, version={}, assetId={}", propId, nextVersion, placeholderAsset.getId());

            // 异步生成图片（使用配置的线程池确保立即执行）
            final Long finalPropId = propId;
            final Long finalEpisodeId = episodeId;
            final String finalQuality = quality;
            final String finalCustomPrompt = customPrompt;
            executeImageGenerationAfterCommit(() -> regeneratePropWithLLMPrompt(finalPropId, finalEpisodeId, finalQuality, finalCustomPrompt));
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

        PropAsset placeholderAsset = createGeneratingPlaceholderAsset(prop.getId(), propName, 1, episodeId);
        log.info("已创建新道具占位资产记录: propId={}, version=1, assetId={}", prop.getId(), placeholderAsset.getId());

        // 异步生成提示词和图片（使用配置的线程池确保立即执行）
        final Long propId = prop.getId();
        final Long finalEpisodeId = episodeId;
        final String finalQuality = quality;
        final String finalCustomPrompt = customPrompt;
        executeImageGenerationAfterCommit(() -> generatePropWithLLMPrompt(propId, finalEpisodeId, finalQuality, finalCustomPrompt));

        return prop.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PropDetailVO uploadPropAsset(Long seriesId, Long episodeId, String propName, String quality, String customPrompt, MultipartFile file) {
        if (propName == null || propName.trim().isEmpty()) {
            throw new BusinessException("道具名称不能为空");
        }

        Series series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new BusinessException("系列不存在");
        }

        LambdaQueryWrapper<Prop> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Prop::getSeriesId, seriesId)
                .eq(Prop::getPropName, propName.trim())
                .last("LIMIT 1");
        Prop prop = propMapper.selectOne(wrapper);

        if (prop == null) {
            prop = new Prop();
            prop.setSeriesId(seriesId);
            prop.setPropName(propName.trim());
            prop.setPropCode("UPLOAD_" + System.currentTimeMillis());
            prop.setStyleKeywords(series.getStyleKeywords());
            prop.setAspectRatio("1:1");
            prop.setQuality((quality != null && !quality.isBlank()) ? quality : "2k");
            prop.setStatus(PropStatus.PENDING_REVIEW.getCode());
            prop.setCreatedAt(LocalDateTime.now());
            prop.setUpdatedAt(LocalDateTime.now());
            propMapper.insert(prop);
        } else {
            if (quality != null && !quality.isBlank()) {
                prop.setQuality(quality);
            }
            if (customPrompt != null && !customPrompt.isBlank()) {
                prop.setCustomPrompt(customPrompt.trim());
            }
            prop.setStatus(PropStatus.PENDING_REVIEW.getCode());
            prop.setUpdatedAt(LocalDateTime.now());
            propMapper.updateById(prop);
        }

        return uploadPropAsset(prop.getId(), episodeId, customPrompt, file);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PropDetailVO uploadPropAsset(Long propId, Long episodeId, String customPrompt, MultipartFile file) {
        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            throw new BusinessException("道具不存在");
        }
        if (PropStatus.LOCKED.getCode().equals(prop.getStatus())) {
            throw new BusinessException("道具已锁定，无法上传新版本");
        }

        validateUploadedPropImage(file);

        try {
            String ossUrl = ossService.uploadImage(file.getBytes(), "props");
            if (ossUrl == null || ossUrl.isBlank()) {
                throw new BusinessException("上传图片失败");
            }

            int nextVersion = getNextVersion(propId);
            deactivatePropAssets(propId);

            PropAsset asset = new PropAsset();
            asset.setPropId(propId);
            asset.setEpisodeId(episodeId);
            asset.setAssetType("item");
            asset.setViewType("main");
            asset.setVersion(nextVersion);
            asset.setFilePath(ossUrl);
            asset.setTransparentPath(ossUrl);
            asset.setThumbnailPath(ossUrl);
            asset.setFileName(prop.getPropName() + "_upload_v" + nextVersion + ".png");
            asset.setStatus(1);
            asset.setIsActive(1);
            asset.setCreatedAt(LocalDateTime.now());
            asset.setUpdatedAt(LocalDateTime.now());
            propAssetMapper.insert(asset);

            PropAssetMetadata metadata = new PropAssetMetadata();
            metadata.setAssetId(asset.getId());
            metadata.setPrompt("用户手动上传道具图片");
            metadata.setUserPrompt(customPrompt != null && !customPrompt.isBlank() ? customPrompt.trim() : prop.getCustomPrompt());
            metadata.setModelVersion("manual-upload");
            metadata.setAspectRatio("1:1");
            metadata.setCreatedAt(LocalDateTime.now());
            propAssetMetadataMapper.insert(metadata);

            if (customPrompt != null && !customPrompt.isBlank()) {
                prop.setCustomPrompt(customPrompt.trim());
            }
            prop.setStatus(PropStatus.PENDING_REVIEW.getCode());
            prop.setUpdatedAt(LocalDateTime.now());
            propMapper.updateById(prop);

            clearActiveGeneratingPlaceholders(propId);
            log.info("道具手动上传完成: propId={}, episodeId={}, assetId={}, version={}",
                    propId, episodeId, asset.getId(), nextVersion);
            return getPropDetail(propId, episodeId);
        } catch (IOException e) {
            log.error("读取上传道具图片失败: propId={}", propId, e);
            throw new BusinessException("读取上传图片失败");
        }
    }

    private void validateUploadedPropImage(MultipartFile file) {
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
            if (image.getWidth() != image.getHeight()) {
                throw new BusinessException("道具图片必须为1:1，请先裁剪为正方形");
            }
        } catch (IOException e) {
            throw new BusinessException("读取上传图片失败");
        }
    }

    private void clearActiveGeneratingPlaceholders(Long propId) {
        LambdaQueryWrapper<PropAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PropAsset::getPropId, propId)
                .eq(PropAsset::getStatus, 0);
        PropAsset updateAsset = new PropAsset();
        updateAsset.setIsActive(0);
        updateAsset.setUpdatedAt(LocalDateTime.now());
        propAssetMapper.update(updateAsset, wrapper);
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
            generatePropAssetsWithPrompt(propId, prompt, quality, episodeId);
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
            regeneratePropAsset(propId, prompt, quality, episodeId);
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
        generatePropAssetsWithPrompt(propId, prompt, quality, null);
    }

    public void generatePropAssetsWithPrompt(Long propId, String prompt, String quality, Long episodeId) {
        log.info("开始生成道具资产: propId={}", propId);

        Prop prop = propMapper.selectById(propId);
        if (prop == null) {
            log.error("道具不存在: propId={}", propId);
            return;
        }

        try {
            prop.setStatus(PropStatus.GENERATING.getCode());
            prop.setUpdatedAt(LocalDateTime.now());
            propMapper.updateById(prop);

            PropAsset asset = findLatestGeneratingPlaceholder(propId, episodeId);
            if (asset == null) {
                int nextVersion = getNextVersion(propId);
                asset = createGeneratingPlaceholderAsset(propId, prop.getPropName(), nextVersion, episodeId);
                log.info("未找到占位资产，已补建: propId={}, version={}, assetId={}", propId, nextVersion, asset.getId());
            } else {
                log.info("复用已存在的占位资产: propId={}, version={}, assetId={}", propId, asset.getVersion(), asset.getId());
            }

            int actualVersion = asset.getVersion() != null ? asset.getVersion() : 1;

            // 调用图片生成服务
            ImageGenerateRequest request = new ImageGenerateRequest();
            request.setCustomPrompt(prompt);
            request.setAspectRatio("1:1"); // 道具固定 1:1
            request.setQuality(quality != null ? quality : "2k");
            request.setStyleKeywords(resolvePropStyleKeywords(prop));

            long startTime = System.currentTimeMillis();
            ImageGenerateResponse response = imageGenerateService.generatePropImage(request);
            long generationTime = System.currentTimeMillis() - startTime;

            if (response != null && response.getImageUrl() != null) {
                // 上传到OSS
                String ossUrl = ossService.uploadImageFromUrl(response.getImageUrl(), "props");

                deactivatePropAssets(propId);

                // 更新资产记录
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
        rollbackToVersion(propId, assetId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollbackToVersion(Long propId, Long assetId, Long episodeId) {
        log.info("回滚道具资产版本: propId={}, assetId={}, episodeId={}", propId, assetId, episodeId);

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

        Long resolvedEpisodeId = episodeId;
        if (resolvedEpisodeId == null && targetAsset.getEpisodeId() == null) {
            PropAsset activeAsset = selectScopedPropAsset(propId, null, true);
            if (activeAsset != null && !assetId.equals(activeAsset.getId())) {
                resolvedEpisodeId = activeAsset.getEpisodeId();
            }
        }

        // 停用所有资产
        LambdaQueryWrapper<PropAsset> updateWrapper = new LambdaQueryWrapper<>();
        updateWrapper.eq(PropAsset::getPropId, propId);
        PropAsset updateAsset = new PropAsset();
        updateAsset.setIsActive(0);
        updateAsset.setUpdatedAt(LocalDateTime.now());
        propAssetMapper.update(updateAsset, updateWrapper);

        // 激活目标资产
        if (resolvedEpisodeId != null && targetAsset.getEpisodeId() == null) {
            targetAsset.setEpisodeId(resolvedEpisodeId);
        }
        targetAsset.setIsActive(1);
        targetAsset.setUpdatedAt(LocalDateTime.now());
        propAssetMapper.updateById(targetAsset);

        prop.setStatus(PropStatus.PENDING_REVIEW.getCode());
        prop.setUpdatedAt(LocalDateTime.now());
        propMapper.updateById(prop);

        log.info("道具资产回滚成功: propId={}, assetId={}, version={}, episodeId={}",
                propId, assetId, targetAsset.getVersion(), targetAsset.getEpisodeId());
    }
}
