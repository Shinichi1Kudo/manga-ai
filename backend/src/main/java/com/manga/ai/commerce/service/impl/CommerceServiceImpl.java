package com.manga.ai.commerce.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.manga.ai.commerce.dto.CommerceModelCreateRequest;
import com.manga.ai.commerce.dto.CommerceModelVO;
import com.manga.ai.commerce.dto.CommerceProductCreateRequest;
import com.manga.ai.commerce.dto.CommerceProductVO;
import com.manga.ai.commerce.dto.CommerceProjectCreateRequest;
import com.manga.ai.commerce.dto.CommerceProjectVO;
import com.manga.ai.commerce.dto.CommerceSceneBatchCreateRequest;
import com.manga.ai.commerce.dto.CommerceSceneGenerationStartRequest;
import com.manga.ai.commerce.dto.CommerceSceneVO;
import com.manga.ai.commerce.dto.CommerceSeriesCreateRequest;
import com.manga.ai.commerce.dto.CommerceSeriesVO;
import com.manga.ai.commerce.dto.CommerceShotVO;
import com.manga.ai.commerce.entity.CommerceModel;
import com.manga.ai.commerce.entity.CommerceProduct;
import com.manga.ai.commerce.entity.CommerceProject;
import com.manga.ai.commerce.entity.CommerceScene;
import com.manga.ai.commerce.entity.CommerceSeries;
import com.manga.ai.commerce.entity.CommerceShot;
import com.manga.ai.commerce.mapper.CommerceModelMapper;
import com.manga.ai.commerce.mapper.CommerceProductMapper;
import com.manga.ai.commerce.mapper.CommerceProjectMapper;
import com.manga.ai.commerce.mapper.CommerceSceneMapper;
import com.manga.ai.commerce.mapper.CommerceSeriesMapper;
import com.manga.ai.commerce.mapper.CommerceShotMapper;
import com.manga.ai.commerce.service.CommerceModelProfileService;
import com.manga.ai.commerce.service.CommerceService;
import com.manga.ai.commerce.service.dto.CommerceModelProfile;
import com.manga.ai.common.constants.CreditConstants;
import com.manga.ai.common.enums.CreditUsageType;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.service.OssService;
import com.manga.ai.image.dto.ImageGenerateRequest;
import com.manga.ai.image.dto.ImageGenerateResponse;
import com.manga.ai.image.service.ImageGenerateService;
import com.manga.ai.user.service.UserService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import com.manga.ai.video.dto.SeedanceRequest;
import com.manga.ai.video.dto.SeedanceResponse;
import com.manga.ai.video.service.SeedanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommerceServiceImpl implements CommerceService {

    private static final String DEFAULT_CATEGORY = "clothing";
    private static final String DEFAULT_VISUAL_STYLE = "realistic";
    private static final String DEFAULT_ASPECT_RATIO = "9:16";
    private static final String DEFAULT_MODEL_ASPECT_RATIO = "16:9";
    private static final List<String> DEFAULT_CONTENT_TYPES = List.of("talking", "try_on");
    private static final List<String> ALLOWED_ASPECT_RATIOS = Arrays.asList("3:4", "9:16", "1:1", "16:9");
    private static final List<String> ALLOWED_CONTENT_TYPES = Arrays.asList("talking", "try_on");
    private static final List<String> FEMALE_MODEL_TYPES = List.of("年轻美女", "成熟女性", "中年女性", "老年女性", "高级感女模特", "亲和素人女性");
    private static final List<String> MALE_MODEL_TYPES = List.of("年轻帅哥", "成熟男性", "中年男性", "老年男性", "高级感男模特", "亲和素人男性");
    private static final List<String> CHILD_MODEL_TYPES = List.of("小女孩", "小男孩", "中性儿童");
    private static final List<String> ALLOWED_ETHNICITIES = List.of("中国", "华人", "东亚", "日韩", "亚洲", "东南亚", "南亚", "欧美", "拉美", "中东", "非洲", "混血感");
    private static final List<String> ALLOWED_BODY_PROFILES = List.of("标准匀称", "纤细修长", "高挑模特", "微胖亲和", "丰满曲线", "大码胖模特");
    private static final List<String> ALLOWED_PRODUCT_IMAGE_TYPES = List.of("image/jpeg", "image/png", "image/webp");
    private static final List<String> ALLOWED_PRODUCT_IMAGE_USAGES = List.of("主图", "细节图", "上身参考");
    private static final List<String> ALLOWED_SCENE_TYPES = List.of("品牌场景", "直播间背景", "街拍场景", "棚拍场景", "商场试衣间", "室内全身镜");
    private static final List<String> ALLOWED_SCENE_RESOLUTIONS = List.of("2K", "3K", "4K");
    private static final List<String> ALLOWED_TRY_ON_VIDEO_RESOLUTIONS = List.of("480p", "720p", "1080p");
    private static final List<String> ALLOWED_TRY_ON_VIDEO_MODELS = List.of("seedance-2.0", "seedance-2.0-fast", "kling-v3-omni");
    private static final List<String> DEFAULT_TRY_ON_DISPLAY_ACTIONS = List.of("全身站立", "转身展示", "走动展示", "细节展示");
    private static final List<String> ALLOWED_TRY_ON_DISPLAY_ACTIONS = List.of(
            "全身站立", "转身展示", "走动展示", "细节展示",
            "侧身展示", "背面展示", "半身近景", "坐姿展示",
            "抬手展示", "下摆展示", "面料特写", "搭配展示");
    private static final int DEFAULT_TRY_ON_VIDEO_DURATION = 8;
    private static final String DEFAULT_TRY_ON_VIDEO_RESOLUTION = "480p";
    private static final String DEFAULT_TRY_ON_VIDEO_MODEL = "seedance-2.0";
    private static final long MAX_PRODUCT_IMAGE_SIZE = 10L * 1024 * 1024;

    private final CommerceSeriesMapper seriesMapper;
    private final CommerceModelMapper modelMapper;
    private final CommerceProductMapper productMapper;
    private final CommerceProjectMapper projectMapper;
    private final CommerceSceneMapper sceneMapper;
    private final CommerceShotMapper shotMapper;
    private final SeedanceService seedanceService;
    private final UserService userService;
    private final CommerceModelProfileService modelProfileService;
    private final ImageGenerateService imageGenerateService;
    private final OssService ossService;
    @Qualifier("imageGenerateExecutor")
    private final Executor imageGenerateExecutor;
    @Qualifier("videoGenerateExecutor")
    private final Executor videoGenerateExecutor;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommerceSeriesVO createSeries(CommerceSeriesCreateRequest request) {
        Long userId = requireUserId();
        if (request == null || isBlank(request.getName())) {
            throw new BusinessException("请输入电商系列名称");
        }

        String aspectRatio = defaultIfBlank(request.getDefaultAspectRatio(), DEFAULT_ASPECT_RATIO);
        if (!ALLOWED_ASPECT_RATIOS.contains(aspectRatio)) {
            throw new BusinessException("不支持的画幅比例");
        }

        List<String> contentTypes = request.getEnabledContentTypes() == null || request.getEnabledContentTypes().isEmpty()
                ? DEFAULT_CONTENT_TYPES
                : request.getEnabledContentTypes();

        CommerceSeries series = new CommerceSeries();
        series.setUserId(userId);
        series.setName(request.getName().trim());
        series.setCategory(defaultIfBlank(request.getCategory(), DEFAULT_CATEGORY));
        series.setVisualStyle(defaultIfBlank(request.getVisualStyle(), DEFAULT_VISUAL_STYLE));
        series.setDefaultAspectRatio(aspectRatio);
        series.setEnabledContentTypes(String.join(",", contentTypes));
        series.setStatus("active");
        series.setIsDeleted(0);
        series.setCreatedAt(LocalDateTime.now());
        series.setUpdatedAt(LocalDateTime.now());
        seriesMapper.insert(series);

        return toSeriesVO(series);
    }

    @Override
    public List<CommerceSeriesVO> listSeries() {
        Long userId = requireUserId();
        LambdaQueryWrapper<CommerceSeries> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CommerceSeries::getUserId, userId)
                .orderByDesc(CommerceSeries::getCreatedAt);
        return seriesMapper.selectList(wrapper).stream().map(this::toSeriesVO).toList();
    }

    @Override
    public CommerceSeriesVO getSeries(Long seriesId) {
        return toSeriesVO(requireSeries(seriesId));
    }

    @Override
    public CommerceModelProfile generateModelPrompt(String preferredName, String gender, String modelType, String ethnicity, String bodyProfile) {
        Long userId = requireUserId();
        validateModelChoices(gender, modelType, ethnicity);
        String resolvedBodyProfile = resolveBodyProfile(bodyProfile);
        userService.deductCredits(
                userId,
                CreditConstants.CREDITS_PER_COMMERCE_MODEL_PROMPT,
                CreditUsageType.IMAGE_GENERATION.getCode(),
                "电商模特提示词生成",
                userId,
                "MODEL_PROMPT"
        );
        CommerceModelProfile profile = modelProfileService.generateProfile(
                "",
                gender.trim(),
                modelType.trim(),
                ethnicity.trim(),
                resolvedBodyProfile);
        ensureProfileBodyProfile(profile, resolvedBodyProfile);
        return profile;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommerceModelVO createModel(Long seriesId, CommerceModelCreateRequest request) {
        Long userId = requireUserId();
        CommerceSeries series = requireSeries(seriesId);
        if (!series.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该电商系列");
        }
        if (request == null) {
            throw new BusinessException("请选择模特信息");
        }
        if (isBlank(request.getName())) {
            throw new BusinessException("请输入模特名称");
        }
        validateModelChoices(request.getGender(), request.getModelType(), request.getEthnicity());
        if (isBlank(request.getAppearancePrompt())) {
            throw new BusinessException("请先生成并确认模特提示词");
        }
        String gender = request.getGender().trim();
        String modelType = request.getModelType().trim();
        String ethnicity = request.getEthnicity().trim();
        String bodyProfile = resolveBodyProfile(request.getBodyProfile());
        String modelName = request.getName().trim();
        String appearancePrompt = request.getAppearancePrompt().trim();
        String modelAspectRatio = DEFAULT_MODEL_ASPECT_RATIO;

        CommerceModel model = new CommerceModel();
        model.setSeriesId(seriesId);
        model.setUserId(userId);
        model.setName(modelName);
        model.setSourceType("ai");
        model.setGenderAge(gender + " / " + modelType + " / " + ethnicity);
        model.setBodyProfile(bodyProfile);
        model.setStyleTags("真人写实, 电商带货");
        model.setSpeakingPersona(null);
        model.setAppearancePrompt(appearancePrompt);
        model.setBaseImageUrl(null);
        model.setModelAspectRatio(modelAspectRatio);
        model.setStatus("queued");
        model.setErrorMessage(null);
        model.setIsDeleted(0);
        model.setCreatedAt(LocalDateTime.now());
        model.setUpdatedAt(LocalDateTime.now());
        modelMapper.insert(model);

        return toModelVO(model);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommerceModelVO startModelImageGeneration(Long modelId) {
        CommerceModel model = requireModel(modelId);
        if ("generating".equals(model.getStatus())) {
            return toModelVO(model);
        }
        if (!"queued".equals(model.getStatus())) {
            throw new BusinessException("只有待生成的模特可以启动图片生成");
        }
        if (isBlank(model.getAppearancePrompt())) {
            throw new BusinessException("模特提示词缺失，无法生成图片");
        }

        model.setBaseImageUrl(null);
        model.setStatus("generating");
        model.setErrorMessage(null);
        model.setUpdatedAt(LocalDateTime.now());
        modelMapper.updateById(model);
        executeCommerceModelImageGenerationAfterCommit(model.getId());
        return toModelVO(model);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommerceModelVO regenerateModel(Long modelId) {
        CommerceModel model = requireModel(modelId);
        if ("queued".equals(model.getStatus()) || "generating".equals(model.getStatus())) {
            throw new BusinessException("当前模特正在生成中，请稍后再试");
        }
        if ("locked".equals(model.getStatus())) {
            throw new BusinessException("已锁定模特需要先解锁才能重新生成");
        }
        if (!"failed".equals(model.getStatus())
                && !"pending_review".equals(model.getStatus())) {
            throw new BusinessException("当前模特状态不可重新生成");
        }
        if (isBlank(model.getAppearancePrompt())) {
            throw new BusinessException("模特提示词缺失，无法重新生成");
        }

        userService.deductCredits(
                model.getUserId(),
                CreditConstants.CREDITS_PER_IMAGE,
                CreditUsageType.IMAGE_GENERATION.getCode(),
                "电商模特重新生成-" + model.getName(),
                modelId,
                "COMMERCE_MODEL"
        );

        model.setStatus("generating");
        model.setErrorMessage(null);
        model.setBaseImageUrl(null);
        model.setUpdatedAt(LocalDateTime.now());
        modelMapper.updateById(model);

        executeCommerceModelImageGenerationAfterCommit(model.getId());
        return toModelVO(model);
    }

    @Override
    public List<CommerceModelVO> listModels(Long seriesId) {
        Long userId = requireUserId();
        requireSeries(seriesId);
        LambdaQueryWrapper<CommerceModel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CommerceModel::getSeriesId, seriesId)
                .eq(CommerceModel::getUserId, userId)
                .orderByDesc(CommerceModel::getCreatedAt);
        return modelMapper.selectList(wrapper).stream().map(this::toModelVO).toList();
    }

    @Override
    public CommerceModelVO getModel(Long modelId) {
        return toModelVO(requireModel(modelId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommerceModelVO confirmModel(Long modelId) {
        CommerceModel model = requireModel(modelId);
        if (isBlank(model.getBaseImageUrl())) {
            throw new BusinessException("模特基础图缺失，无法锁定");
        }
        if (!"pending_review".equals(model.getStatus()) && !"locked".equals(model.getStatus())) {
            throw new BusinessException("只有待锁定的模特可以锁定");
        }
        model.setStatus("locked");
        model.setUpdatedAt(LocalDateTime.now());
        modelMapper.updateById(model);
        return toModelVO(model);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommerceModelVO unlockModel(Long modelId) {
        CommerceModel model = requireModel(modelId);
        if (!"locked".equals(model.getStatus())) {
            throw new BusinessException("只有已锁定的模特可以解锁");
        }
        model.setStatus("pending_review");
        model.setUpdatedAt(LocalDateTime.now());
        modelMapper.updateById(model);
        return toModelVO(model);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommerceProductVO createProduct(Long modelId, CommerceProductCreateRequest request) {
        Long userId = requireUserId();
        CommerceModel model = requireModel(modelId);
        if (!model.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该模特");
        }
        if (!"locked".equals(model.getStatus())) {
            throw new BusinessException("请先锁定模特再绑定商品");
        }
        if (request == null || isBlank(request.getName())) {
            throw new BusinessException("请输入商品名称");
        }
        List<CommerceProductCreateRequest.ProductImageItem> productImages = normalizeProductImages(request);

        CommerceProduct product = new CommerceProduct();
        product.setModelId(modelId);
        product.setSeriesId(model.getSeriesId());
        product.setUserId(userId);
        product.setName(request.getName().trim());
        product.setImageUrl(productImages.get(0).getUrl());
        product.setImagesJson(JSON.toJSONString(productImages));
        product.setProductType(trimToNull(request.getProductType()));
        product.setSellingPoints(null);
        product.setFabricFitColorSize(null);
        product.setTargetAudience(null);
        product.setPriceOffer(null);
        product.setTryOnRequirements(null);
        product.setStatus("active");
        product.setIsDeleted(0);
        product.setCreatedAt(LocalDateTime.now());
        product.setUpdatedAt(LocalDateTime.now());
        productMapper.insert(product);
        return toProductVO(product);
    }

    @Override
    public List<CommerceProductVO> listProducts(Long modelId) {
        Long userId = requireUserId();
        requireModel(modelId);
        LambdaQueryWrapper<CommerceProduct> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CommerceProduct::getModelId, modelId)
                .eq(CommerceProduct::getUserId, userId)
                .orderByDesc(CommerceProduct::getCreatedAt);
        List<CommerceProduct> products = productMapper.selectList(wrapper);
        return products.stream().map(this::toProductVO).toList();
    }

    @Override
    public String uploadProductImage(MultipartFile file) {
        requireUserId();
        validateProductImageFile(file);
        try {
            String url = ossService.uploadImage(
                    file.getBytes(),
                    "commerce/products",
                    file.getContentType(),
                    imageExtensionFromFilename(file.getOriginalFilename()));
            if (isBlank(url)) {
                throw new BusinessException("商品图片上传失败");
            }
            return url;
        } catch (IOException e) {
            throw new BusinessException("读取商品图片失败");
        }
    }

    @Override
    public List<CommerceSceneVO> listScenes(Long seriesId) {
        Long userId = requireUserId();
        requireSeries(seriesId);
        LambdaQueryWrapper<CommerceScene> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CommerceScene::getSeriesId, seriesId)
                .eq(CommerceScene::getUserId, userId)
                .orderByDesc(CommerceScene::getUpdatedAt);
        return sceneMapper.selectList(wrapper).stream().map(this::toSceneVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<CommerceSceneVO> prepareSceneBatch(Long seriesId, CommerceSceneBatchCreateRequest request) {
        Long userId = requireUserId();
        CommerceSeries series = requireSeries(seriesId);
        if (!series.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该电商系列");
        }
        List<CommerceScene> scenes = buildSceneBatch(seriesId, userId, request);
        scenes.forEach(sceneMapper::insert);
        return scenes.stream().map(this::toSceneVO).toList();
    }

    @Override
    public List<CommerceSceneVO> startSceneBatch(Long seriesId, CommerceSceneGenerationStartRequest request) {
        Long userId = requireUserId();
        CommerceSeries series = requireSeries(seriesId);
        if (!series.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该电商系列");
        }
        List<Long> sceneIds = request == null || request.getSceneIds() == null ? List.of() : request.getSceneIds().stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (sceneIds.isEmpty()) {
            throw new BusinessException("请选择要生成的场景");
        }
        List<CommerceScene> scenes = sceneMapper.selectBatchIds(sceneIds);
        if (scenes.size() != sceneIds.size()) {
            throw new BusinessException("场景不存在或无权访问");
        }
        for (CommerceScene scene : scenes) {
            if (!seriesId.equals(scene.getSeriesId()) || !userId.equals(scene.getUserId())) {
                throw new BusinessException("场景不存在或无权访问");
            }
            executeSceneImageGenerationAfterCommit(scene.getId());
        }
        return scenes.stream().map(this::toSceneVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommerceProjectVO createProject(Long productId, CommerceProjectCreateRequest request) {
        Long userId = requireUserId();
        CommerceProduct product = requireProduct(productId);
        if (!product.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该商品");
        }
        CommerceModel model = requireModel(product.getModelId());
        if (!"locked".equals(model.getStatus())) {
            throw new BusinessException("请先审核并锁定模特");
        }
        if (isBlank(model.getBaseImageUrl())) {
            throw new BusinessException("模特基础图缺失，无法生成视频");
        }
        if (isBlank(product.getImageUrl())) {
            throw new BusinessException("商品图片缺失，无法生成视频");
        }

        String contentType = request == null ? "talking" : defaultIfBlank(request.getContentType(), "talking");
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException("不支持的内容类型");
        }
        String aspectRatio = request == null ? DEFAULT_ASPECT_RATIO : defaultIfBlank(request.getAspectRatio(), DEFAULT_ASPECT_RATIO);
        if (!ALLOWED_ASPECT_RATIOS.contains(aspectRatio)) {
            throw new BusinessException("不支持的画幅比例");
        }
        CommerceProduct shotProduct = copyProductWithProjectSettings(product, request, contentType);
        List<String> sceneImageUrls = "try_on".equals(contentType)
                ? resolveProjectSceneImageUrls(product.getSeriesId(), userId, request)
                : List.of();
        if ("try_on".equals(contentType) && sceneImageUrls.isEmpty()) {
            throw new BusinessException("请选择1张试穿场景图");
        }
        shotProduct.setFabricFitColorSize(sceneImageUrls.isEmpty() ? null : JSON.toJSONString(sceneImageUrls));

        CommerceProject project = new CommerceProject();
        project.setSeriesId(product.getSeriesId());
        project.setModelId(model.getId());
        project.setProductId(product.getId());
        project.setUserId(userId);
        project.setContentType(contentType);
        project.setAspectRatio(aspectRatio);
        project.setModelImageUrl(model.getBaseImageUrl());
        project.setProductImageUrl(product.getImageUrl());
        project.setSceneImageUrlsJson(sceneImageUrls.isEmpty() ? null : JSON.toJSONString(sceneImageUrls));
        project.setTryOnFirstFrameEnabled(0);
        project.setTryOnFirstFrameAspectRatio(null);
        project.setTryOnFirstFrameResolution(null);
        project.setTryOnFirstFrameUrl(null);
        project.setTryOnFirstFrameStatus(null);
        project.setTryOnFirstFrameError(null);
        project.setTryOnFirstFrameConfirmed(0);
        project.setStatus("try_on".equals(contentType) ? "generating" : "draft");
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        projectMapper.insert(project);

        List<CommerceShot> shots = buildDraftShots(project, model, shotProduct);
        shots.forEach(shotMapper::insert);

        CommerceProjectVO vo = toProjectVO(project);
        vo.setShots(shots.stream().map(this::toShotVO).toList());
        return vo;
    }

    @Override
    public CommerceProjectVO getProject(Long projectId) {
        CommerceProject project = requireProject(projectId);
        CommerceProjectVO vo = toProjectVO(project);
        vo.setShots(listProjectShots(projectId).stream().map(this::toShotVO).toList());
        return vo;
    }

    @Override
    public List<CommerceProjectVO> listProductProjects(Long productId, String contentType) {
        Long userId = requireUserId();
        CommerceProduct product = requireProduct(productId);
        if (!userId.equals(product.getUserId())) {
            throw new BusinessException("无权访问该商品");
        }
        String normalizedContentType = defaultIfBlank(contentType, "");
        if (!isBlank(normalizedContentType) && !ALLOWED_CONTENT_TYPES.contains(normalizedContentType)) {
            throw new BusinessException("不支持的内容类型");
        }
        return listProductProjectsForVO(productId, userId, normalizedContentType);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommerceProjectVO startProjectVideoGeneration(Long projectId) {
        CommerceProject project = requireProject(projectId);
        List<CommerceShot> currentShots = listProjectShots(projectId);
        if ("try_on".equals(project.getContentType())) {
            currentShots = rebuildTryOnShotsIfNeeded(project, currentShots);
        }
        if (!"generating".equals(project.getStatus())) {
            project.setStatus("generating");
            project.setErrorMessage(null);
            project.setUpdatedAt(LocalDateTime.now());
            projectMapper.updateById(project);
            currentShots.forEach(shot -> {
                shot.setGenerationStatus("generating");
                shot.setErrorMessage(null);
                shot.setUpdatedAt(LocalDateTime.now());
                shotMapper.updateById(shot);
            });
        }
        executeProjectVideoGenerationAfterCommit(projectId, project.getUserId());
        CommerceProjectVO vo = toProjectVO(project);
        vo.setShots(currentShots.stream().map(this::toShotVO).toList());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommerceProjectVO generateProjectVideo(Long projectId) {
        CommerceProject project = requireProject(projectId);
        if (isBlank(project.getModelImageUrl()) || isBlank(project.getProductImageUrl())) {
            throw new BusinessException("模特图和商品图不能为空");
        }
        List<String> sceneImageUrls = parseSceneImageUrls(project.getSceneImageUrlsJson());
        if ("try_on".equals(project.getContentType()) && sceneImageUrls.isEmpty()) {
            throw new BusinessException("请选择1张试穿场景图");
        }

        List<CommerceShot> shots = listProjectShots(projectId);
        if (shots == null || shots.isEmpty()) {
            throw new BusinessException("请先生成带货分镜");
        }

        int requiredCredits = shots.stream()
                .mapToInt(shot -> CreditConstants.calculateCredits(
                        normalizeTryOnVideoResolution(shot.getResolution()),
                        normalizeTryOnVideoDuration(shot.getDurationSeconds()),
                        normalizeTryOnVideoModel(shot.getVideoModel())))
                .sum();
        userService.deductCredits(project.getUserId(), requiredCredits, CreditUsageType.VIDEO_GENERATION.getCode(),
                "电商带货视频生成-项目" + projectId, projectId, "COMMERCE_PROJECT");

        boolean completed = true;
        String firstError = null;
        for (CommerceShot shot : shots) {
            try {
                shot.setGenerationStatus("generating");
                shot.setErrorMessage(null);
                shot.setUpdatedAt(LocalDateTime.now());
                shotMapper.updateById(shot);

                SeedanceResponse response = seedanceService.generateVideo(buildVideoRequest(project, shot));
                if ("completed".equalsIgnoreCase(response.getStatus()) || "succeeded".equalsIgnoreCase(response.getStatus())) {
                    shot.setVideoUrl(response.getVideoUrl());
                    shot.setGenerationStatus("completed");
                } else {
                    completed = false;
                    firstError = response.getErrorMessage() != null ? response.getErrorMessage() : "视频生成失败";
                    shot.setGenerationStatus("failed");
                    shot.setErrorMessage(firstError);
                }
            } catch (Exception e) {
                completed = false;
                firstError = e.getMessage();
                shot.setGenerationStatus("failed");
                shot.setErrorMessage(e.getMessage());
            } finally {
                shot.setUpdatedAt(LocalDateTime.now());
                shotMapper.updateById(shot);
            }
        }

        if (!completed) {
            userService.refundCredits(project.getUserId(), requiredCredits,
                    "电商带货视频生成失败返还-项目" + projectId, projectId, "COMMERCE_PROJECT");
            project.setStatus("failed");
            project.setErrorMessage(firstError);
        } else {
            project.setStatus("completed");
            project.setErrorMessage(null);
        }
        project.setUpdatedAt(LocalDateTime.now());
        projectMapper.updateById(project);

        CommerceProjectVO vo = toProjectVO(project);
        vo.setShots(shots.stream().map(this::toShotVO).toList());
        return vo;
    }

    private Long requireUserId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        return userId;
    }

    private CommerceSeries requireSeries(Long seriesId) {
        Long userId = requireUserId();
        CommerceSeries series = seriesMapper.selectById(seriesId);
        if (series == null || !userId.equals(series.getUserId())) {
            throw new BusinessException("电商系列不存在");
        }
        return series;
    }

    private CommerceModel requireModel(Long modelId) {
        Long userId = requireUserId();
        CommerceModel model = modelMapper.selectById(modelId);
        if (model == null || !userId.equals(model.getUserId())) {
            throw new BusinessException("模特不存在");
        }
        return model;
    }

    private CommerceProduct requireProduct(Long productId) {
        Long userId = requireUserId();
        CommerceProduct product = productMapper.selectById(productId);
        if (product == null || !userId.equals(product.getUserId())) {
            throw new BusinessException("商品不存在");
        }
        return product;
    }

    private CommerceProject requireProject(Long projectId) {
        Long userId = requireUserId();
        CommerceProject project = projectMapper.selectById(projectId);
        if (project == null || !userId.equals(project.getUserId())) {
            throw new BusinessException("带货视频项目不存在");
        }
        return project;
    }

    private CommerceSeriesVO toSeriesVO(CommerceSeries series) {
        CommerceSeriesVO vo = new CommerceSeriesVO();
        vo.setId(series.getId());
        vo.setName(series.getName());
        vo.setCategory(series.getCategory());
        vo.setVisualStyle(series.getVisualStyle());
        vo.setDefaultAspectRatio(series.getDefaultAspectRatio());
        vo.setEnabledContentTypes(splitCsv(series.getEnabledContentTypes()));
        vo.setStatus(series.getStatus());
        vo.setCreatedAt(series.getCreatedAt());
        vo.setUpdatedAt(series.getUpdatedAt());
        return vo;
    }

    private CommerceModelVO toModelVO(CommerceModel model) {
        CommerceModelVO vo = new CommerceModelVO();
        vo.setId(model.getId());
        vo.setSeriesId(model.getSeriesId());
        vo.setName(model.getName());
        vo.setSourceType(model.getSourceType());
        vo.setGenderAge(model.getGenderAge());
        vo.setBodyProfile(model.getBodyProfile());
        vo.setStyleTags(model.getStyleTags());
        vo.setSpeakingPersona(model.getSpeakingPersona());
        vo.setAppearancePrompt(model.getAppearancePrompt());
        vo.setBaseImageUrl(model.getBaseImageUrl());
        vo.setModelAspectRatio(model.getModelAspectRatio());
        vo.setStatus(model.getStatus());
        vo.setErrorMessage(model.getErrorMessage());
        vo.setCreatedAt(model.getCreatedAt());
        vo.setUpdatedAt(model.getUpdatedAt());
        return vo;
    }

    private CommerceProductVO toProductVO(CommerceProduct product) {
        CommerceProductVO vo = new CommerceProductVO();
        vo.setId(product.getId());
        vo.setModelId(product.getModelId());
        vo.setSeriesId(product.getSeriesId());
        vo.setName(product.getName());
        vo.setImageUrl(product.getImageUrl());
        vo.setProductType(product.getProductType());
        vo.setSellingPoints(product.getSellingPoints());
        vo.setFabricFitColorSize(product.getFabricFitColorSize());
        vo.setTargetAudience(product.getTargetAudience());
        vo.setPriceOffer(product.getPriceOffer());
        vo.setTryOnRequirements(product.getTryOnRequirements());
        vo.setImages(toProductImageVOs(product));
        vo.setTryOnProjects(listProductProjectsForVO(product.getId(), product.getUserId(), "try_on"));
        vo.setStatus(product.getStatus());
        vo.setCreatedAt(product.getCreatedAt());
        vo.setUpdatedAt(product.getUpdatedAt());
        return vo;
    }

    private List<CommerceProductCreateRequest.ProductImageItem> normalizeProductImages(CommerceProductCreateRequest request) {
        List<CommerceProductCreateRequest.ProductImageItem> requestImages = request.getImages();
        if ((requestImages == null || requestImages.isEmpty()) && !isBlank(request.getImageUrl())) {
            CommerceProductCreateRequest.ProductImageItem legacyImage = new CommerceProductCreateRequest.ProductImageItem();
            legacyImage.setUrl(request.getImageUrl().trim());
            legacyImage.setUsage("主图");
            requestImages = List.of(legacyImage);
        }
        if (requestImages == null || requestImages.isEmpty()) {
            throw new BusinessException("请上传商品图片");
        }
        if (requestImages.size() > 5) {
            throw new BusinessException("最多上传5张商品图片");
        }

        List<CommerceProductCreateRequest.ProductImageItem> normalized = new ArrayList<>();
        for (CommerceProductCreateRequest.ProductImageItem image : requestImages) {
            if (image == null || isBlank(image.getUrl())) {
                throw new BusinessException("请上传商品图片");
            }
            if (isBlank(image.getUsage())) {
                throw new BusinessException("请选择商品图片用途");
            }
            String usage = image.getUsage().trim();
            if (!ALLOWED_PRODUCT_IMAGE_USAGES.contains(usage)) {
                throw new BusinessException("不支持的商品图片用途");
            }
            CommerceProductCreateRequest.ProductImageItem item = new CommerceProductCreateRequest.ProductImageItem();
            item.setUrl(image.getUrl().trim());
            item.setUsage(usage);
            normalized.add(item);
        }

        normalized.sort(Comparator.comparingInt(item -> productImageUsageOrder(item.getUsage())));
        return normalized;
    }

    private List<CommerceProductVO.ProductImageVO> toProductImageVOs(CommerceProduct product) {
        List<CommerceProductCreateRequest.ProductImageItem> images = parseProductImages(product);
        return images.stream().map(image -> {
            CommerceProductVO.ProductImageVO vo = new CommerceProductVO.ProductImageVO();
            vo.setUrl(image.getUrl());
            vo.setUsage(image.getUsage());
            return vo;
        }).toList();
    }

    private List<CommerceProjectVO> listProductProjectsForVO(Long productId, Long userId, String contentType) {
        if (productId == null || userId == null) {
            return List.of();
        }
        LambdaQueryWrapper<CommerceProject> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CommerceProject::getProductId, productId)
                .eq(CommerceProject::getUserId, userId);
        if (!isBlank(contentType)) {
            wrapper.eq(CommerceProject::getContentType, contentType);
        }
        wrapper.orderByDesc(CommerceProject::getCreatedAt);
        return projectMapper.selectList(wrapper).stream()
                .map(project -> {
                    CommerceProjectVO vo = toProjectVO(project);
                    vo.setShots(listProjectShots(project.getId()).stream().map(this::toShotVO).toList());
                    return vo;
                })
                .toList();
    }

    private List<CommerceProductCreateRequest.ProductImageItem> parseProductImages(CommerceProduct product) {
        if (product == null) {
            return List.of();
        }
        if (!isBlank(product.getImagesJson())) {
            try {
                List<CommerceProductCreateRequest.ProductImageItem> parsed = JSON.parseArray(
                        product.getImagesJson(), CommerceProductCreateRequest.ProductImageItem.class);
                if (parsed != null && !parsed.isEmpty()) {
                    return normalizeParsedProductImages(parsed, product.getImageUrl());
                }
            } catch (Exception e) {
                log.warn("商品图片列表解析失败: productId={}", product.getId(), e);
            }
        }
        if (isBlank(product.getImageUrl())) {
            return List.of();
        }
        CommerceProductCreateRequest.ProductImageItem fallback = new CommerceProductCreateRequest.ProductImageItem();
        fallback.setUrl(product.getImageUrl().trim());
        fallback.setUsage("主图");
        return List.of(fallback);
    }

    private List<CommerceProductCreateRequest.ProductImageItem> normalizeParsedProductImages(
            List<CommerceProductCreateRequest.ProductImageItem> images,
            String fallbackImageUrl) {
        List<CommerceProductCreateRequest.ProductImageItem> normalized = images.stream()
                .filter(image -> image != null && !isBlank(image.getUrl()))
                .map(image -> {
                    CommerceProductCreateRequest.ProductImageItem item = new CommerceProductCreateRequest.ProductImageItem();
                    item.setUrl(image.getUrl().trim());
                    item.setUsage(ALLOWED_PRODUCT_IMAGE_USAGES.contains(defaultIfBlank(image.getUsage(), "").trim())
                            ? image.getUsage().trim()
                            : "细节图");
                    return item;
                })
                .toList();
        if (normalized.isEmpty() && !isBlank(fallbackImageUrl)) {
            CommerceProductCreateRequest.ProductImageItem fallback = new CommerceProductCreateRequest.ProductImageItem();
            fallback.setUrl(fallbackImageUrl.trim());
            fallback.setUsage("主图");
            return List.of(fallback);
        }
        return normalized.stream()
                .sorted(Comparator.comparingInt(item -> productImageUsageOrder(item.getUsage())))
                .toList();
    }

    private List<String> resolveProjectSceneImageUrls(Long seriesId, Long userId, CommerceProjectCreateRequest request) {
        List<Long> sceneIds = request == null || request.getSceneIds() == null ? List.of() : request.getSceneIds();
        List<Long> normalizedIds = sceneIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            return List.of();
        }
        if (normalizedIds.size() > 1) {
            throw new BusinessException("试穿视频只能选择1张场景图");
        }
        List<CommerceScene> scenes = sceneMapper.selectBatchIds(normalizedIds);
        if (scenes.size() != normalizedIds.size()) {
            throw new BusinessException("请选择有效的场景图");
        }
        List<String> urls = new ArrayList<>();
        for (CommerceScene scene : scenes) {
            if (!userId.equals(scene.getUserId()) || !seriesId.equals(scene.getSeriesId())) {
                throw new BusinessException("请选择当前系列的场景图");
            }
            if (!"completed".equals(scene.getStatus()) || isBlank(scene.getImageUrl())) {
                throw new BusinessException("请选择已生成完成的场景图");
            }
            urls.add(scene.getImageUrl().trim());
        }
        return urls;
    }

    private List<String> parseSceneImageUrls(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        try {
            List<String> urls = JSON.parseArray(value, String.class);
            if (urls == null) {
                return List.of();
            }
            return urls.stream()
                    .filter(url -> !isBlank(url))
                    .map(String::trim)
                    .toList();
        } catch (Exception e) {
            log.warn("电商项目场景图解析失败: {}", value, e);
            return List.of();
        }
    }

    private List<CommerceShot> listProjectShots(Long projectId) {
        LambdaQueryWrapper<CommerceShot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CommerceShot::getProjectId, projectId)
                .orderByAsc(CommerceShot::getSortOrder);
        return shotMapper.selectList(wrapper);
    }

    private List<CommerceShot> rebuildTryOnShotsIfNeeded(CommerceProject project, List<CommerceShot> currentShots) {
        if (currentShots == null || currentShots.isEmpty()) {
            return currentShots == null ? List.of() : currentShots;
        }
        String requirements = extractTryOnRequirementsFromShots(currentShots);
        if (isBlank(requirements)) {
            return currentShots;
        }
        List<String> expectedTitles = parseTryOnDisplayActions(requirements);
        List<String> currentTitles = currentShots.stream()
                .map(CommerceShot::getTitle)
                .toList();
        if (currentTitles.equals(expectedTitles)) {
            return currentShots;
        }
        CommerceModel model = requireModel(project.getModelId());
        CommerceProduct product = requireProduct(project.getProductId());
        CommerceProduct shotProduct = copyProductForExistingTryOnProject(product, currentShots, requirements);
        List<CommerceShot> rebuiltShots = buildDraftShots(project, model, shotProduct);
        currentShots.stream()
                .map(CommerceShot::getId)
                .filter(id -> id != null)
                .forEach(shotMapper::deleteById);
        rebuiltShots.forEach(shotMapper::insert);
        return rebuiltShots;
    }

    private CommerceProduct copyProductForExistingTryOnProject(
            CommerceProduct product,
            List<CommerceShot> currentShots,
            String requirements) {
        CommerceProduct copy = new CommerceProduct();
        copy.setId(product.getId());
        copy.setModelId(product.getModelId());
        copy.setSeriesId(product.getSeriesId());
        copy.setUserId(product.getUserId());
        copy.setName(product.getName());
        copy.setImageUrl(product.getImageUrl());
        copy.setImagesJson(product.getImagesJson());
        copy.setProductType(product.getProductType());
        copy.setTryOnRequirements(requirements);
        CommerceShot firstShot = currentShots.get(0);
        copy.setProjectDurationSeconds(normalizeTryOnVideoDuration(firstShot.getDurationSeconds()));
        copy.setProjectResolution(normalizeTryOnVideoResolution(firstShot.getResolution()));
        copy.setProjectVideoModel(normalizeTryOnVideoModel(firstShot.getVideoModel()));
        return copy;
    }

    private String extractTryOnRequirementsFromShots(List<CommerceShot> shots) {
        for (CommerceShot shot : shots) {
            String visualDescription = defaultIfBlank(shot.getVisualDescription(), "");
            int start = visualDescription.lastIndexOf("展示方式：");
            if (start < 0) {
                continue;
            }
            int end = visualDescription.indexOf('。', start);
            String actions = end >= 0
                    ? visualDescription.substring(start, end)
                    : visualDescription.substring(start);
            if (!parseTryOnDisplayActions(actions).isEmpty()) {
                return actions;
            }
        }
        return null;
    }

    private void executeProjectVideoGenerationAfterCommit(Long projectId, Long userId) {
        Runnable task = () -> {
            if (userId != null) {
                UserContextHolder.setUserId(userId);
            }
            try {
                generateProjectVideo(projectId);
            } finally {
                UserContextHolder.clear();
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    videoGenerateExecutor.execute(task);
                }
            });
            return;
        }
        videoGenerateExecutor.execute(task);
    }

    private int productImageUsageOrder(String usage) {
        if ("主图".equals(usage)) {
            return 0;
        }
        if ("上身参考".equals(usage)) {
            return 1;
        }
        return 2;
    }

    private CommerceProjectVO toProjectVO(CommerceProject project) {
        CommerceProjectVO vo = new CommerceProjectVO();
        vo.setId(project.getId());
        vo.setSeriesId(project.getSeriesId());
        vo.setModelId(project.getModelId());
        vo.setProductId(project.getProductId());
        vo.setContentType(project.getContentType());
        vo.setAspectRatio(project.getAspectRatio());
        vo.setModelImageUrl(project.getModelImageUrl());
        vo.setProductImageUrl(project.getProductImageUrl());
        vo.setTryOnFirstFrameEnabled(project.getTryOnFirstFrameEnabled() != null && project.getTryOnFirstFrameEnabled() == 1);
        vo.setTryOnFirstFrameAspectRatio(project.getTryOnFirstFrameAspectRatio());
        vo.setTryOnFirstFrameResolution(project.getTryOnFirstFrameResolution());
        vo.setTryOnFirstFrameUrl(project.getTryOnFirstFrameUrl());
        vo.setTryOnFirstFrameStatus(project.getTryOnFirstFrameStatus());
        vo.setTryOnFirstFrameError(project.getTryOnFirstFrameError());
        vo.setTryOnFirstFrameConfirmed(project.getTryOnFirstFrameConfirmed() != null && project.getTryOnFirstFrameConfirmed() == 1);
        vo.setSceneImageUrls(parseSceneImageUrls(project.getSceneImageUrlsJson()));
        vo.setStatus(project.getStatus());
        vo.setErrorMessage(project.getErrorMessage());
        vo.setCreatedAt(project.getCreatedAt());
        vo.setUpdatedAt(project.getUpdatedAt());
        return vo;
    }

    private CommerceSceneVO toSceneVO(CommerceScene scene) {
        CommerceSceneVO vo = new CommerceSceneVO();
        vo.setId(scene.getId());
        vo.setSeriesId(scene.getSeriesId());
        vo.setName(scene.getName());
        vo.setSceneType(scene.getSceneType());
        vo.setLibraryType(scene.getLibraryType());
        vo.setPrompt(scene.getPrompt());
        vo.setImageUrl(scene.getImageUrl());
        vo.setAspectRatio(scene.getAspectRatio());
        vo.setResolution(scene.getResolution());
        vo.setStatus(scene.getStatus());
        vo.setErrorMessage(scene.getErrorMessage());
        vo.setCreatedAt(scene.getCreatedAt());
        vo.setUpdatedAt(scene.getUpdatedAt());
        return vo;
    }

    private CommerceShotVO toShotVO(CommerceShot shot) {
        CommerceShotVO vo = new CommerceShotVO();
        vo.setId(shot.getId());
        vo.setProjectId(shot.getProjectId());
        vo.setSortOrder(shot.getSortOrder());
        vo.setTitle(shot.getTitle());
        vo.setVisualDescription(shot.getVisualDescription());
        vo.setVoiceoverText(shot.getVoiceoverText());
        vo.setDurationSeconds(shot.getDurationSeconds());
        vo.setResolution(shot.getResolution());
        vo.setVideoModel(shot.getVideoModel());
        vo.setVideoUrl(shot.getVideoUrl());
        vo.setGenerationStatus(shot.getGenerationStatus());
        vo.setErrorMessage(shot.getErrorMessage());
        return vo;
    }

    private SeedanceRequest buildVideoRequest(CommerceProject project, CommerceShot shot) {
        String videoModel = normalizeTryOnVideoModel(shot.getVideoModel());
        String resolution = normalizeResolutionForModel(videoModel, normalizeTryOnVideoResolution(shot.getResolution()));
        int durationSeconds = normalizeTryOnVideoDuration(shot.getDurationSeconds());
        int[] size = calculateVideoSize(resolution, project.getAspectRatio());
        CommerceProduct product = project.getProductId() == null ? null : productMapper.selectById(project.getProductId());
        List<CommerceProductCreateRequest.ProductImageItem> productReferences = buildProjectProductReferences(project, product);
        List<String> sceneReferences = parseSceneImageUrls(project.getSceneImageUrlsJson()).stream()
                .limit(1)
                .toList();
        SeedanceRequest request = new SeedanceRequest();
        request.setPrompt(buildVideoPrompt(project, shot, productReferences, sceneReferences));
        request.setDuration(durationSeconds);
        request.setResolution(resolution);
        request.setRatio(project.getAspectRatio());
        request.setWidth(size[0]);
        request.setHeight(size[1]);
        request.setGenerateAudio(true);
        request.setWatermark(false);
        request.setModel(convertToApiModel(videoModel, resolution));

        List<SeedanceRequest.ReferenceContent> contents = new ArrayList<>();
        contents.add(imageReference(project.getModelImageUrl()));
        productReferences.forEach(reference -> contents.add(imageReference(reference.getUrl())));
        sceneReferences.forEach(url -> contents.add(imageReference(url)));
        request.setContents(contents);
        return request;
    }

    private String buildVideoPrompt(
            CommerceProject project,
            CommerceShot shot,
            List<CommerceProductCreateRequest.ProductImageItem> productReferences,
            List<String> sceneReferences) {
        if (!"try_on".equals(project.getContentType())) {
            return shot.getVisualDescription() + "\n口播：" + defaultIfBlank(shot.getVoiceoverText(), "");
        }
        return commerceTryOnVideoSystemPrompt(project, productReferences, sceneReferences)
                + "\n当前分镜要求："
                + defaultIfBlank(shot.getVisualDescription(), "")
                + "\n口播：" + defaultIfBlank(shot.getVoiceoverText(), "");
    }

    private String commerceTryOnVideoSystemPrompt(
            CommerceProject project,
            List<CommerceProductCreateRequest.ProductImageItem> productReferences,
            List<String> sceneReferences) {
        return "生成一段电商服装试穿展示视频。"
                + buildTryOnReferenceDescription(productReferences, sceneReferences)
                + "商品还原优先级：主图确定整体款式和主色，上身参考确定穿着状态，细节图补充局部结构和面料细节。"
                + "视频生成要求：直接基于模特参考图、上述商品参考图和场景参考图生成完整试穿视频，不要依赖首帧确认图作为主体合成依据。"
                + "模特需要真实自然地穿着该商品出现在场景中，人物、服装、场景、光影和运动必须统一生成，不能有贴图感、抠图感或漂浮感。"
                + "必须准确保持商品颜色、版型、图案、面料质感、领口、肩线、腰线、衣长袖长、下摆和细节结构。"
                + "展示方式规范：全身站立：完整展示头到脚的穿着效果，人物正面自然站立，衣长、裤长、整体比例和廓形必须清楚；"
                + "转身展示：人物缓慢小幅转身，展示正面到侧面再到背面的连续穿着状态，避免快速旋转和身体扭曲；"
                + "走动展示：人物自然向前或轻微斜向走动，展示面料垂坠、摆动幅度和衣服随身体运动的真实状态；"
                + "细节展示：镜头靠近领口、袖口、纽扣、腰线、下摆、图案或拼接位置，局部清晰但不能裁掉关键穿着关系；"
                + "侧身展示：展示肩线、腰线、胸腰臀围贴合度、衣服厚薄和侧面轮廓，人物保持自然侧身或 45 度角；"
                + "背面展示：展示后背、后腰、后摆、背部图案、开衩或后身结构，不能随意改变商品背面设计；"
                + "半身近景：从头肩到腰胯的中近景展示上半身版型、领口、肩袖、胸前图案和面料质感，脸部与服装都要自然；"
                + "坐姿展示：人物自然坐下或半坐，展示坐姿下衣服褶皱、下摆、腰腹贴合和舒适度，身体比例不能变形；"
                + "抬手展示：轻微抬手或整理袖口，展示袖长、袖口、腋下余量、肩部活动空间和衣服牵拉状态；"
                + "下摆展示：聚焦衣长、下摆线、开衩、腰线、裤裙衔接和垂坠感，保持整体商品结构一致；"
                + "面料特写：靠近展示面料纹理、织法、光泽、厚薄、印花和工艺细节，不能把材质生成成另一种面料；"
                + "搭配展示：允许搭配极简基础单品辅助呈现穿搭效果，但商品本身必须是唯一视觉重点，不要新增抢眼饰品或改变商品。"
                + "动作镜头必须连贯：如果用户选择多个展示方式，必须在同一个场景、同一位模特、同一套服装中，从上一个动作自然过渡到下一个动作，"
                + "可以用轻微跟拍、缓慢推近、自然转身、整理衣服或步伐衔接完成过渡；不要出现硬切跳变、瞬间换姿势或镜头断层。"
                + "人物动作自然克制，适合电商试穿展示，不要夸张表演。"
                + "画面比例为 " + defaultIfBlank(project.getAspectRatio(), DEFAULT_ASPECT_RATIO) + "，视频清晰、稳定、商业摄影质感。"
                + "镜头运动轻微自然，主体始终清晰，服装始终是视觉重点。"
                + "禁止内容：不要改变商品主色和核心图案。不要新增无关服装、饰品、文字、水印、价格牌或宣传字。"
                + "不要把商品图作为角落卡片、悬浮图、贴纸或画中画显示出来。"
                + "不要出现身体变形、脸部漂移、手脚异常、衣服穿帮、图案闪烁、布料结构跳变。"
                + "不要让人物和背景分离，不要出现明显后期合成感。";
    }

    private String buildTryOnReferenceDescription(
            List<CommerceProductCreateRequest.ProductImageItem> productReferences,
            List<String> sceneReferences) {
        StringBuilder description = new StringBuilder();
        description.append("参考图说明：图1是当前商品绑定的模特基础参考图，必须保持同一位模特的身份特征，包括脸型、发型、身材比例、肤色气质和自然体态。");
        int imageIndex = 2;
        for (CommerceProductCreateRequest.ProductImageItem reference : productReferences == null ? List.<CommerceProductCreateRequest.ProductImageItem>of() : productReferences) {
            description.append("图").append(imageIndex).append("是商品").append(reference.getUsage()).append("，")
                    .append(productImageUsagePrompt(reference.getUsage()));
            imageIndex++;
        }
        int productCount = productReferences == null ? 0 : productReferences.size();
        if (productCount > 0) {
            description.append("本次共提供").append(productCount).append("张商品参考图，必须按上述图号和用途综合利用。");
        }
        if (sceneReferences != null && !sceneReferences.isEmpty()) {
            description.append("图").append(imageIndex).append("是用户选中的试穿场景图，用于确定空间环境、光线、背景风格和画面氛围。");
        }
        if (productCount > 1) {
            description.append("如果同一用途有多张图片，需要综合参考，不要只使用其中一张。");
        }
        return description.toString();
    }

    private String productImageUsagePrompt(String usage) {
        if ("主图".equals(usage)) {
            return "用于确定商品整体款式、主色、版型、轮廓和主要穿着效果。";
        }
        if ("上身参考".equals(usage)) {
            return "用于确定真实穿着状态、松紧程度、垂坠感、衣长袖长、肩线腰线和人体贴合关系。";
        }
        return "用于补充面料纹理、图案、纽扣、领口、袖口、腰线、下摆、拼接、装饰和局部工艺。";
    }

    private List<CommerceProductCreateRequest.ProductImageItem> buildProjectProductReferences(CommerceProject project, CommerceProduct product) {
        List<CommerceProductCreateRequest.ProductImageItem> references = parseProductImages(product).stream()
                .filter(image -> !isBlank(image.getUrl()))
                .map(image -> {
                    CommerceProductCreateRequest.ProductImageItem item = new CommerceProductCreateRequest.ProductImageItem();
                    item.setUrl(image.getUrl().trim());
                    item.setUsage(defaultIfBlank(image.getUsage(), "细节图"));
                    return item;
                })
                .collect(
                        ArrayList::new,
                        (items, item) -> {
                            boolean exists = items.stream().anyMatch(existing -> item.getUrl().equals(existing.getUrl()));
                            if (!exists && items.size() < 5) {
                                items.add(item);
                            }
                        },
                        (left, right) -> {
                            for (CommerceProductCreateRequest.ProductImageItem item : right) {
                                boolean exists = left.stream().anyMatch(existing -> item.getUrl().equals(existing.getUrl()));
                                if (!exists && left.size() < 5) {
                                    left.add(item);
                                }
                            }
                        }
                );
        if (!references.isEmpty()) {
            return references;
        }
        if (isBlank(project.getProductImageUrl())) {
            return List.of();
        }
        CommerceProductCreateRequest.ProductImageItem fallback = new CommerceProductCreateRequest.ProductImageItem();
        fallback.setUrl(project.getProductImageUrl().trim());
        fallback.setUsage("主图");
        return List.of(fallback);
    }

    private SeedanceRequest.ReferenceContent imageReference(String url) {
        SeedanceRequest.ReferenceContent content = new SeedanceRequest.ReferenceContent();
        content.setType("image_url");
        content.setRole("reference_image");
        SeedanceRequest.ImageUrl imageUrl = new SeedanceRequest.ImageUrl();
        imageUrl.setUrl(url);
        content.setImageUrl(imageUrl);
        return content;
    }

    private ImageGenerateRequest buildCommerceModelImageRequest(CommerceModel model) {
        String gender = parseModelGender(model.getGenderAge());
        String bodyProfile = resolveBodyProfile(model.getBodyProfile());
        String prompt = model.getAppearancePrompt();
        if (!isBlank(bodyProfile)) {
            prompt = defaultIfBlank(prompt, "") + "\n身材设定：" + bodyProfile;
        }
        return ImageGenerateRequest.builder()
                .roleName(model.getName())
                .customPrompt(prompt)
                .originalPrompt(model.getAppearancePrompt())
                .aspectRatio(DEFAULT_MODEL_ASPECT_RATIO)
                .quality("4k")
                .styleKeywords(buildCommerceModelStyleKeywords(gender, bodyProfile))
                .seriesId(model.getSeriesId())
                .build();
    }

    private void executeSceneImageGenerationAfterCommit(Long sceneId) {
        Runnable task = () -> generateSceneImageAsync(sceneId);
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

    private void generateSceneImageAsync(Long sceneId) {
        CommerceScene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            log.warn("电商场景图异步生成跳过，场景不存在: sceneId={}", sceneId);
            return;
        }
        try {
            ImageGenerateResponse imageResponse = imageGenerateService.generateCommerceSceneImage(buildSceneImageRequest(scene));
            applySceneImageResult(sceneId, imageResponse);
        } catch (Exception e) {
            log.error("电商场景图异步生成失败: sceneId={}", sceneId, e);
            ImageGenerateResponse failedResponse = new ImageGenerateResponse();
            failedResponse.setStatus("failed");
            failedResponse.setErrorMessage(e.getMessage());
            applySceneImageResult(sceneId, failedResponse);
        }
    }

    private ImageGenerateRequest buildSceneImageRequest(CommerceScene scene) {
        return ImageGenerateRequest.builder()
                .roleName(scene.getName())
                .customPrompt(scene.getPrompt())
                .aspectRatio(scene.getAspectRatio())
                .quality(scene.getResolution())
                .seriesId(scene.getSeriesId())
                .build();
    }

    private void applySceneImageResult(Long sceneId, ImageGenerateResponse imageResponse) {
        String status;
        String imageUrl = null;
        String errorMessage = null;
        if (imageResponse != null && "success".equals(imageResponse.getStatus()) && !isBlank(imageResponse.getImageUrl())) {
            status = "completed";
            imageUrl = imageResponse.getImageUrl().trim();
        } else {
            status = "failed";
            errorMessage = imageResponse == null ? "场景图生成失败" : defaultIfBlank(imageResponse.getErrorMessage(), "场景图生成失败");
        }
        UpdateWrapper<CommerceScene> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", sceneId)
                .set("image_url", imageUrl)
                .set("status", status)
                .set("error_message", errorMessage)
                .set("updated_at", LocalDateTime.now());
        sceneMapper.update(null, wrapper);
    }

    private void executeCommerceModelImageGenerationAfterCommit(Long modelId) {
        Runnable task = () -> generateCommerceModelImageAsync(modelId);
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

    private void generateCommerceModelImageAsync(Long modelId) {
        CommerceModel model = modelMapper.selectById(modelId);
        if (model == null) {
            log.warn("电商模特异步生图跳过，模特不存在: modelId={}", modelId);
            return;
        }
        try {
            userService.deductCredits(
                    model.getUserId(),
                    CreditConstants.CREDITS_PER_IMAGE,
                    CreditUsageType.IMAGE_GENERATION.getCode(),
                    "电商模特生成-" + model.getName(),
                    model.getSeriesId(),
                    "COMMERCE_SERIES"
            );
            ImageGenerateResponse imageResponse = imageGenerateService.generateCommerceModelImage(buildCommerceModelImageRequest(model));
            applyCommerceModelImageResult(model, imageResponse);
        } catch (Exception e) {
            log.error("电商模特异步生图失败: modelId={}", modelId, e);
            ImageGenerateResponse failedResponse = new ImageGenerateResponse();
            failedResponse.setStatus("failed");
            failedResponse.setErrorMessage(e.getMessage());
            applyCommerceModelImageResult(model, failedResponse);
        }
    }

    private void applyCommerceModelImageResult(CommerceModel model, ImageGenerateResponse imageResponse) {
        if (imageResponse != null && "success".equals(imageResponse.getStatus()) && !isBlank(imageResponse.getImageUrl())) {
            model.setBaseImageUrl(imageResponse.getImageUrl().trim());
            model.setStatus("pending_review");
            model.setErrorMessage(null);
        } else {
            model.setBaseImageUrl(null);
            model.setStatus("failed");
            String errorMessage = imageResponse == null ? null : imageResponse.getErrorMessage();
            model.setErrorMessage(normalizeCommerceModelGenerationError(errorMessage));
        }
        model.setUpdatedAt(LocalDateTime.now());
        UpdateWrapper<CommerceModel> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", model.getId())
                .set("base_image_url", model.getBaseImageUrl())
                .set("status", model.getStatus())
                .set("error_message", model.getErrorMessage())
                .set("updated_at", model.getUpdatedAt());
        modelMapper.update(null, wrapper);
    }

    private String normalizeCommerceModelGenerationError(String errorMessage) {
        if (isBlank(errorMessage)) {
            return "模特图生成失败，请稍后重新生成";
        }
        String message = errorMessage.trim();
        if (message.contains("OutputImageSensitiveContentDetected") || message.toLowerCase().contains("sensitive")) {
            return "图片内容可能触发安全策略，请调整提示词后重新生成";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private String parseModelGender(String genderAge) {
        if (isBlank(genderAge)) {
            return "女";
        }
        String trimmed = genderAge.trim();
        int separator = trimmed.indexOf('/');
        if (separator > 0) {
            return trimmed.substring(0, separator).trim();
        }
        if (trimmed.contains("儿童")) {
            return "儿童";
        }
        if (trimmed.contains("男")) {
            return "男";
        }
        return "女";
    }

    private int[] calculateVideoSize(String resolution, String aspectRatio) {
        String normalizedResolution = normalizeTryOnVideoResolution(resolution);
        return switch (defaultIfBlank(aspectRatio, DEFAULT_ASPECT_RATIO)) {
            case "16:9" -> switch (normalizedResolution) {
                case "480p" -> new int[]{864, 480};
                case "1080p" -> new int[]{1920, 1080};
                default -> new int[]{1280, 720};
            };
            case "1:1" -> switch (normalizedResolution) {
                case "480p" -> new int[]{640, 640};
                case "1080p" -> new int[]{1440, 1440};
                default -> new int[]{960, 960};
            };
            case "3:4" -> switch (normalizedResolution) {
                case "480p" -> new int[]{544, 736};
                case "1080p" -> new int[]{1248, 1664};
                default -> new int[]{834, 1112};
            };
            case "9:16" -> switch (normalizedResolution) {
                case "480p" -> new int[]{480, 864};
                case "1080p" -> new int[]{1080, 1920};
                default -> new int[]{720, 1280};
            };
            default -> switch (normalizedResolution) {
                case "480p" -> new int[]{480, 864};
                case "1080p" -> new int[]{1080, 1920};
                default -> new int[]{720, 1280};
            };
        };
    }

    private String convertToApiModel(String videoModel, String resolution) {
        return switch (normalizeTryOnVideoModel(videoModel)) {
            case "seedance-2.0" -> "doubao-seedance-2-0-260128";
            case "kling-v3-omni" -> "kling-v3-omni";
            case "seedance-2.0-fast" -> "doubao-seedance-2-0-fast-260128";
            default -> "doubao-seedance-2-0-fast-260128";
        };
    }

    private String normalizeResolutionForModel(String videoModel, String resolution) {
        if ("kling-v3-omni".equals(videoModel) && "480p".equals(resolution)) {
            return "720p";
        }
        if ("seedance-2.0-fast".equals(videoModel) && "1080p".equals(resolution)) {
            return "720p";
        }
        return resolution;
    }

    private int normalizeTryOnVideoDuration(Integer durationSeconds) {
        int duration = durationSeconds == null ? DEFAULT_TRY_ON_VIDEO_DURATION : durationSeconds;
        if (duration < 4 || duration > 15) {
            return DEFAULT_TRY_ON_VIDEO_DURATION;
        }
        return duration;
    }

    private String normalizeTryOnVideoResolution(String resolution) {
        String normalized = defaultIfBlank(resolution, DEFAULT_TRY_ON_VIDEO_RESOLUTION);
        return ALLOWED_TRY_ON_VIDEO_RESOLUTIONS.contains(normalized) ? normalized : DEFAULT_TRY_ON_VIDEO_RESOLUTION;
    }

    private String normalizeTryOnVideoModel(String videoModel) {
        String normalized = defaultIfBlank(videoModel, DEFAULT_TRY_ON_VIDEO_MODEL);
        return ALLOWED_TRY_ON_VIDEO_MODELS.contains(normalized) ? normalized : DEFAULT_TRY_ON_VIDEO_MODEL;
    }

    private List<CommerceShot> buildDraftShots(CommerceProject project, CommerceModel model, CommerceProduct product) {
        List<String[]> script = "try_on".equals(project.getContentType())
                ? tryOnScript(product)
                : talkingScript(product);

        List<CommerceShot> shots = new ArrayList<>();
        for (int i = 0; i < script.size(); i++) {
            String[] item = script.get(i);
            CommerceShot shot = new CommerceShot();
            shot.setProjectId(project.getId());
            shot.setSortOrder(i + 1);
            shot.setTitle(item[0]);
            shot.setVisualDescription(item[1].replace("{model}", model.getName()).replace("{product}", product.getName()));
            shot.setVoiceoverText(item[2].replace("{product}", product.getName()));
            shot.setDurationSeconds(normalizeTryOnVideoDuration(product.getProjectDurationSeconds()));
            shot.setResolution(normalizeTryOnVideoResolution(product.getProjectResolution()));
            shot.setVideoModel(normalizeTryOnVideoModel(product.getProjectVideoModel()));
            shot.setGenerationStatus("try_on".equals(project.getContentType()) ? "generating" : "draft");
            shot.setCreatedAt(LocalDateTime.now());
            shot.setUpdatedAt(LocalDateTime.now());
            shots.add(shot);
        }
        return shots;
    }

    private CommerceProduct copyProductWithProjectSettings(
            CommerceProduct product,
            CommerceProjectCreateRequest request,
            String contentType) {
        CommerceProduct copy = new CommerceProduct();
        copy.setId(product.getId());
        copy.setModelId(product.getModelId());
        copy.setSeriesId(product.getSeriesId());
        copy.setUserId(product.getUserId());
        copy.setName(product.getName());
        copy.setImageUrl(product.getImageUrl());
        copy.setImagesJson(product.getImagesJson());
        copy.setProductType(product.getProductType());
        copy.setFabricFitColorSize(product.getFabricFitColorSize());
        if (request != null && "talking".equals(contentType)) {
            copy.setSellingPoints(trimToNull(request.getSellingPoints()));
            copy.setTargetAudience(trimToNull(request.getTargetAudience()));
            copy.setPriceOffer(trimToNull(request.getPriceOffer()));
        }
        if (request != null && "try_on".equals(contentType)) {
            copy.setTryOnRequirements(trimToNull(request.getTryOnRequirements()));
            copy.setProjectDurationSeconds(normalizeTryOnVideoDuration(request.getDurationSeconds()));
            copy.setProjectResolution(normalizeTryOnVideoResolution(request.getResolution()));
            copy.setProjectVideoModel(normalizeTryOnVideoModel(request.getVideoModel()));
        }
        return copy;
    }

    private List<CommerceScene> buildSceneBatch(Long seriesId, Long userId, CommerceSceneBatchCreateRequest request) {
        if (request == null) {
            throw new BusinessException("请选择要生成的场景");
        }
        String aspectRatio = ALLOWED_ASPECT_RATIOS.contains(defaultIfBlank(request.getAspectRatio(), "16:9"))
                ? defaultIfBlank(request.getAspectRatio(), "16:9")
                : "16:9";
        String resolution = defaultIfBlank(request.getResolution(), "2K").toUpperCase();
        if (!ALLOWED_SCENE_RESOLUTIONS.contains(resolution)) {
            resolution = "2K";
        }
        int countPerScene = request.getCountPerScene() == null ? 1 : Math.max(1, Math.min(request.getCountPerScene(), 4));
        List<String> sceneNames = new ArrayList<>();
        for (String sceneType : defaultList(request.getSceneTypes())) {
            String scene = defaultIfBlank(sceneType, "");
            if (isBlank(scene)) {
                continue;
            }
            if (!ALLOWED_SCENE_TYPES.contains(scene)) {
                throw new BusinessException("不支持的场景类型");
            }
            sceneNames.add(scene);
        }
        defaultList(request.getCustomScenes()).stream()
                .map(scene -> defaultIfBlank(scene, ""))
                .filter(scene -> !isBlank(scene))
                .forEach(scene -> sceneNames.add("自定义:" + scene));
        if (sceneNames.isEmpty()) {
            throw new BusinessException("请选择要生成的场景");
        }

        List<CommerceScene> scenes = new ArrayList<>();
        for (String sceneName : sceneNames) {
            for (int i = 0; i < countPerScene; i++) {
                String actualName = sceneName.startsWith("自定义:") ? sceneName.substring("自定义:".length()) : sceneName;
                CommerceScene scene = new CommerceScene();
                scene.setSeriesId(seriesId);
                scene.setUserId(userId);
                scene.setName(countPerScene > 1 ? actualName + " " + (i + 1) : actualName);
                scene.setSceneType(sceneName.startsWith("自定义:") ? "自定义" : actualName);
                scene.setLibraryType(sceneLibraryType(scene.getSceneType()));
                scene.setAspectRatio(aspectRatio);
                scene.setResolution(resolution);
                scene.setPrompt(buildScenePrompt(scene.getSceneType(), actualName, request.getDescription(), aspectRatio, request.getBrandSceneName()));
                scene.setStatus("generating");
                scene.setIsDeleted(0);
                scene.setCreatedAt(LocalDateTime.now());
                scene.setUpdatedAt(LocalDateTime.now());
                scenes.add(scene);
            }
        }
        return scenes;
    }

    private List<String> defaultList(List<String> value) {
        return value == null ? List.of() : value;
    }

    private String sceneLibraryType(String sceneType) {
        return switch (sceneType) {
            case "品牌场景" -> "品牌场景库";
            case "直播间背景" -> "直播间背景库";
            case "街拍场景" -> "街拍场景库";
            case "棚拍场景" -> "棚拍场景库";
            case "商场试衣间" -> "试衣间场景库";
            case "室内全身镜" -> "镜前场景库";
            default -> "自定义场景库";
        };
    }

    private String buildScenePrompt(String sceneType, String sceneName, String description, String aspectRatio, String brandSceneName) {
        boolean brandScene = "品牌场景".equals(sceneType);
        String base = "生成一张电商服装试穿场景背景图，用于后续虚拟试穿合成。"
                + "场景类型：" + sceneType + "，场景名称：" + sceneName + "，图片比例：" + aspectRatio + "。"
                + scenePromptDetail(sceneType, sceneName, brandSceneName)
                + "构图要求：竖向和横向画面都要保留完整真人模特站立空间，人物预留区位于画面中轴附近，从头顶到脚底都有充足留白，地面/墙面/背景透视稳定，避免强遮挡物穿过身体区域。"
                + "光线要求：商业服装展示质感，柔和均匀补光，肤色和服装颜色后续合成时容易还原，避免强色偏、过曝、死黑阴影、强反光。"
                + "画面质感：真实摄影感，干净高级，细节清晰，背景有层次但不抢主体，适合女装/男装/童装试穿短视频首帧和展示图。";
        if (brandScene) {
            String brandName = defaultIfBlank(brandSceneName, "");
            if (isBlank(brandName)) {
                base += "品牌元素要求：需要出现简洁、清晰、虚构的服装品牌视觉标识，可表现为门头招牌、Logo墙、导视牌或背景广告牌中的一个核心视觉点。"
                        + "品牌元素要自然融入空间，位置醒目但不要遮挡后续真人模特站立区域，作为场景识别的一部分。"
                        + "严格限制：不要出现人物、人体、模特、手脚、服装商品、衣架、假人模特、价格牌、二维码、水印、无关海报、杂乱遮挡物；除品牌相关文字与Logo外，不要出现其他无关文字、水印、二维码、价格牌。";
            } else {
                base += "品牌元素要求：必须出现用户填写的品牌字样「" + brandName + "」，可表现为门头招牌、Logo墙、导视牌或背景广告牌中的一个核心视觉点。"
                        + "品牌字样必须清晰可读，不要翻译、不要改写、不要改变大小写；如果生成Logo，请围绕「" + brandName + "」做简洁服装品牌标识，不要生成其他品牌名。"
                        + "品牌元素要自然融入空间，位置醒目但不要遮挡后续真人模特站立区域，作为场景识别的一部分。"
                        + "严格限制：不要出现人物、人体、模特、手脚、服装商品、衣架、假人模特、价格牌、二维码、水印、无关海报、杂乱遮挡物；除品牌相关文字与Logo外，不要出现其他无关文字、水印、二维码、价格牌。";
            }
        } else {
            base += "严格限制：不要出现人物、人体、模特、手脚、服装商品、衣架、假人模特、品牌字样、价格牌、二维码、文字、水印、Logo、海报、杂乱遮挡物，画面必须无文字、无水印、无Logo。";
        }
        if (!isBlank(description)) {
            base += "补充要求：" + description.trim() + "。";
        }
        return base;
    }

    private String scenePromptDetail(String sceneType, String sceneName, String brandSceneName) {
        String brandName = defaultIfBlank(brandSceneName, "该品牌");
        return switch (sceneType) {
            case "品牌场景" -> "具体场景：围绕「" + brandName + "」设计现代服装品牌视觉空间，可为品牌门头、形象展厅、Logo墙、导视牌或背景广告牌。"
                    + "品牌名称「" + brandName + "」需要作为清晰可读的空间标识出现在画面里，形成可识别的品牌场景；整体保持干净展厅或品牌形象角质感，低饱和高级配色，可有抽象陈列台、柔和背景墙、精致地面材质，但不能出现商品服装。";
            case "直播间背景" -> "具体场景：电商直播间背景，柔和补光、整洁背景墙、直播氛围灯和简洁陈设，画面有销售场域感但不能出现主播、屏幕文字、商品堆叠和明显品牌标识。";
            case "街拍场景" -> "具体场景：适合服装街拍的真实城市环境，可为通勤街景、咖啡店外、商圈步道或干净街角，背景纵深自然，地面平整，主体站立区开阔。";
            case "棚拍场景" -> "具体场景：专业服装棚拍背景，纯色或轻纹理背景纸、柔和影棚灯效、干净地面与背景过渡，适合突出服装版型和颜色。";
            case "商场试衣间" -> "具体场景：高端商场试衣间或试衣区，干净镜面、柔和灯带、简洁墙面和地面，空间真实但不要让镜子里出现人物或服装商品。";
            case "室内全身镜" -> "具体场景：室内全身镜试穿环境，简洁居家或买手店氛围，全身镜、自然光、干净地面，镜面不能反射人物、手脚、拍摄者或商品。";
            default -> "具体场景：" + sceneName + "。用户填写的词仅作为场景主题，需要将其完善为适合电商服装试穿合成的生成空场景背景。"
                    + "围绕该主题设计真实可信的空间环境、地面、背景墙、自然纵深和柔和商业光线，画面必须能让后续可以自然放入真人模特和服装商品。"
                    + "主体落位区保持开阔、干净、无遮挡，人物站立位置前后左右留出足够空间，避免桌椅、门框、装饰物、镜面反射或强透视压到身体区域。"
                    + "不要根据用户词生成正在试穿的人、衣服、商品展示架或广告牌，只生成可承载人物与服装合成的空环境。";
        };
    }

    private List<String[]> talkingScript(CommerceProduct product) {
        String points = defaultIfBlank(product.getSellingPoints(), "版型、材质和上身效果");
        String audience = defaultIfBlank(product.getTargetAudience(), "日常通勤和想要轻松穿搭的人群");
        return List.of(
                new String[]{"开场种草", "真人写实模特 {model} 出镜，身边展示商品 {product} 的清晰商品图，画面干净高级。", "今天分享这件{product}，很适合日常快速出门。"},
                new String[]{"卖点讲解", "模特指向商品图旁的卖点字幕，突出商品细节：商品卖点包括" + points + "。", "它的重点是" + points + "。"},
                new String[]{"细节展示", "商品图放大展示面料、版型、颜色和细节，模特在旁边自然讲解。", "细节处理比较实用，上身不会显得累赘。"},
                new String[]{"适合人群", "模特以轻松口播方式说明适合场景，画面保留商品图和人群标签。", "更推荐给" + audience + "。"},
                new String[]{"收尾引导", "模特微笑看向镜头，商品图定格展示，字幕给出购买理由。", "喜欢这种风格的话，可以直接看同款。"}
        );
    }

    private List<String[]> tryOnScript(CommerceProduct product) {
        String requirements = defaultIfBlank(product.getTryOnRequirements(), "展示方式：" + String.join("、", DEFAULT_TRY_ON_DISPLAY_ACTIONS));
        return parseTryOnDisplayActions(requirements).stream()
                .map(action -> new String[]{
                        action,
                        tryOnActionVisualDescription(action, requirements),
                        tryOnActionVoiceover(action)
                })
                .toList();
    }

    private List<String> parseTryOnDisplayActions(String requirements) {
        String value = defaultIfBlank(requirements, "");
        if (value.startsWith("展示方式：")) {
            value = value.substring("展示方式：".length());
        } else {
            int start = value.indexOf("展示方式：");
            value = start >= 0 ? value.substring(start + "展示方式：".length()) : "";
        }
        int end = value.indexOf('；');
        if (end >= 0) {
            value = value.substring(0, end);
        }
        List<String> selectedActions = Arrays.stream(value.split("[、,，/\\s]+"))
                .map(String::trim)
                .filter(action -> ALLOWED_TRY_ON_DISPLAY_ACTIONS.contains(action))
                .distinct()
                .limit(4)
                .toList();
        return selectedActions.isEmpty() ? DEFAULT_TRY_ON_DISPLAY_ACTIONS : selectedActions;
    }

    private String tryOnActionVisualDescription(String action, String requirements) {
        String actionDescription = switch (action) {
            case "全身站立" -> "真人写实模特 {model} 穿上商品图中的 {product}，正面自然站立，完整展示从头到脚的穿着效果，衣长、裤长、整体比例和服装廓形必须清楚。";
            case "转身展示" -> "真人写实模特 {model} 穿上商品图中的 {product}，进行缓慢小幅转身，连续展示正面、侧面到背面的穿着状态，避免快速旋转和身体扭曲。";
            case "走动展示" -> "真人写实模特 {model} 穿上商品图中的 {product}，自然向前或轻微斜向走动，展示面料垂坠、摆动幅度和衣服随身体运动的真实状态。";
            case "细节展示" -> "镜头靠近真人写实模特 {model} 穿着的 {product}，展示领口、袖口、纽扣、腰线、下摆、图案或拼接位置，局部清晰但不能裁掉关键穿着关系。";
            case "侧身展示" -> "真人写实模特 {model} 穿上商品图中的 {product}，保持自然侧身或 45 度角，展示肩线、腰线、胸腰臀围贴合度、衣服厚薄和侧面轮廓。";
            case "背面展示" -> "真人写实模特 {model} 穿上商品图中的 {product}，自然转到背面展示后背、后腰、后摆、背部图案、开衩或后身结构，不能随意改变商品背面设计。";
            case "半身近景" -> "真人写实模特 {model} 穿上商品图中的 {product}，用头肩到腰胯的中近景展示上半身版型、领口、肩袖、胸前图案和面料质感，脸部与服装都要自然。";
            case "坐姿展示" -> "真人写实模特 {model} 穿上商品图中的 {product}，自然坐下或半坐，展示坐姿下衣服褶皱、下摆、腰腹贴合和舒适度，身体比例不能变形。";
            case "抬手展示" -> "真人写实模特 {model} 穿上商品图中的 {product}，轻微抬手或整理袖口，展示袖长、袖口、腋下余量、肩部活动空间和衣服牵拉状态。";
            case "下摆展示" -> "真人写实模特 {model} 穿上商品图中的 {product}，镜头聚焦衣长、下摆线、开衩、腰线、裤裙衔接和垂坠感，同时保持整体商品结构一致。";
            case "面料特写" -> "镜头靠近真人写实模特 {model} 身上的 {product}，展示面料纹理、织法、光泽、厚薄、印花和工艺细节，不能把材质生成成另一种面料。";
            case "搭配展示" -> "真人写实模特 {model} 穿上商品图中的 {product}，用极简基础单品辅助呈现穿搭效果，商品本身必须是唯一视觉重点，不要新增抢眼饰品或改变商品。";
            default -> "真人写实模特 {model} 穿上商品图中的 {product}，自然展示商品上身效果，画面必须同时参考模特基础图、商品参考图和场景图。";
        };
        return actionDescription
                + "展示方式：" + action + "。"
                + "动作镜头需要和前后展示方式自然衔接，保持同一个场景、同一位模特、同一套服装，不要硬切跳变。"
                + "全程严格参考商品主图、细节图和上身参考，准确保持颜色、版型、图案、面料、衣长袖长和结构细节。"
                + "展示要求：" + requirements + "。"
                + "不要把商品图作为角落参考卡片、悬浮图、贴纸或画中画显示出来。";
    }

    private String tryOnActionVoiceover(String action) {
        return switch (action) {
            case "全身站立" -> "先看整体上身效果，版型和长度都比较直观。";
            case "转身展示" -> "转身看一下侧面和背面的线条。";
            case "走动展示" -> "走动时也能看到面料的垂感和轮廓。";
            case "细节展示" -> "细节位置也给大家看清楚。";
            case "侧身展示" -> "侧面更容易判断肩线、腰线和厚薄。";
            case "背面展示" -> "背后的版型和下摆也一起看一下。";
            case "半身近景" -> "近一点看上半身的剪裁和质感。";
            case "坐姿展示" -> "坐下后也能看到舒适度和褶皱状态。";
            case "抬手展示" -> "抬手时袖长和活动空间也很关键。";
            case "下摆展示" -> "下摆和衣长这里重点看一下。";
            case "面料特写" -> "面料纹理和工艺细节给大家放近看。";
            case "搭配展示" -> "搭配起来整体风格会更完整。";
            default -> "这个角度也能看清上身效果。";
        };
    }

    private List<String> splitCsv(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    private void validateModelChoices(String gender, String modelType, String ethnicity) {
        if (isBlank(gender)) {
            throw new BusinessException("请选择模特性别");
        }
        if (isBlank(modelType)) {
            throw new BusinessException("请选择模特类型");
        }
        if (isBlank(ethnicity)) {
            throw new BusinessException("请选择人种风格");
        }
        if (!isAllowedModelType(gender.trim(), modelType.trim())) {
            throw new BusinessException("模特类型与性别不匹配，请重新选择");
        }
        if (!ALLOWED_ETHNICITIES.contains(ethnicity.trim())) {
            throw new BusinessException("不支持的人种风格");
        }
    }

    private boolean isAllowedModelType(String gender, String modelType) {
        return switch (gender) {
            case "女" -> FEMALE_MODEL_TYPES.contains(modelType);
            case "男" -> MALE_MODEL_TYPES.contains(modelType);
            case "儿童" -> CHILD_MODEL_TYPES.contains(modelType);
            default -> false;
        };
    }

    private String resolveModelAspectRatio(String aspectRatio) {
        return DEFAULT_MODEL_ASPECT_RATIO;
    }

    private String resolveBodyProfile(String bodyProfile) {
        if (isBlank(bodyProfile)) {
            return "标准匀称";
        }
        String trimmed = bodyProfile.trim();
        if (!ALLOWED_BODY_PROFILES.contains(trimmed)) {
            throw new BusinessException("不支持的身材类型");
        }
        return trimmed;
    }

    private void ensureProfileBodyProfile(CommerceModelProfile profile, String bodyProfile) {
        if (profile == null || isBlank(bodyProfile)) {
            return;
        }
        profile.setBodyProfile(bodyProfile);
        profile.setPrompt(sanitizeVisibleCommerceModelPrompt(profile.getPrompt(), bodyProfile));
    }

    private void validateProductImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的商品图片");
        }
        String contentType = file.getContentType();
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if ((contentType == null || !ALLOWED_PRODUCT_IMAGE_TYPES.contains(contentType))
                && !filename.endsWith(".jpg")
                && !filename.endsWith(".jpeg")
                && !filename.endsWith(".png")
                && !filename.endsWith(".webp")) {
            throw new BusinessException(400, "只支持 JPG、PNG、WEBP 格式的商品图片");
        }
        if (file.getSize() > MAX_PRODUCT_IMAGE_SIZE) {
            throw new BusinessException(400, "商品图片大小不能超过10MB");
        }
    }

    private String imageExtensionFromFilename(String filename) {
        if (isBlank(filename) || !filename.contains(".")) {
            return "png";
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        if ("jpeg".equals(extension)) {
            return "jpg";
        }
        if (!List.of("jpg", "png", "webp").contains(extension)) {
            return "png";
        }
        return extension;
    }

    private String sanitizeVisibleCommerceModelPrompt(String prompt, String bodyProfile) {
        if (prompt == null) {
            return null;
        }
        String sanitized = prompt
                .replace("\u773c\u775b", "")
                .replace("\u773c\u795e", "")
                .replaceAll("(?i)e" + "yes?", "");
        if (!isBlank(bodyProfile)) {
            sanitized = sanitized.replace("身材设定：" + bodyProfile, "");
            sanitized = sanitized.replace("身材设定:" + bodyProfile, "");
            sanitized = sanitized.replace(bodyProfile, "");
        }
        return sanitized.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String buildCommerceModelStyleKeywords(String gender, String bodyProfile) {
        String bodyKeyword = isBlank(bodyProfile) ? "标准匀称" : bodyProfile.trim();
        if ("儿童".equals(gender)) {
            return "真人写实, 电商服装带货, 大头特写, 右侧三视图, 纯白背景, " + bodyKeyword + ", 浅色儿童基础打底服, 不透, 低干扰";
        }
        if ("男".equals(gender)) {
            return "真人写实, 电商服装带货, 大头特写, 右侧三视图, 纯白背景, " + bodyKeyword + ", 男模特, 上半身不穿, 上半身裸露, 普通浅色四角内裤, 纯色无花纹, 基础男士款, 低干扰, 惊艳但真实";
        }
        return "真人写实, 电商服装带货, 大头特写, 右侧三视图, 纯白背景, " + bodyKeyword + ", 女模特, 白色微透蕾丝内衣, 低干扰, 惊艳但真实";
    }

    private String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
