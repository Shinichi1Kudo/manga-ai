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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private static final List<String> ALLOWED_FIRST_FRAME_RESOLUTIONS = Arrays.asList("2K", "3K", "4K");
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
        return productMapper.selectList(wrapper).stream().map(this::toProductVO).toList();
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
    public List<CommerceSceneVO> createSceneBatch(Long seriesId, CommerceSceneBatchCreateRequest request) {
        Long userId = requireUserId();
        CommerceSeries series = requireSeries(seriesId);
        if (!series.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该电商系列");
        }
        List<CommerceScene> scenes = buildSceneBatch(seriesId, userId, request);
        scenes.forEach(sceneMapper::insert);
        scenes.forEach(scene -> executeSceneImageGenerationAfterCommit(scene.getId()));
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
        boolean firstFrameEnabled = isTryOnFirstFrameEnabled(request, contentType);
        String firstFrameAspectRatio = normalizeFirstFrameAspectRatio(request);
        String firstFrameResolution = normalizeFirstFrameResolution(request);
        List<String> sceneImageUrls = "try_on".equals(contentType)
                ? resolveProjectSceneImageUrls(product.getSeriesId(), userId, request)
                : List.of();
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
        project.setTryOnFirstFrameEnabled(firstFrameEnabled ? 1 : 0);
        project.setTryOnFirstFrameAspectRatio(firstFrameAspectRatio);
        project.setTryOnFirstFrameResolution(firstFrameResolution);
        project.setTryOnFirstFrameUrl(null);
        project.setTryOnFirstFrameStatus(firstFrameEnabled ? "generating" : null);
        project.setTryOnFirstFrameError(null);
        project.setTryOnFirstFrameConfirmed(0);
        project.setStatus("draft");
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        projectMapper.insert(project);

        List<CommerceShot> shots = buildDraftShots(project, model, shotProduct);
        shots.forEach(shotMapper::insert);

        CommerceProjectVO vo = toProjectVO(project);
        vo.setShots(shots.stream().map(this::toShotVO).toList());
        if (firstFrameEnabled) {
            executeTryOnFirstFrameGenerationAfterCommit(project.getId(), model, product, shotProduct, firstFrameAspectRatio, firstFrameResolution);
        }
        return vo;
    }

    @Override
    public CommerceProjectVO getProject(Long projectId) {
        CommerceProject project = requireProject(projectId);
        LambdaQueryWrapper<CommerceShot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CommerceShot::getProjectId, projectId)
                .orderByAsc(CommerceShot::getSortOrder);
        CommerceProjectVO vo = toProjectVO(project);
        vo.setShots(shotMapper.selectList(wrapper).stream().map(this::toShotVO).toList());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommerceProjectVO confirmTryOnFirstFrame(Long projectId) {
        CommerceProject project = requireProject(projectId);
        if (project.getTryOnFirstFrameEnabled() == null || project.getTryOnFirstFrameEnabled() != 1) {
            throw new BusinessException("当前项目未开启试穿首帧确认");
        }
        if (!"completed".equals(project.getTryOnFirstFrameStatus()) || isBlank(project.getTryOnFirstFrameUrl())) {
            throw new BusinessException("试穿首帧确认图尚未生成完成");
        }

        UpdateWrapper<CommerceProject> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", projectId)
                .set("try_on_first_frame_confirmed", 1)
                .set("updated_at", LocalDateTime.now());
        projectMapper.update(null, wrapper);

        project.setTryOnFirstFrameConfirmed(1);
        project.setUpdatedAt(LocalDateTime.now());
        return toProjectVO(project);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommerceProjectVO generateProjectVideo(Long projectId) {
        CommerceProject project = requireProject(projectId);
        if (isBlank(project.getModelImageUrl()) || isBlank(project.getProductImageUrl())) {
            throw new BusinessException("模特图和商品图不能为空");
        }
        if (project.getTryOnFirstFrameEnabled() != null
                && project.getTryOnFirstFrameEnabled() == 1
                && (project.getTryOnFirstFrameConfirmed() == null || project.getTryOnFirstFrameConfirmed() != 1)) {
            throw new BusinessException("请先确认试穿首帧，再生成视频");
        }

        LambdaQueryWrapper<CommerceShot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CommerceShot::getProjectId, projectId)
                .orderByAsc(CommerceShot::getSortOrder);
        List<CommerceShot> shots = shotMapper.selectList(wrapper);
        if (shots == null || shots.isEmpty()) {
            throw new BusinessException("请先生成带货分镜");
        }

        int requiredCredits = shots.stream()
                .mapToInt(shot -> CreditConstants.calculateCredits("720p", shot.getDurationSeconds(), "seedance-2.0-fast"))
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

    private List<String> buildTryOnFirstFrameReferenceUrls(CommerceModel model, CommerceProduct product, List<String> sceneImageUrls) {
        Set<String> urls = new LinkedHashSet<>();
        if (!isBlank(model.getBaseImageUrl())) {
            urls.add(model.getBaseImageUrl().trim());
        }
        parseProductImages(product).forEach(image -> {
            if (!isBlank(image.getUrl())) {
                urls.add(image.getUrl().trim());
            }
        });
        (sceneImageUrls == null ? List.<String>of() : sceneImageUrls).forEach(url -> {
            if (!isBlank(url)) {
                urls.add(url.trim());
            }
        });
        return new ArrayList<>(urls);
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
        vo.setVideoUrl(shot.getVideoUrl());
        vo.setGenerationStatus(shot.getGenerationStatus());
        vo.setErrorMessage(shot.getErrorMessage());
        return vo;
    }

    private SeedanceRequest buildVideoRequest(CommerceProject project, CommerceShot shot) {
        SeedanceRequest request = new SeedanceRequest();
        request.setPrompt(shot.getVisualDescription() + "\n口播：" + defaultIfBlank(shot.getVoiceoverText(), ""));
        request.setDuration(shot.getDurationSeconds() != null ? shot.getDurationSeconds() : 5);
        request.setResolution("720p");
        request.setRatio(project.getAspectRatio());
        request.setWidth(videoWidth(project.getAspectRatio()));
        request.setHeight(videoHeight(project.getAspectRatio()));
        request.setGenerateAudio(true);
        request.setWatermark(false);
        request.setModel("doubao-seedance-2-0-fast-260128");

        SeedanceRequest.ReferenceContent modelRef = imageReference(project.getModelImageUrl());
        SeedanceRequest.ReferenceContent productRef = imageReference(project.getProductImageUrl());
        List<SeedanceRequest.ReferenceContent> contents = new ArrayList<>();
        contents.add(modelRef);
        contents.add(productRef);
        parseSceneImageUrls(project.getSceneImageUrlsJson()).forEach(url -> contents.add(imageReference(url)));
        request.setContents(contents);
        return request;
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

    private boolean isTryOnFirstFrameEnabled(CommerceProjectCreateRequest request, String contentType) {
        return request != null && "try_on".equals(contentType) && Boolean.TRUE.equals(request.getTryOnFirstFrameEnabled());
    }

    private String normalizeFirstFrameAspectRatio(CommerceProjectCreateRequest request) {
        if (request == null || isBlank(request.getTryOnFirstFrameAspectRatio())) {
            return DEFAULT_ASPECT_RATIO;
        }
        String aspectRatio = request.getTryOnFirstFrameAspectRatio().trim();
        return ALLOWED_ASPECT_RATIOS.contains(aspectRatio) ? aspectRatio : DEFAULT_ASPECT_RATIO;
    }

    private String normalizeFirstFrameResolution(CommerceProjectCreateRequest request) {
        if (request == null || isBlank(request.getTryOnFirstFrameResolution())) {
            return "2K";
        }
        String resolution = request.getTryOnFirstFrameResolution().trim().toUpperCase();
        return ALLOWED_FIRST_FRAME_RESOLUTIONS.contains(resolution) ? resolution : "2K";
    }

    private void executeTryOnFirstFrameGenerationAfterCommit(
            Long projectId,
            CommerceModel model,
            CommerceProduct product,
            CommerceProduct shotProduct,
            String aspectRatio,
            String resolution) {
        Runnable task = () -> generateTryOnFirstFrameAsync(projectId, model, product, shotProduct, aspectRatio, resolution);
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

    private void generateTryOnFirstFrameAsync(
            Long projectId,
            CommerceModel model,
            CommerceProduct product,
            CommerceProduct shotProduct,
            String aspectRatio,
            String resolution) {
        try {
            ImageGenerateResponse imageResponse = imageGenerateService.generateCommerceTryOnFirstFrame(
                    buildTryOnFirstFrameRequest(model, product, shotProduct, aspectRatio, resolution));
            applyTryOnFirstFrameResult(projectId, imageResponse);
        } catch (Exception e) {
            log.error("电商试穿首帧生成失败: projectId={}", projectId, e);
            ImageGenerateResponse failedResponse = new ImageGenerateResponse();
            failedResponse.setStatus("failed");
            failedResponse.setErrorMessage(e.getMessage());
            applyTryOnFirstFrameResult(projectId, failedResponse);
        }
    }

    private ImageGenerateRequest buildTryOnFirstFrameRequest(
            CommerceModel model,
            CommerceProduct product,
            CommerceProduct shotProduct,
            String aspectRatio,
            String resolution) {
        String requirements = defaultIfBlank(shotProduct.getTryOnRequirements(),
                "生成用于正式试穿视频前审核的首帧确认图，重点保留商品颜色、服装版型、印花纹理、衣长袖长，并保持模特身体结构自然");
        String prompt = "将图1的模特穿上图2的商品服装，生成电商试穿首帧确认图。"
                + "图片比例 " + aspectRatio + "。"
                + "必须准确还原商品颜色、服装版型、图案纹理、衣长袖长，保持模特身体比例自然且姿态稳定。"
                + "试穿首帧确认要求：" + requirements + "。"
                + "不要添加文字、水印、贴纸或额外商品。";
        return ImageGenerateRequest.builder()
                .roleName(model.getName())
                .customPrompt(prompt)
                .aspectRatio(aspectRatio)
                .quality(resolution)
                .referenceImageUrls(buildTryOnFirstFrameReferenceUrls(model, product, parseSceneImageUrls(shotProduct.getFabricFitColorSize())))
                .seriesId(model.getSeriesId())
                .build();
    }

    private void applyTryOnFirstFrameResult(Long projectId, ImageGenerateResponse imageResponse) {
        String status;
        String imageUrl = null;
        String errorMessage = null;
        if (imageResponse != null && "success".equals(imageResponse.getStatus()) && !isBlank(imageResponse.getImageUrl())) {
            status = "completed";
            imageUrl = imageResponse.getImageUrl().trim();
        } else {
            status = "failed";
            errorMessage = imageResponse == null ? "试穿首帧生成失败" : defaultIfBlank(imageResponse.getErrorMessage(), "试穿首帧生成失败");
        }

        UpdateWrapper<CommerceProject> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", projectId)
                .set("try_on_first_frame_url", imageUrl)
                .set("try_on_first_frame_status", status)
                .set("try_on_first_frame_error", errorMessage)
                .set("updated_at", LocalDateTime.now());
        projectMapper.update(null, wrapper);
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

    private int videoWidth(String aspectRatio) {
        return switch (defaultIfBlank(aspectRatio, DEFAULT_ASPECT_RATIO)) {
            case "9:16" -> 720;
            case "1:1" -> 960;
            case "16:9" -> 1280;
            case "3:4" -> 834;
            default -> 834;
        };
    }

    private int videoHeight(String aspectRatio) {
        return switch (defaultIfBlank(aspectRatio, DEFAULT_ASPECT_RATIO)) {
            case "9:16" -> 1280;
            case "1:1" -> 960;
            case "16:9" -> 720;
            case "3:4" -> 1112;
            default -> 1112;
        };
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
            shot.setDurationSeconds(5);
            shot.setGenerationStatus("draft");
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
                scene.setPrompt(buildScenePrompt(scene.getSceneType(), actualName, request.getDescription(), aspectRatio));
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

    private String buildScenePrompt(String sceneType, String sceneName, String description, String aspectRatio) {
        String base = "生成电商服装试穿场景图，场景类型：" + sceneType + "，场景名称：" + sceneName + "。"
                + "图片比例 " + aspectRatio + "。"
                + "画面必须是可用于真人服装试穿合成的干净背景或真实环境，主体区域留出完整人物站立空间，光线自然，透视稳定。"
                + "不要出现文字、水印、Logo、人物、人体、服装商品或杂乱遮挡物。";
        if (!isBlank(description)) {
            base += "补充要求：" + description.trim() + "。";
        }
        return base;
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
        String requirements = defaultIfBlank(product.getTryOnRequirements(), "正面、侧面、转身和细节特写");
        return List.of(
                new String[]{"正面展示", "真人写实模特 {model} 穿上商品图中的 {product}，正面站立展示整体版型，画面必须同时参考模特基础图和商品图。", "先看正面，上身版型很直观。"},
                new String[]{"走动展示", "模特穿着商品自然走动，展示衣服随动作的垂感和轮廓，商品颜色和款式严格参考商品图。", "走动的时候也能看到它的线条感。"},
                new String[]{"侧面展示", "模特侧身展示腰线、肩线和长度，保持真人写实质感。", "侧面看更能判断修身和遮肉效果。"},
                new String[]{"细节特写", "镜头切到商品面料、袖口、下摆或重点设计，展示要求：" + requirements + "。", "细节位置也给大家看清楚。"},
                new String[]{"场景收尾", "模特穿着商品完成一次通勤风定格展示，商品图作为角落参考卡片出现。", "整体很适合日常搭配，实穿度不错。"}
        );
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
