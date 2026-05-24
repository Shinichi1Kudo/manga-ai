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
import com.manga.ai.commerce.service.CommerceProductImagePrivacyService;
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
import java.util.stream.Collectors;

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
    private static final List<String> ALLOWED_SCENE_TYPES = List.of(
            "品牌场景", "直播间背景", "街拍场景", "棚拍场景", "商场试衣间", "室内全身镜",
            "田间自然", "海边旅拍", "海边氛围", "山野公路", "湖畔草地", "花房庭院", "咖啡店外",
            "复古街区", "城市夜景", "天台露台", "艺术馆", "酒店民宿", "地铁通勤",
            "校园林荫", "公园绿荫");
    private static final List<String> ALLOWED_SCENE_RESOLUTIONS = List.of("2K", "3K", "4K");
    private static final List<String> ALLOWED_TRY_ON_VIDEO_RESOLUTIONS = List.of("480p", "720p", "1080p");
    private static final List<String> ALLOWED_TRY_ON_VIDEO_MODELS = List.of("seedance-2.0", "seedance-2.0-fast", "kling-v3-omni");
    private static final List<String> DEFAULT_TRY_ON_DISPLAY_ACTIONS = List.of("全身站立", "转身展示", "走动展示", "细节展示");
    private static final List<String> ALLOWED_TRY_ON_DISPLAY_ACTIONS = List.of(
            "全身站立", "转身展示", "走动展示", "细节展示",
            "半身近景", "坐姿展示", "抬手展示", "整理衣摆",
            "拿包通勤", "戴帽展示", "搭配展示");
    private static final String DEFAULT_TRY_ON_PERFORMANCE_STYLE = "自信大片";
    private static final List<String> ALLOWED_TRY_ON_PERFORMANCE_STYLES = List.of(
            "自信大片", "甜酷活力", "高冷超模", "松弛度假", "明朗亲和");
    private static final List<String> ALLOWED_TRY_ON_PERFORMANCE_BOOSTS = List.of(
            "轻跑靠近镜头", "快走急停回看", "旋身甩发", "主动靠近镜头",
            "回头挑眉", "大胆回看镜头", "自信微笑", "开朗笑起来", "爽朗大笑");
    private static final int MAX_TRY_ON_PERFORMANCE_BOOSTS = 3;
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
    private final CommerceProductImagePrivacyService productImagePrivacyService;
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
    public List<CommerceModelVO> listModelLibrary(Long targetSeriesId) {
        Long userId = requireUserId();
        CommerceSeries targetSeries = requireSeries(targetSeriesId);
        LambdaQueryWrapper<CommerceModel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CommerceModel::getUserId, userId)
                .eq(CommerceModel::getStatus, "locked")
                .orderByDesc(CommerceModel::getCreatedAt);
        return modelMapper.selectList(wrapper).stream()
                .map(model -> toModelLibraryVO(model, targetSeries))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommerceModelVO reuseModelIntoSeries(Long seriesId, Long sourceModelId) {
        Long userId = requireUserId();
        CommerceSeries targetSeries = requireSeries(seriesId);
        CommerceModel source = requireModel(sourceModelId);
        if (!"locked".equals(source.getStatus())) {
            throw new BusinessException("只能沿用已锁定的模特");
        }
        if (seriesId != null && seriesId.equals(source.getSeriesId())) {
            throw new BusinessException("该模特已在当前系列");
        }
        if (isBlank(source.getBaseImageUrl())) {
            throw new BusinessException("模特基础图缺失，无法沿用");
        }

        LocalDateTime now = LocalDateTime.now();
        CommerceModel copied = new CommerceModel();
        copied.setSeriesId(seriesId);
        copied.setUserId(userId);
        copied.setName(source.getName());
        copied.setSourceType("library");
        copied.setGenderAge(source.getGenderAge());
        copied.setBodyProfile(source.getBodyProfile());
        copied.setStyleTags(source.getStyleTags());
        copied.setSpeakingPersona(source.getSpeakingPersona());
        copied.setAppearancePrompt(source.getAppearancePrompt());
        copied.setBaseImageUrl(source.getBaseImageUrl());
        copied.setModelAspectRatio(defaultIfBlank(source.getModelAspectRatio(), DEFAULT_MODEL_ASPECT_RATIO));
        copied.setStatus("locked");
        copied.setErrorMessage(null);
        copied.setIsDeleted(0);
        copied.setCreatedAt(now);
        copied.setUpdatedAt(now);
        modelMapper.insert(copied);

        CommerceModelVO vo = toModelVO(copied);
        vo.setSeriesName(targetSeries.getName());
        vo.setInTargetSeries(true);
        return vo;
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
    @Transactional(rollbackFor = Exception.class)
    public CommerceProductVO updateProductImages(Long productId, CommerceProductCreateRequest request) {
        CommerceProduct product = requireProduct(productId);
        List<CommerceProductCreateRequest.ProductImageItem> productImages = normalizeProductImages(request);
        CommerceProductCreateRequest.ProductImageItem mainImage = productImages.stream()
                .filter(image -> "主图".equals(image.getUsage()))
                .findFirst()
                .orElse(productImages.get(0));

        product.setImageUrl(mainImage.getUrl());
        product.setImagesJson(JSON.toJSONString(productImages));
        product.setUpdatedAt(LocalDateTime.now());
        productMapper.updateById(product);
        return toProductVO(product);
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
        project.setTryOnRequirements("try_on".equals(contentType) && request != null ? trimToNull(request.getTryOnRequirements()) : null);
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
        boolean hasCompletedVideo = false;
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
                    hasCompletedVideo = !isBlank(response.getVideoUrl()) || hasCompletedVideo;
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

        if (!completed && hasCompletedVideo) {
            project.setStatus("partial_completed");
            project.setErrorMessage(buildTryOnPartialFailureMessage(shots, firstError));
        } else if (!completed) {
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

    private String buildTryOnPartialFailureMessage(List<CommerceShot> shots, String fallbackError) {
        if (shots != null) {
            for (CommerceShot shot : shots) {
                if (!"failed".equals(shot.getGenerationStatus())) {
                    continue;
                }
                String title = defaultIfBlank(shot.getTitle(), "部分动作");
                String error = defaultIfBlank(shot.getErrorMessage(), fallbackError);
                return title + "生成失败：" + defaultIfBlank(error, "视频生成失败");
            }
        }
        return defaultIfBlank(fallbackError, "部分动作生成失败");
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

    private CommerceModelVO toModelLibraryVO(CommerceModel model, CommerceSeries targetSeries) {
        CommerceModelVO vo = toModelVO(model);
        CommerceSeries sourceSeries = seriesMapper.selectById(model.getSeriesId());
        vo.setSeriesName(sourceSeries != null ? sourceSeries.getName() : "未知系列");
        vo.setInTargetSeries(targetSeries != null && targetSeries.getId() != null && targetSeries.getId().equals(model.getSeriesId()));
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
                    List<CommerceShot> shots = listProjectShots(project.getId());
                    normalizeTryOnProjectPartialStatus(project, shots);
                    vo = toProjectVO(project);
                    vo.setShots(shots.stream().map(this::toShotVO).toList());
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

    private void normalizeTryOnProjectPartialStatus(CommerceProject project, List<CommerceShot> shots) {
        if (project == null || !"try_on".equals(project.getContentType()) || shots == null || shots.isEmpty()) {
            return;
        }
        boolean hasVideo = shots.stream().anyMatch(shot -> !isBlank(shot.getVideoUrl()));
        boolean hasFailed = shots.stream().anyMatch(shot -> "failed".equals(shot.getGenerationStatus()));
        if (!hasVideo || !hasFailed || "partial_completed".equals(project.getStatus())) {
            return;
        }
        project.setStatus("partial_completed");
        project.setErrorMessage(buildTryOnPartialFailureMessage(shots, project.getErrorMessage()));
        project.setUpdatedAt(LocalDateTime.now());
        projectMapper.updateById(project);
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
        vo.setTryOnRequirements(project.getTryOnRequirements());
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
        List<CommerceProductCreateRequest.ProductImageItem> safeProductReferences =
                productImagePrivacyService.sanitizeProductReferences(productReferences);
        List<String> sceneReferences = parseSceneImageUrls(project.getSceneImageUrlsJson()).stream()
                .limit(1)
                .toList();
        SeedanceRequest request = new SeedanceRequest();
        request.setPrompt(buildVideoPrompt(project, shot, safeProductReferences, sceneReferences));
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
        safeProductReferences.forEach(reference -> contents.add(imageReference(reference.getUrl())));
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
                + "\n声音要求：只使用适合电商试穿氛围的纯音乐BGM，节奏轻松、高级、干净，不要口播，不要人声讲解，不要旁白，不要字幕念白。";
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
                + tryOnCinematicStylePrompt()
                + "展示方式规范：全身站立：完整展示头到脚的穿着效果，但不要做成呆板证件照；用中远景或低角度中远景建立人物气场，镜头先拉远建立场景空间，再从脚部向上扫描到脸部；模特通过单腿承重、跨步前点、肩线打开、髋部轻微转向、下巴微抬和回看镜头形成时尚大片站姿，衣长、裤长、整体比例和廓形必须清楚；"
                + "转身展示：人物不是原地机械旋转，而是先有明显重心转移，肩线和髋部带动身体，以四分之三角度进入，再慢动作转到侧面和背面；镜头推近后再轻微拉远，镜头轻微环绕人物半圈，侧逆光扫过肩线、腰线、下摆和发丝边缘，眼神从镜外导视点回到镜头后再转身离开，展示正面到侧面再到背面的连续穿着状态，避免快速旋转和身体扭曲；"
                + "走动展示：人物自然向前或轻微斜向走动，但步伐要像时装大片或街拍抓拍，步幅比普通试穿更有气场；可以从快走自然过渡到轻跑两三步，再稳稳停住回看镜头，动作大方有生命力；镜头先从脚部/下摆开始建立节奏，再侧跟拍到中景，最后轻微推近脸部和上半身；通过步幅、重心、发丝飞舞、裙摆摆动、衣摆回弹、短暂停顿和眼神接触展示面料垂坠、摆动幅度和衣服随身体运动的真实状态；"
                + "细节展示：不要做廉价局部扫货特写，镜头从中景自然推近到特写，再轻微拉回到半身关系，靠近领口、袖口、纽扣、腰线、下摆、图案或拼接位置，使用浅景深、斜向光和材质反光；模特手指可以牵引、按压或轻扫布料，但动作要有停顿和呼吸，局部清晰且不能裁掉关键穿着关系；"
                + "半身近景：使用中近景或肩胸中景，从头肩到腰胯展示上半身版型、领口、肩袖、胸前图案和面料质感；镜头缓慢推近，人物先看向镜外导视点，再回看镜头并停留半秒，随后微微侧头或整理发丝，脸部与服装都要自然；"
                + "坐姿展示：人物不是僵硬坐下，而是先从站姿侧身入座或半坐，身体有S型松弛曲线，一只脚轻点地面或自然前伸，手臂换位到膝侧或椅背；镜头从低角度中景推近到腰胯与下摆，展示坐姿下衣服褶皱、下摆、腰腹贴合和舒适度，身体比例不能变形；"
                + "抬手展示：抬手幅度要清楚，不是小幅晃手；用中景抓拍抬手整理袖口、拨开发丝、触碰帽沿或调整肩线的瞬间，通过手臂抬起、肩部牵拉、袖口回落和发丝边缘光展示袖长、袖口、腋下余量、肩部活动空间和衣服牵拉状态；"
                + "整理衣摆：人物自然整理衣摆、腰头或下摆，不是功能演示；镜头从半身或腰部中景推近，人物先轻微跨步或转胯，再用手指带动布料，捕捉衣摆回落、下摆线恢复、开衩和裤裙衔接，展示衣长、腰线和垂坠恢复状态，动作要有真实幅度和停顿；"
                + "拿包通勤：通勤包或极简手袋必须有明确入画来源，可以由模特从画面边缘、桌面、椅背或镜头外自然拿起，也可以由镜头外助手的手短暂递入给模特；包从镜头外递入或拿取后，再用极具张力的台步、单手拎包、肩线打开、下巴微抬、镜头前急停、回看镜头或侧身转体形成通勤大片，展示商品在通勤场景中的整体穿搭比例，包必须低调，不要遮挡商品主体，不能从人物手中凭空出现；"
                + "戴帽展示：仅当商品本身有帽子、兜帽或连帽结构时，人物自然把帽子戴上再轻微调整帽沿或帽绳；动作要有慢动作、明显手部牵引、发丝边缘光、领口肩颈关系和一次回看镜头，展示帽型、领口和肩颈关系；如果商品没有帽子结构，不能凭空新增帽子或兜帽；"
                + "搭配展示：搭配单品必须从镜头外自然拿取或由镜头外助手递入，先有明确入画动作再进入展示，不能突然出现在人物手中或身上；用时尚杂志封面质感、大面积留白、中远景站位、慢动作走位、单手叉腰、侧身转体或低角度仰拍呈现完整造型氛围，但商品本身必须是唯一视觉重点，不要新增抢眼饰品或改变商品。"
                + "道具入画规则：凡是包、帽子、外搭、围巾、配饰、鞋盒或其他搭配物件，都必须通过镜头外拿取、画面边缘拿起、桌椅挂架取下或镜头外助手递入来出现；不能凭空生成、不能瞬间跳到手里、不能突然穿戴到身上。"
                + "动作镜头必须连贯：如果用户选择多个展示方式，必须在同一个场景、同一位模特、同一套服装中，从上一个动作自然过渡到下一个动作，"
                + "可以用轻微跟拍、缓慢推近、自然转身、整理衣服或步伐衔接完成过渡；不要出现硬切跳变、瞬间换姿势或镜头断层。"
                + "动作幅度比普通试穿更明显：允许跨步、转胯、肩线打开、回身、短暂停顿、低头整理、抬手、侧身转体和带风感的走位，但必须是时装大片表演感，不要尬舞、不要夸张喜剧表演。"
                + "表演气质要自信、大方、个性张扬，模特像在掌控镜头和现场，不要扭扭捏捏、害羞拘谨、小心翼翼。"
                + "可以出现轻跑、快走、旋身、甩发、开朗笑容、爽朗大笑、主动靠近镜头、回头挑眉和大胆回看镜头；动作要外放但仍然高级，不能变成尬舞或夸张搞笑。"
                + "笑容要自然明亮，可以有自信微笑、开朗笑起来或爽朗大笑，但不要假笑、僵硬笑或过度表演。"
                + "眼神互动节奏：不要从头到尾盯着远方，也不要一直死盯镜头；视线需要在镜头、镜外导视点和服装细节之间自然转移，至少出现一次镜外导视点 -> 回看镜头 -> 短暂停留 -> 再移开的节奏，配合轻微眨眼、自然呼吸、细小头部调整和微表情变化。"
                + "镜头调度必须有变化：根据动作使用推近、拉远、低角度仰拍、侧跟拍、轻微环绕、脚步到上半身扫描、半身到特写再回到中景等组合；每个动作至少一次景别变化或视角变化，镜头不能像固定监控机位。"
                + "人物眼神必须自然有焦点，不要空洞呆滞；眼神从镜外导视点回到镜头时要有短暂停顿和真实反应，不能全程游离、瞳孔固定或蜡像感。"
                + "脸部表情保持松弛亲和，有真实真人的细微反应，不要僵硬假笑、面无表情、瞳孔固定或蜡像感。"
                + "声音要求：如果生成音频，只使用合适的纯音乐BGM，氛围高级、轻快、干净，音量自然；不要口播，不要人声讲解，不要旁白，不要台词，不要歌词。"
                + "画面比例为 " + defaultIfBlank(project.getAspectRatio(), DEFAULT_ASPECT_RATIO) + "，视频清晰、稳定、商业摄影质感。"
                + "镜头运动要自然但不能死板，主体始终清晰，服装始终是视觉重点。"
                + "禁止内容：不要改变商品主色和核心图案。不要新增无关服装、饰品、文字、水印、价格牌或宣传字。"
                + "不要把商品图作为角落卡片、悬浮图、贴纸或画中画显示出来。"
                + "不要出现身体变形、脸部漂移、手脚异常、衣服穿帮、图案闪烁、布料结构跳变。"
                + "不要让人物和背景分离，不要出现明显后期合成感。";
    }

    private String tryOnCinematicStylePrompt() {
        return "导演式画面目标：这不是普通平铺的商品展示，而是一段有时装短片氛围的电商试穿大片；"
                + "在不牺牲商品准确性的前提下，参考场景图的空间气质、光线方向、色温和环境材质，把模特、服装和场景统一成真实摄影现场。"
                + "场景参考优先级高于通用风格词：必须先识别并遵守用户选中场景图里的地点、时间、人群氛围、地面材质、主要背景元素和光线方向，"
                + "不得因为通用风格词把海边改成城市夜景、棚拍、室内或其他不在场景图里的空间。"
                + "拒绝廉价摆拍，禁止站桩式动作、网红尬舞、卖家秀式比划、原地僵硬转圈和无目的抬手；画面要有情绪叙事和现场感，像自然抓拍感的高级商业摄影，人物不是机械摆拍，动作和镜头都带有明确的身体力学和生活流动感。"
                + "人物性格气质要更外放：自信、大方、明亮、个性张扬，有主动掌控镜头的超模感和生活感染力；不要害羞、拘谨、扭捏、低头躲镜头或小幅度试探。"
                + "根据场景图自动选择动作气质，且只使用与场景图一致的电影语言：场景是城市夜景或街拍时，才使用王家卫式都市情绪大片，强调霓虹反光、侧逆光、焦外光斑、慢动作跟拍、短暂停顿、眼神接触和故事张力；"
                + "场景是棚拍或极简空间时，才使用欧美 Vogue 杂志高冷秀场风，强调时尚杂志封面质感、大面积留白、硬光闪光灯直打、冷艳步伐、单手叉腰、下巴微抬和极强气场；"
                + "场景是咖啡店、花房、酒店民宿时，才使用法式电影慵懒浪漫风，强调暖色柔光、倚靠、整理发丝、S型身体曲线、不经意抬头和偷拍感；"
                + "场景是海边、田间、山野、公园时，才使用自然旅拍电影风，强调风吹发丝、衣摆/裙摆摆动、低角度仰拍、逆光轮廓、丁达尔效应、空气透视和自由松弛感。"
                + "动作高级化规则：每个动作都必须有明确的景别，如特写、中景、低角度仰拍、镜头推进、镜头拉远、侧跟拍、镜头从脚部向上扫描或轻微环绕；不要只写全身照、站着、走两步，不要只是站着、转身、走两步。"
                + "物理参数规则：每个动作都必须有身体物理参数，如重心转移、单腿承重、肩线打开、下巴微抬、慢动作、发丝飞舞、裙摆摆动、衣摆回弹、轻跑两三步后的稳定急停、甩发、旋身、手指牵引布料、眼神接触、短暂停顿和微表情变化。"
                + "光影定调规则：轮廓光、丁达尔效应、霓虹反光、闪光灯直打、电影级布光、侧逆光、柔光滤镜、路灯斑驳投影、玻璃反射和地面反光必须服务于服装主体；光影要高级，但不能污染商品颜色。"
                + "摄影质感：电影感光影、浅景深、胶片颗粒、柔焦边缘、光影弥散、材质反光、局部折射光、高光边缘、发丝边缘光、柔和阴影、空气感和空间纵深。"
                + "色彩氛围：根据场景自动选择高级低饱和、莫兰迪灰调、清冷暗调、暖色室内光或自然日光，不要全片单调灰、不要过度艳丽、不要强滤镜污染商品颜色。"
                + "构图语言：可以有前景遮挡、背景虚化、环境反射、斜向透视、慢推近、轻微跟拍和留白，但主体服装必须始终清楚，关键轮廓、版型、图案和材质不能被虚化遮住。"
                + "氛围控制：允许背景有梦幻光影、光斑、虚化层次和艺术氛围，但不要把背景虚化成无法识别的糊片，不要让花、灯光、路人、陈设或道具抢走服装主体。"
                + "质感底线：高清自然，真实真人，真实布料，真实空间透视；不要塑料皮肤、假人感、过度磨皮、过曝、死黑、低清噪点、抽象到看不清商品。";
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
            description.append("图").append(imageIndex).append("是用户选中的试穿场景图，用于确定空间环境、光线、背景风格和画面氛围。")
                    .append("图").append(imageIndex).append("是唯一场景背景基准，必须使用这张图的地点、地面、空间透视、主要背景元素、时间氛围、光线方向和色温，")
                    .append("不得替换成其他地点、其他天气、其他时间段或棚拍背景。")
                    .append("人物必须真实站在图").append(imageIndex).append("的地面、透视、光线和环境层次中，")
                    .append("服装、肤色、阴影、轮廓光和运动模糊都要匹配图").append(imageIndex).append("的现场光。")
                    .append("如果图").append(imageIndex).append("是海边，就必须保留海岸、沙滩或海面等海边空间特征；")
                    .append("如果图").append(imageIndex).append("是室内，就必须保持室内墙面、地面、灯光和陈设逻辑；")
                    .append("如果图").append(imageIndex).append("是街景，就必须保持街道、建筑、路面和城市环境关系。")
                    .append("通用电影感词只用于强化图").append(imageIndex).append("已有的光影和氛围，不能创造与图").append(imageIndex).append("不一致的新场景。");
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
                scene.setPrompt(buildScenePrompt(
                        scene.getSceneType(),
                        actualName,
                        request.getDescription(),
                        aspectRatio,
                        request.getBrandSceneName(),
                        request.getTimeMood(),
                        request.getCrowdMode()));
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
            case "田间自然", "湖畔草地", "花房庭院", "公园绿荫" -> "自然氛围场景库";
            case "海边旅拍", "山野公路", "天台露台" -> "旅拍场景库";
            case "海边氛围" -> "海边氛围场景库";
            case "咖啡店外", "复古街区", "城市夜景", "地铁通勤", "校园林荫" -> "街拍场景库";
            case "艺术馆", "酒店民宿" -> "生活方式场景库";
            default -> "自定义场景库";
        };
    }

    private String buildScenePrompt(
            String sceneType,
            String sceneName,
            String description,
            String aspectRatio,
            String brandSceneName,
            String timeMood,
            String crowdMode) {
        boolean brandScene = "品牌场景".equals(sceneType);
        String base = "生成一张电商服装试穿场景背景图，用于后续虚拟试穿合成。"
                + "场景类型：" + sceneType + "，场景名称：" + sceneName + "，图片比例：" + aspectRatio + "。"
                + scenePromptDetail(sceneType, sceneName, brandSceneName, timeMood, crowdMode)
                + sceneCinematicMoodPrompt(sceneType, sceneName, timeMood)
                + sceneCrowdPrompt(sceneType, sceneName, crowdMode)
                + sceneCinematicCameraPrompt(sceneType, sceneName, timeMood)
                + "构图要求：竖向和横向画面都要保留完整真人模特站立空间，人物预留区位于画面中轴或黄金分割点附近，从头顶到脚底都有充足留白，地面/墙面/背景透视稳定，避免强遮挡物穿过身体区域。"
                + sceneSubjectRestriction(crowdMode)
                + "画面质感：照片级真实感，电影感，商业大片氛围，情绪化光影，选择性浅景深和景深效果，背景有层次但不抢主体，细节清晰，适合女装/男装/童装试穿短视频首帧和展示图。";
        if (brandScene) {
            String brandName = defaultIfBlank(brandSceneName, "");
            if (isBlank(brandName)) {
                base += "品牌元素要求：需要出现简洁、清晰、虚构的服装品牌视觉标识，可表现为门头招牌、Logo墙、导视牌或背景广告牌中的一个核心视觉点。"
                        + "品牌元素要自然融入空间，位置醒目但不要遮挡后续真人模特站立区域，作为场景识别的一部分。"
                        + brandSceneRestriction(crowdMode);
            } else {
                base += "品牌元素要求：必须出现用户填写的品牌字样「" + brandName + "」，可表现为门头招牌、Logo墙、导视牌或背景广告牌中的一个核心视觉点。"
                        + "品牌字样必须清晰可读，不要翻译、不要改写、不要改变大小写；如果生成Logo，请围绕「" + brandName + "」做简洁服装品牌标识，不要生成其他品牌名。"
                        + "品牌元素要自然融入空间，位置醒目但不要遮挡后续真人模特站立区域，作为场景识别的一部分。"
                        + brandSceneRestriction(crowdMode);
            }
        } else {
            base += nonBrandSceneRestriction(crowdMode);
        }
        if (!isBlank(description)) {
            base += "补充要求：" + description.trim() + "。";
        }
        return base;
    }

    private String sceneSubjectRestriction(String crowdMode) {
        if ("ambient".equals(defaultIfBlank(crowdMode, "empty"))) {
            return "严格主体要求：这里生成的是试穿合成用背景图，不要生成正在试穿的人、服装商品、手脚特写、清晰主体人物或占据主体落位区的人物；允许远景或画面边缘出现少量模糊低存在感的氛围人群，所有氛围人群必须远离主体落位区且不能有清晰脸部。所有电影感叙事主要通过环境、光影、风、雾、前景和空间表达。";
        }
        return "严格空场要求：这里生成的是试穿合成用背景图，不要生成真人、远行者、女性、模特或人物剪影，不要出现正在试穿的人、服装商品、手脚特写或主体人物；所有电影感叙事只能通过环境、光影、风、雾、前景和空间表达。";
    }

    private String sceneCinematicCameraPrompt(String sceneType, String sceneName, String timeMood) {
        if (isBeachScene(sceneType, sceneName)) {
            boolean nightMood = "night".equals(defaultIfBlank(timeMood, "day"));
            return "摄影语言：电影级摄影，大师级海岛度假大片摄影，史诗感，广角或超宽画幅视角，低角度或轻微仰拍，非对称构图，强透视，黄金分割主体预留位，选择性浅景深和景深效果；"
                    + "前景强虚化只使用干净奶粉沙滩边缘、浅海水线、细小浪花、水面高光、透明海水反光或远处海平线层次，形成清透蓝绿色果冻海的空间包裹感；不要出现杂草、芦苇、乱草、海草前景，不要让礁石、木栈道或植物遮挡主体落位区。"
                    + "海岸线透视要求：海平线必须保持水平，沙滩必须连续，海水只能位于海岸线的一侧，水线从画面左下到右上或右下到左上的单向斜线自然延伸，近处白沙、中景浅海、远景深蓝海和天空要分层清楚；不要生成白沙舌、沙洲孤岛、环形浅滩、双重水线、两侧夹水、海水倒灌、断裂沙滩、漂浮沙面或不符合透视的弯曲海岸。"
                    + (nightMood
                    ? "光影要求：根据用户选择的夜晚氛围生成海岛蓝调光影，强调低照度海岸、侧逆光、轮廓光、月光海面反射、白沙柔和反光、远处暖色度假灯光、柔光镜效果、高动态范围、电影级色彩分级、胶片模拟质感、独特颗粒感、诗意现实主义和夜晚度假商业大片氛围；画面要有自由、松弛、通透、治愈和高级旅拍质感。"
                    : "光影要求：根据用户选择的白天氛围生成海岛自然光，强调高透日光、侧逆光、轮廓光、海面反射、白沙反光、云层透光、柔光镜效果、高动态范围、电影级色彩分级、胶片模拟质感、独特颗粒感、诗意现实主义和白天度假商业大片氛围；画面要有自由、松弛、通透、治愈和高级旅拍质感。");
        }
        if (isOutdoorScene(sceneType, sceneName)) {
            return "摄影语言：电影级摄影，大师级电影摄影，史诗感，广角或超宽画幅视角，低角度或轻微仰拍，非对称构图，强透视，黄金分割主体预留位，选择性浅景深和景深效果，前景强虚化和虚焦慢门质感，可用草叶、芦苇、花枝、岩石、栏杆、水汽或街景边缘做失焦前景，形成光斑、层层遮挡和空间包裹感；背景保留中远景纵深、云雾/薄雾/空气透视、柔和自然光晕、局部高光边缘、材质反光和画面张力。"
                    + "光影要求：根据用户选择的时间氛围生成对应光影，强调情绪化光影、逆光、侧逆光、轮廓光、丁达尔光束、云层透光、地面或水面反射、柔光镜效果、高动态范围、电影级色彩分级、胶片模拟质感、独特颗粒感、诗意现实主义和情绪叙事；画面要有自由、松弛、野性、治愈与商业大片氛围。";
        }
        return "摄影语言：电影级摄影，大师级商业大片空间摄影，广角或中广角镜头，低角度或平视略仰拍，非对称构图，强透视，黄金分割主体预留位，选择性浅景深和景深效果，前景强虚化和柔焦边缘，可用门框、玻璃、陈设边缘、植物、镜面边缘、帘布或空间结构做失焦前景；背景保留中远景纵深、材质层次、柔和光晕、高光边缘、空间透视和高级留白。"
                + "光影要求：根据用户选择的时间氛围生成对应光影，强调情绪化光影、柔和主光、轮廓光、局部反射、墙面漫反射、材质高光、玻璃折射、柔光镜效果、高动态范围、电影级色彩分级、胶片模拟质感、独特颗粒感、克制但有情绪的商业大片氛围。";
    }

    private String sceneCinematicMoodPrompt(String sceneType, String sceneName, String timeMood) {
        boolean outdoor = isOutdoorScene(sceneType, sceneName);
        if ("night".equals(defaultIfBlank(timeMood, "day"))) {
            if (isUrbanOutdoorScene(sceneType, sceneName)) {
                return "时间氛围：夜晚氛围，电影感城市夜景，低照度环境但主体预留区保留柔和补光；湿润沥青或光洁地面反射城市灯光，虚化交通信号灯、路灯、橱窗灯和远处建筑灯光，青橙色调，浅景深，柔光镜光晕，局部高光扩散，胶片颗粒，情绪化光影和都市大片质感。";
            }
            if (isBeachScene(sceneType, sceneName)) {
                return "时间氛围：自然旅拍夜晚氛围，海岛蓝调时刻或月光海岸氛围，海面保持清澈蓝绿色层次，白沙有柔和反光，远处少量暖色度假灯光、月光感轮廓光、水面柔和反射、薄雾和空气透视；画面安静、通透、浪漫，有柔光镜光晕、胶片颗粒和高级海岛旅拍大片质感。";
            }
            if (outdoor) {
                return "时间氛围：自然旅拍夜晚氛围，蓝调时刻或入夜后的低照度环境，远处少量暖色点光源、月光感轮廓光、雾气和空气透视、地面或水面柔和反射、草木/海岸/山野边缘层次；画面安静、辽阔、梦幻，有柔光镜光晕、胶片颗粒和诗意现实主义的旅拍大片质感。";
            }
            return "时间氛围：室内夜晚氛围，低照度室内环境但主体预留区有柔和商业补光；室内灯带、背景氛围光、柔和主光、轮廓光、局部暖色点光源、橱窗暖光、墙面或地面柔和反射、浅景深和空间纵深；整体高级、安静、有情绪，带柔光镜光晕、材质高光和胶片颗粒。";
        }
        if (isUrbanOutdoorScene(sceneType, sceneName)) {
            return "时间氛围：白天氛围，明亮自然光、金色时刻或高级柔和日光，根据场景形成侧逆光、轮廓光、建筑玻璃反射、树影层次和干净通透的空气感；真实环境色彩，柔和阴影，高动态范围，胶片模拟质感，高级商业街拍或空间摄影质感，电影感但不过度戏剧化。";
        }
        if (isBeachScene(sceneType, sceneName)) {
            return "时间氛围：白天自然旅拍氛围，仙本那式高透日光、清澈蓝绿色果冻海、蓝绿渐变浅海、奶粉沙滩反光和干净空气感；可有轻薄白云、海面波光、白沙柔和阴影和远处海平线层次，整体明亮通透、高动态范围、胶片模拟质感、高级海岛度假大片质感。";
        }
        if (outdoor) {
            return "时间氛围：白天自然旅拍氛围，柔和自然日光、黄金时刻、金色逆光或阴天漫射光；可有穿透云层和草叶的丁达尔光束、薄雾、空气透视、草木/海面/山野/湖面真实环境色彩和细节层次，柔和阴影，高动态范围，胶片模拟质感，高级生活方式大片质感。";
        }
        return "时间氛围：室内白天氛围，使用自然窗光、柔和主光、补光、墙面漫反射、地面轻微反光和浅景深；画面通透、干净、高级，有柔光镜光晕、材质高光、空气感和生活方式大片质感，画面元素仅限室内墙面、地面、镜面、陈设和材质细节。";
    }

    private boolean isBeachScene(String sceneType, String sceneName) {
        String type = defaultIfBlank(sceneType, "");
        String name = defaultIfBlank(sceneName, "");
        String combined = type + " " + name;
        return "海边旅拍".equals(type)
                || "海边氛围".equals(type)
                || combined.contains("海边")
                || combined.contains("海岛")
                || combined.contains("沙滩")
                || combined.contains("海滩");
    }

    private boolean isUrbanOutdoorScene(String sceneType, String sceneName) {
        String type = defaultIfBlank(sceneType, "");
        String name = defaultIfBlank(sceneName, "");
        if (List.of("街拍场景", "咖啡店外", "复古街区", "城市夜景", "天台露台", "地铁通勤", "校园林荫")
                .contains(type)) {
            return true;
        }
        String combined = type + " " + name;
        return combined.contains("街")
                || combined.contains("路口")
                || combined.contains("城市")
                || combined.contains("商圈")
                || combined.contains("地铁")
                || combined.contains("天台")
                || combined.contains("露台")
                || combined.contains("校园");
    }

    private boolean isOutdoorScene(String sceneType, String sceneName) {
        String type = defaultIfBlank(sceneType, "");
        String name = defaultIfBlank(sceneName, "");
        if (List.of("街拍场景", "田间自然", "海边旅拍", "海边氛围", "山野公路", "湖畔草地", "花房庭院",
                "咖啡店外", "复古街区", "城市夜景", "天台露台", "地铁通勤", "校园林荫", "公园绿荫")
                .contains(type)) {
            return true;
        }
        String combined = type + " " + name;
        return combined.contains("街")
                || combined.contains("路")
                || combined.contains("户外")
                || combined.contains("城市")
                || combined.contains("广场")
                || combined.contains("公园")
                || combined.contains("天台")
                || combined.contains("海边")
                || combined.contains("地铁口");
    }

    private String sceneCrowdPrompt(String sceneType, String sceneName, String crowdMode) {
        boolean outdoor = isOutdoorScene(sceneType, sceneName);
        if ("ambient".equals(defaultIfBlank(crowdMode, "empty"))) {
            if (outdoor) {
                return "人群氛围：远处背景必须有少量路人作为虚化氛围，只能出现在画面边缘或远景，必须模糊、低存在感、不能占据主体落位区，不能有清晰脸部、手脚特写或完整人物主体。";
            }
            return "人群氛围：室内空间边缘或远景必须有少量顾客/工作人员轮廓作为虚化氛围，必须模糊、低存在感、不能占据主体落位区，不能有清晰脸部、手脚特写或完整人物主体。";
        }
        if (outdoor) {
            return "人群氛围：无人，空场景，空无一人的室外环境，画面中不要出现人物、人体、手脚、清晰人脸或人群。";
        }
        return "人群氛围：无人，空场景，空无一人的室内空间，画面中不要出现人物、人体、手脚、清晰人脸或人群。";
    }

    private String brandSceneRestriction(String crowdMode) {
        String peopleRestriction = "ambient".equals(defaultIfBlank(crowdMode, "empty"))
                ? "主体落位区不要出现人物、人体、模特、手脚、清晰人脸、服装商品、衣架、假人模特、价格牌、二维码、水印、无关海报、杂乱遮挡物；远景氛围人群必须模糊低存在感；"
                : "不要出现人物、人体、模特、手脚、服装商品、衣架、假人模特、价格牌、二维码、水印、无关海报、杂乱遮挡物；";
        return "严格限制：" + peopleRestriction + "除品牌相关文字与Logo外，不要出现其他无关文字、水印、二维码、价格牌。";
    }

    private String nonBrandSceneRestriction(String crowdMode) {
        String peopleRestriction = "ambient".equals(defaultIfBlank(crowdMode, "empty"))
                ? "主体落位区不要出现人物、人体、模特、手脚、清晰人脸、服装商品、衣架、假人模特、品牌字样、价格牌、二维码、文字、水印、Logo、海报、杂乱遮挡物；远景氛围人群必须模糊低存在感，不能成为主体；画面必须无文字、无水印、无Logo。"
                : "不要出现人物、人体、模特、手脚、服装商品、衣架、假人模特、品牌字样、价格牌、二维码、文字、水印、Logo、海报、杂乱遮挡物，画面必须无文字、无水印、无Logo。";
        return "严格限制：" + peopleRestriction;
    }

    private String scenePromptDetail(
            String sceneType,
            String sceneName,
            String brandSceneName,
            String timeMood,
            String crowdMode) {
        String brandName = defaultIfBlank(brandSceneName, "该品牌");
        boolean nightMood = "night".equals(defaultIfBlank(timeMood, "day"));
        boolean ambientCrowd = "ambient".equals(defaultIfBlank(crowdMode, "empty"));
        return switch (sceneType) {
            case "品牌场景" -> "具体场景：围绕「" + brandName + "」设计现代服装品牌视觉空间，可为品牌门头、形象展厅、Logo墙、导视牌或背景广告牌。"
                    + "品牌名称「" + brandName + "」需要作为清晰可读的空间标识出现在画面里，形成可识别的品牌场景；整体是高端品牌大片空间，使用精致石材、金属、玻璃、抽象陈列台、柔和背景墙和高级地面材质形成纵深，光线在材质边缘产生细腻反射和轮廓层次，但不能出现商品服装。";
            case "直播间背景" -> "具体场景：高端电商直播间或品牌直播间背景，背景墙、陈列结构、柔和灯带、简洁陈设、材质层次和纵深空间形成销售场域感。画面像高级直播间空镜，干净但不空洞，背景有空间层次、柔和反光和商业布光氛围，但不能出现主播、屏幕文字、商品堆叠和明显品牌标识。";
            case "街拍场景" -> nightMood
                    ? "具体场景：夜晚城市街拍环境，可为商圈步道、城市路口、橱窗街角或干净街边人行道；重点表现湿润的沥青路面或光洁地面上的城市灯光反射、路灯、橱窗暖光、远处建筑灯光和虚化交通信号灯，形成高端夜景街拍氛围；主体站立区必须干净开阔，不要让车辆、路牌、灯箱或街边设施压到人物预留区域。"
                    : "具体场景：日光街拍环境，可为通勤街景、咖啡店外、商圈步道、干净街角或城市人行道；重点表现明亮自然光、干净建筑立面、玻璃橱窗、树影、街面纵深和真实城市日常质感，形成高级白天穿搭街拍氛围；主体站立区必须干净开阔，不要让车辆、路牌、桌椅或街边设施压到人物预留区域。";
            case "棚拍场景" -> "具体场景：专业服装棚拍背景，纯色或轻纹理背景纸、干净地面、背景过渡和稳定影棚空间结构。不要只是平铺背景，要有高级棚拍大片的柔和渐变、地面微反射、轮廓补光和克制阴影，适合突出服装版型和颜色。";
            case "商场试衣间" -> "具体场景：高端商场试衣间或精品店试衣区，干净镜面、精致墙面、地面材质、门帘或换衣区结构，空间真实、有高级商业空间氛围。使用镜面边缘、帘布、墙面材质和柔和灯光形成层次，像精品店试衣区空镜大片，但不要让镜子里出现人物或服装商品。";
            case "室内全身镜" -> "具体场景：室内全身镜试穿环境，精品买手店或高级公寓氛围，全身镜、干净地面、背景墙和局部陈设形成纵深和生活方式大片感。镜面边缘、窗光、地面反光和软装层次要有高级室内摄影质感，镜面不能反射人物、手脚、拍摄者或商品。";
            case "田间自然" -> "具体场景：适合服装旅拍的田间自然场景，开阔麦田、稻田、草垛或乡间小路，远处有层叠田埂、低矮丘陵和柔和天际线，地面平整留出模特站立区。"
                    + "前景茂密麦穗、草叶和谷穗或芦苇要有强烈失焦虚化和风吹草浪，像电影空镜里被风掠过的草甸；远景田埂、丘陵或薄雾森林融入背景，形成辽阔、治愈、自由、松弛和诗意现实主义的生活方式大片氛围。"
                    + "主体落位区保持干净土路、草地或田埂平台，适合棉麻、连衣裙、外套、亲子装等服装试穿；不要生成农具、杂乱电线、泥泞脏乱地面或抢眼动物。";
            case "海边旅拍" -> "具体场景：高级海边旅拍场景，仙本那式果冻海度假海岸，蓝绿色透明浅海、奶粉沙滩、干净可行走的弧形海岸线、远处水上屋、白色小码头、低存在感白色游艇、水平海平线、"
                    + (nightMood ? "海岛蓝调天空、月光海面、远处暖色度假灯光和细小浪花构成夜晚真实度假旅拍动线。"
                    : "轻薄白云、细小浪花和高透天空层次构成真实度假旅拍动线。")
                    + "画面重点是可站可走的海岛旅拍空间：主体落位区像真实海岛旅拍站位，位于连续白沙滩或浅水边缘的干净开阔区域，前方保留完整脚下空间，后方有果冻海、海平线和远处度假元素形成纵深，后续模特和服装可以自然落位，适合长裙、外套、防晒服、休闲套装等服装试穿。"
                    + "前景强虚化使用低角度白沙边缘、单向浅海水线、浪花水汽和水面高光，形成浪漫电影手法、海风流动感、自由与松弛感、"
                    + (nightMood ? "蓝调月光下的度假大片感；" : "明亮治愈的度假大片感；")
                    + "海岸线透视要求：海平线必须保持水平，沙滩必须连续，海水只能位于海岸线的一侧，水线从画面左下到右上或右下到左上的单向斜线自然延伸，近处白沙、中景浅海、远景深蓝海和天空要分层清楚；不要生成白沙舌、沙洲孤岛、环形浅滩、双重水线、两侧夹水、海水倒灌、断裂沙滩、漂浮沙面或不符合透视的弯曲海岸。"
                    + (ambientCrowd ? "允许远处背景或画面边缘出现少量虚化游客轮廓、夜晚海边散步人影或度假区低存在感人群作为氛围；"
                    : "可加入远处白色游艇、白色小码头、水上屋或低存在感度假建筑作为氛围；")
                    + "但不要出现杂草、芦苇、乱草、海草前景，不要出现脏乱礁石堆、灰暗海水、泥沙滩、泳装人群特写、强烈逆光黑脸或湿乱贴身效果。";
            case "海边氛围" -> "具体场景：海边氛围大片背景，仙本那果冻海、蓝绿色透明浅海、奶粉沙滩、干净开阔海岸线、远处水平海平线和"
                    + (nightMood ? "海岛蓝调夜空构建高级海岛夜晚氛围大片。"
                    : "轻薄云层构建纯净海岛氛围空镜。")
                    + "画面重点是极简海天分层和大片感"
                    + (nightMood ? "夜景留白" : "空镜")
                    + "，不放明显码头、水上屋、游艇或度假建筑；近景是干净细腻的奶粉沙滩留白，中景是薄薄一条自然浅海水线，远景是蓝绿渐变海面和水平海平线，"
                    + (nightMood ? "天空是蓝调夜色、月光云层和远处极少量暖色点光源，整体更像高级海边广告片夜景留白而不是旅游打卡照。"
                    : "天空清透宽阔，整体更像高级海边广告片留白而不是旅游打卡照。")
                    + "主体落位区像高级海边广告片留白，必须是可站立、干净开阔、连续完整的奶粉沙滩或浅海边缘，后续模特和服装可以自然落位；前景强虚化只使用白沙颗粒、单向浅海水线、细小浪花、水面反光和柔和水汽，形成浪漫电影手法、慢门感、虚焦感、清透蓝绿渐变和度假广告片质感。"
                    + "海岸线透视要求：海平线必须保持水平，沙滩必须连续，海水只能位于海岸线的一侧，水线从画面左下到右上或右下到左上的单向斜线自然延伸，近处白沙、中景浅海、远景深蓝海和天空要分层清楚；不要生成白沙舌、沙洲孤岛、环形浅滩、双重水线、两侧夹水、海水倒灌、断裂沙滩、漂浮沙面或不符合透视的弯曲海岸。"
                    + "画面强调自由与松弛感、治愈与慰藉氛围、海岛色彩和极致光影；"
                    + (ambientCrowd ? "允许远景或画面边缘出现少量模糊低存在感的海边散步人影作为虚化氛围，但不能成为主体；"
                    : "")
                    + "不要出现杂草、芦苇、乱草、海草前景，不要出现灰黑海水、泥沙滩、杂乱礁石、情侣游客主体、远行者、清晰人物背影、船只文字、码头、水上屋、游艇、度假建筑或商业招牌。";
            case "山野公路" -> "具体场景：山野公路旅拍场景，干净蜿蜒道路、山脊线、远处树林或旷野，画面有强透视和公路片纵深，路面平整且主体站立区安全开阔。"
                    + "道路作为强引导线通向远山或雾气森林，低角度视角带出辽阔空间，路面有细腻材质、风吹尘雾或微弱反射，整体有公路电影、探索感、孤独感和诗意现实主义气质，适合风衣、外套、工装、休闲运动风试穿。"
                    + "背景车辆只能极远景虚化或不出现，不要让车流、护栏、路牌或树枝穿过人物预留区域。";
            case "湖畔草地" -> "具体场景：湖畔草地生活方式大片场景，平整草地、湖面、远处树线或木栈道，整体清爽安静，有水岸空间和自然环境层次。"
                    + "湖面反射、草叶前景、远处树线和空气透视要形成柔和纵深，画面像松弛自然的生活方式大片，适合春夏服装、针织、裙装、亲子装和轻户外穿搭，主体落位区干净开阔，草地质感细腻但不杂乱。"
                    + "可有远处虚化散步人影或野餐布作为轻氛围，但不要出现大面积杂物、近景人物、宠物、广告牌或抢眼道具。";
            case "花房庭院" -> "具体场景：花房、庭院或玻璃温室氛围，花架、藤蔓、绿植、石板路、木门窗或玻璃屋形成浪漫纵深，植物和建筑结构形成边缘层次。"
                    + "前景花枝、叶片或玻璃反射可以强虚化，形成梦幻柔焦、光影弥散和浪漫主义画面；整体是法式花园/生活方式大片质感，适合连衣裙、针织开衫、轻礼服、女装上新图，主体落位区保持清爽，花草只作为边缘前景和背景层次。"
                    + "不要让花枝、桌椅、门框遮挡模特站位，不要生成清晰人物、花店价签、文字招牌或过度拥挤的花墙。";
            case "咖啡店外" -> "具体场景：街角咖啡店外的生活方式街拍场景，干净玻璃橱窗、门口小圆桌、遮阳伞或木质座椅作为边缘氛围，街面平整开阔。"
                    + "玻璃橱窗反射、门口桌椅边缘和街面透视要形成电影抓拍感，画面有松弛感、城市日常感和商业大片质感，适合通勤、法式、休闲、针织、风衣穿搭。"
                    + "橱窗与桌椅不能压到主体落位区，不要出现清晰店名文字、价目表、密集人群或正在用餐的人物主体。";
            case "复古街区" -> "具体场景：复古街区时装街拍，石板路、老建筑立面、拱门、复古橱窗和远处建筑形成多层纵深，地面平整且主体站位开阔。"
                    + "石板路纹理、拱门阴影、橱窗反射和远处建筑形成多层空间，画面有胶片感、灰调质感、光影斑驳和故事感，适合风衣、大衣、裙装、通勤穿搭和高级感女装试穿。"
                    + "可有远处虚化行人或车辆形成现场感，但不能出现清晰人脸、强文字招牌、杂乱垃圾桶或遮挡身体的街边设施。";
            case "城市夜景" -> nightMood
                    ? "具体场景：都市夜景时装大片场景，湿润路面或光洁地面反射霓虹、路灯、橱窗灯和远处建筑灯光，背景有虚化交通灯和城市建筑层次。"
                    + "整体是高端夜景街拍质感，青橙色调、浅景深、情绪化光影、强空间纵深，适合大衣、风衣、礼服、通勤女装和高级感男装试穿。"
                    + "主体落位区必须明亮干净，有柔和人物预留补光；远景人群和车辆只作为虚化氛围，不要让灯牌文字、车辆或路人抢主体。"
                    : "具体场景：白天都市大片场景，可为干净城市广场、现代建筑外立面、玻璃幕墙街区、商业街开阔步道或城市天际线背景。"
                    + "整体是高级白天城市时装大片质感，明亮自然光、建筑线条、玻璃反射、开阔地面和清爽空间纵深，适合大衣、风衣、套装、通勤女装和高级感男装试穿。"
                    + "主体落位区必须干净开阔，有柔和人物预留补光；远景人群和车辆只作为低存在感氛围，不要让广告牌、车辆或路人抢主体。";
            case "天台露台" -> "具体场景：城市天台、露台或屋顶花园旅拍场景，低矮栏杆、远处天际线、露台地面、风感和开阔天空形成高级空间。"
                    + "利用栏杆边缘、露台地面线条、远处天际线和空气透视形成都市大片感，画面有风吹过的空间流动感，适合风衣、套装、礼服、轻户外和度假穿搭。"
                    + "栏杆和绿植只能在边缘或远景，不能切过人物身体，不要出现危险边缘站位、杂乱空调外机、清晰广告牌或密集人群。";
            case "艺术馆" -> "具体场景：艺术馆、美术馆或设计展厅空间，干净大面积墙面、拱形走廊、石材地面和少量抽象装置形成高级留白。"
                    + "用拱门、走廊、石材纹理、墙面漫反射和少量装置边缘形成强透视与高级留白，整体是极简高级、安静克制的时装大片氛围，适合高级感女装、男装、套装、大衣和设计款服装试穿。"
                    + "展品只能作为远景或边缘抽象形体，主体落位区必须空旷，不要出现清晰展览文字、密集观众、海报、水印或抢眼艺术品。";
            case "酒店民宿" -> "具体场景：精品酒店、度假民宿或高级公寓角落，落地窗、木质地板、亚麻窗帘、休闲沙发或床品陈设形成生活方式大片空间。"
                    + "利用落地窗光、窗帘柔焦、木地板反射和软装层次形成温暖松弛的生活方式大片，适合家居服、针织、度假裙装、通勤套装和轻奢女装试穿，主体落位区宽敞干净。"
                    + "家具和床品只能作为背景层次，不要让床、桌椅、行李箱遮挡人物，不要出现凌乱私人物品、品牌文字或镜面反射人物。";
            case "地铁通勤" -> "具体场景：高级通勤感地铁站入口、扶梯旁或站厅通道，干净地面、线性顶棚、金属墙面、玻璃护栏和强透视通道形成都市节奏。"
                    + "线性顶棚、玻璃护栏、金属墙面和地面反射形成秩序感与速度感，整体是通勤大片氛围，冷暖混合光、低饱和色彩、空间纵深明显，适合风衣、西装、衬衫、半裙和城市通勤穿搭。"
                    + "只允许远处虚化乘客轮廓，主体落位区不要有人，不要出现清晰线路图、广告字、闸机遮挡、二维码或拥挤人群。";
            case "校园林荫" -> "具体场景：校园林荫路或学院风步道，整齐树阵、红砖建筑、长椅、干净路面和建筑纵深形成青春感空间。"
                    + "树影、红砖建筑、长椅边缘和路面纵深形成清新文艺的电影抓拍感，画面松弛但有层次，适合衬衫、针织、百褶裙、卫衣、外套和学院风穿搭。"
                    + "远处可以有虚化学生轮廓作为氛围，但不能出现清晰未成年人脸部、校名文字、杂乱自行车或遮挡主体的树干。";
            case "公园绿荫" -> "具体场景：城市公园绿荫步道，树阵、草坪、长椅、石板路和植物边缘层次，地面干净平整，主体站位开阔。"
                    + "前景叶片、树影斑驳、草坪纵深和步道引导线形成自然生活方式大片，明亮但柔和，适合休闲、运动、亲子、针织、裙装和日常通勤服装试穿。"
                    + "前景可有少量虚化树叶和植物层次，远处可有低存在感路人轮廓，但不要出现运动器材、垃圾桶、密集人群或过强绿色色偏。";
            default -> customScenePromptDetail(sceneType, sceneName);
        };
    }

    private String customScenePromptDetail(String sceneType, String sceneName) {
        String base = "具体场景：" + sceneName + "。用户填写的词仅作为场景主题，需要将其完善为适合电商服装试穿合成的生成空场景背景。";
        if (isOutdoorScene(sceneType, sceneName)) {
            return base
                    + "围绕该主题设计真实可信的大片级室外环境、地面、背景纵深、电影感光影和景深效果；可使用城市建筑层次、环境灯光、地面反射和远景虚化，画面必须能让后续可以自然放入真人模特和服装商品。"
                    + "主体落位区保持开阔、干净、无遮挡，人物站立位置前后左右留出足够空间，避免车辆、栏杆、桌椅、门框或强透视压到身体区域。"
                    + "不要根据用户词生成正在试穿的人、衣服、商品展示架或广告牌，只生成可承载人物与服装合成的空环境。";
        }
        return base
                + "围绕该主题设计真实可信的大片级室内空间环境、地面、背景墙、室内纵深、商业布光和景深效果；优先使用窗光、柔和主光、灯带、轮廓光、墙面漫反射、地面微反射、材质层次和干净陈设，画面必须能让后续可以自然放入真人模特和服装商品。"
                + "主体落位区保持开阔、干净、无遮挡，人物站立位置前后左右留出足够空间，避免桌椅、门框、装饰物、镜面反射或强透视压到身体区域。"
                + "不要根据用户词生成正在试穿的人、衣服、商品展示架或广告牌，只生成可承载人物与服装合成的空环境。";
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
        String value = extractTryOnRequirementSection(requirements, "展示方式：");
        List<String> selectedActions = Arrays.stream(value.split("[、,，/\\s]+"))
                .map(String::trim)
                .filter(action -> ALLOWED_TRY_ON_DISPLAY_ACTIONS.contains(action))
                .distinct()
                .limit(4)
                .toList();
        return selectedActions.isEmpty() ? DEFAULT_TRY_ON_DISPLAY_ACTIONS : selectedActions;
    }

    private String parseTryOnPerformanceStyle(String requirements) {
        String value = extractTryOnRequirementSection(requirements, "表演风格：").trim();
        return ALLOWED_TRY_ON_PERFORMANCE_STYLES.contains(value) ? value : DEFAULT_TRY_ON_PERFORMANCE_STYLE;
    }

    private List<String> parseTryOnPerformanceBoosts(String requirements) {
        String value = extractTryOnRequirementSection(requirements, "动作表情：");
        return Arrays.stream(value.split("[、,，/\\s]+"))
                .map(String::trim)
                .filter(boost -> ALLOWED_TRY_ON_PERFORMANCE_BOOSTS.contains(boost))
                .distinct()
                .limit(MAX_TRY_ON_PERFORMANCE_BOOSTS)
                .toList();
    }

    private String extractTryOnRequirementSection(String requirements, String label) {
        String value = defaultIfBlank(requirements, "");
        int start = value.indexOf(label);
        if (start < 0) {
            return "";
        }
        value = value.substring(start + label.length());
        int chineseSemicolon = value.indexOf('；');
        int asciiSemicolon = value.indexOf(';');
        int end = -1;
        if (chineseSemicolon >= 0 && asciiSemicolon >= 0) {
            end = Math.min(chineseSemicolon, asciiSemicolon);
        } else if (chineseSemicolon >= 0) {
            end = chineseSemicolon;
        } else if (asciiSemicolon >= 0) {
            end = asciiSemicolon;
        }
        if (end >= 0) {
            value = value.substring(0, end);
        }
        return value.trim();
    }

    private String tryOnActionVisualDescription(String action, String requirements) {
        String actionDescription = switch (action) {
            case "全身站立" -> "真人写实模特 {model} 穿上商品图中的 {product}，用低角度中远景或时尚杂志封面式中远景建立第一眼气场；镜头先拉远建立场景空间，再从脚部向上扫描到肩颈和脸部，模特跨步前点、单腿承重、髋部轻微转向，肩线打开，下巴微抬，眼神从镜外导视点回到镜头并短暂停留，完整展示从头到脚的穿着效果，衣长、裤长、整体比例和服装廓形必须清楚。";
            case "转身展示" -> "真人写实模特 {model} 穿上商品图中的 {product}，不要原地机械旋转；先从四分之三角度站定，动作幅度要比普通试穿更明确，重心转移到后侧腿，肩线和髋部先动带动身体慢动作转身，镜头推进后轻微拉远，镜头轻微环绕人物半圈，侧逆光掠过肩线、腰线、下摆和发丝边缘，眼神从镜外导视点回到镜头再转身离开，连续展示正面、侧面到背面的穿着状态，避免快速旋转和身体扭曲。";
            case "走动展示" -> "真人写实模特 {model} 穿上商品图中的 {product}，以电影街拍方式自然向前或轻微斜向走动，步幅比普通试穿更有气场；可以从快走自然过渡到轻跑两三步，再稳稳急停或旋身回看镜头，表情明亮自信，允许自然笑起来；镜头先给脚步和下摆的中近景，再侧跟拍到中景，最后轻微推近脸部和上半身，步伐有节奏，重心清楚，发丝飞舞，裙摆摆动或衣摆回弹，眼神从镜外导视点转向镜头并短暂停顿，展示面料垂坠、摆动幅度和衣服随身体运动的真实状态。";
            case "细节展示" -> "镜头从真人写实模特 {model} 穿着 {product} 的半身中景自然推近到特写，再轻微拉回半身关系，展示领口、袖口、纽扣、腰线、下摆、图案或拼接位置；使用斜向侧光、浅景深、材质反光和手指牵引、按压或轻扫布料的动作，动作要有停顿和呼吸，局部清晰但不能裁掉关键穿着关系，不要做成廉价扫货式局部特写。";
            case "半身近景" -> "真人写实模特 {model} 穿上商品图中的 {product}，使用头肩到腰胯的中近景，镜头缓慢推进；模特眼神先看向镜外导视点，再回到镜头并停留半秒，随后微微侧头、整理发丝或领口，展示上半身版型、领口、肩袖、胸前图案和面料质感，脸部与服装都要自然。";
            case "坐姿展示" -> "真人写实模特 {model} 穿上商品图中的 {product}，以法式电影慵懒浪漫风从站姿侧身入座或半坐，身体形成自然S型曲线，一只脚轻点地面或自然前伸，手臂换位到膝侧或椅背，镜头从低角度中景推近到腰胯与下摆，展示坐姿下衣服褶皱、下摆、腰腹贴合和舒适度，身体比例不能变形。";
            case "抬手展示" -> "真人写实模特 {model} 穿上商品图中的 {product}，抬手幅度要清楚，用中景抓拍抬手整理袖口、拨开发丝、触碰帽沿或调整肩线的瞬间；手臂抬起带出肩部牵拉，袖口自然回落，发丝边缘光清晰，眼神短暂回看镜头，展示袖长、袖口、腋下余量、肩部活动空间和衣服牵拉状态。";
            case "整理衣摆" -> "真人写实模特 {model} 穿上商品图中的 {product}，镜头从腰胯中景推进到下摆特写，人物先轻微跨步或转胯，再用手指自然牵引布料，整理衣摆、腰头或下摆；捕捉衣摆回落、布料垂坠、下摆线恢复、开衩和裤裙衔接，展示衣长、腰线、下摆线和垂坠恢复状态，动作要有真实幅度和停顿。";
            case "拿包通勤" -> "真人写实模特 {model} 穿上商品图中的 {product}，通勤包或极简手袋必须有明确来源：包从镜头外自然递入，或模特从画面边缘、桌面、椅背、挂架处自然拿起；拿到包后用通勤大片的极具张力的台步走向镜头，肩线打开，下巴微抬，走到合适距离短暂停顿，单手拎包或轻搭肩带，再回看镜头或侧身转体，展示商品在通勤场景中的整体穿搭比例；包必须低调，不要遮挡商品主体，不能在手中凭空出现。";
            case "戴帽展示" -> "真人写实模特 {model} 穿上商品图中的 {product}，仅在商品本身有帽子、兜帽或连帽结构时，自然把帽子戴上并轻微调整帽沿或帽绳；动作使用慢动作，中近景展示明显手部牵引帽沿、发丝边缘光、领口和肩颈关系，随后眼神短暂回看镜头；如果商品没有帽子结构，不能凭空新增帽子或兜帽。";
            case "搭配展示" -> "真人写实模特 {model} 穿上商品图中的 {product}，用极简基础单品辅助呈现穿搭效果；搭配单品必须从镜头外自然拿取或由镜头外助手递入，先完成拿取或递入动作再进入展示，不能突然出现在人物手中或身上；镜头使用时尚杂志封面质感、大面积留白、中远景站位、慢动作走位、单手叉腰、侧身转体或低角度仰拍，商品本身必须是唯一视觉重点，不要新增抢眼饰品或改变商品。";
            default -> "真人写实模特 {model} 穿上商品图中的 {product}，自然展示商品上身效果，画面必须同时参考模特基础图、商品参考图和场景图。";
        };
        return actionDescription
                + tryOnShotCinematicStyle(action)
                + tryOnPerformanceStylePrompt(parseTryOnPerformanceStyle(requirements))
                + tryOnPerformanceBoostPrompt(parseTryOnPerformanceBoosts(requirements))
                + "展示方式：" + action + "。"
                + "动作镜头需要和前后展示方式自然衔接，保持同一个场景、同一位模特、同一套服装，不要硬切跳变。"
                + "动作幅度要比普通试穿更明确，允许跨步、快走、轻跑、转胯、肩线打开、回身、旋身、甩发、短暂停顿、低头整理、抬手、侧身转体和带风感的走位，但必须是时装大片表演感，不要尬舞、不要夸张喜剧表演。"
                + "表演气质要自信、大方、个性张扬，笑容要自然明亮，可以有自信微笑、开朗笑起来或爽朗大笑；不要扭扭捏捏、害羞拘谨、小心翼翼。"
                + "如果本动作出现包、帽子、外搭、配饰或其他搭配物件，必须通过镜头外拿取、画面边缘拿起、桌椅挂架取下或镜头外助手递入来出现，不要凭空生成或瞬间换装。"
                + "人物眼神要自然有焦点，眼神从镜外导视点回到镜头时要短暂停留，配合轻微眨眼、自然呼吸和微表情变化，不要呆滞凝视镜头，不要从头到尾盯着远方。"
                + "镜头不能像固定监控机位，必须根据动作加入推近、拉远、低角度仰拍、侧跟拍、轻微环绕、脚步到上半身扫描或半身到特写再回到中景的变化。"
                + "全程严格参考商品主图、细节图和上身参考，准确保持颜色、版型、图案、面料、衣长袖长和结构细节。"
                + "展示要求：" + requirements + "。"
                + "不要把商品图作为角落参考卡片、悬浮图、贴纸或画中画显示出来。";
    }

    private String tryOnShotCinematicStyle(String action) {
        String actionMood = switch (action) {
            case "全身站立" -> "本镜头像时装街拍或棚拍大片开场，不是站桩；用完整身形、低角度仰拍、镜头推进、镜头拉远后再推近、单腿承重、肩线打开、髋部轻转和场景纵深建立第一眼高级感，服装廓形必须是视觉中心。";
            case "转身展示" -> "本镜头用重心转移和肩线带动转身制造连续轮廓变化，镜头推进、拉远或轻微环绕，侧逆光在肩线、腰线、下摆和发丝边缘自然滑过，形成流动的电影感。";
            case "走动展示" -> "本镜头要有自然抓拍感和现场感，侧跟拍和轻微推近带出衣摆、面料、发丝飞舞、裙摆摆动和光影变化，背景可轻微运动虚化但人物和服装保持稳定清晰。";
            case "细节展示" -> "本镜头像高级杂志细节页，从中景推到特写，靠近面料、领口、袖口、图案或工艺，使用浅景深、材质反光和电影级布光，但关键细节必须清楚可辨。";
            case "半身近景" -> "本镜头突出脸部自然状态与上半身服装关系，用柔和主光、眼神接触、发丝边缘光、背景虚化和短暂停顿形成亲近的时装人像感。";
            case "坐姿展示" -> "本镜头是法式电影慵懒浪漫风和生活方式大片感，人物自然落座，S型身体曲线、衣服褶皱、腰腹贴合和下摆状态真实，场景氛围安静但不沉闷。";
            case "抬手展示" -> "本镜头用清楚的抬手幅度、整理袖口或拨开发丝制造动态瞬间，展示肩袖活动空间，光影在袖口、发丝边缘光和面料纹理上有细小变化。";
            case "整理衣摆" -> "本镜头聚焦手指牵引布料、衣摆回落和下摆线恢复，画面要有细腻布料垂坠、材质反光和自然呼吸感，不要做成机械操作演示。";
            case "拿包通勤" -> "本镜头呈现通勤大片氛围，道具入画要自然，包只服务于穿搭比例和场景叙事；模特可以用极具张力的台步、短暂停顿、下巴微抬和肩线打开形成高级压场感，不能遮挡商品主体。";
            case "戴帽展示" -> "本镜头只在商品有帽子结构时强化帽型和领口关系，动作要像真实试穿中顺手戴帽，慢动作保留帽檐、发丝边缘光、手部牵引和肩颈层次。";
            case "搭配展示" -> "本镜头是完整造型氛围展示，搭配物件自然进入画面后服务于整体风格；使用时尚杂志封面质感、大面积留白和中远景站位，不能盖过商品本身。";
            default -> "本镜头保持高级商业摄影氛围，兼顾真实试穿和自然场景叙事。";
        };
        return "画面氛围：" + actionMood
                + "摄影语言保持电影感光影、浅景深、胶片颗粒、自然抓拍感和空间层次；必须以用户选中的场景图为唯一背景基准，根据场景图已有的地点、时间、光线和环境元素选择匹配的都市情绪、秀场、法式电影或自然旅拍气质，不要把场景图改成另一种地点。"
                + "每个动作至少一次景别变化或视角变化，优先使用推近、拉远、低角度仰拍、侧跟拍、轻微环绕、脚步到上半身扫描或半身到特写再回到中景。"
                + "背景效果必须来自场景图本身，例如海边只强化海面反光、海风、沙地、逆光或空气透视，街景只强化街道反射、建筑纵深或橱窗光，棚拍只强化棚拍布光和留白；不能跨场景套用霓虹反光、闪光灯直打或室内灯光。"
                + "服装主体、颜色、版型和关键细节必须清晰稳定。";
    }

    private String tryOnPerformanceStylePrompt(String style) {
        return "表演风格：" + style + "。"
                + switch (style) {
            case "甜酷活力" -> "模特状态明亮、有能量，动作节奏更轻快，可以有弹性跨步、轻快转身、自然笑起来和短暂俏皮回看；镜头互动要像年轻时尚街拍，活力外放但不幼稚，不要撒娇式卖萌。";
            case "高冷超模" -> "模特气场冷艳、克制、有压迫感，步伐利落，肩线打开，下巴微抬，眼神犀利直接；可以使用低角度仰拍、硬光边缘、镜头前急停和单手叉腰，但表情不要呆滞或端着不动。";
            case "松弛度假" -> "模特身体更舒展，动作像旅拍抓拍，允许迎风轻跑、回身大笑、整理发丝、裙摆或衣摆随风摆动；镜头跟拍要轻盈自然，表情放松开朗，不能变成站桩游客照。";
            case "明朗亲和" -> "模特亲切、自然、有感染力，眼神交流更温暖，笑容更真实，动作可以主动靠近镜头、轻轻整理衣服、自然转身和开朗笑起来；保持商业大片质感，不要做成直播卖货口播。";
            default -> "模特气质自信、大方、个性张扬，像时装大片主角一样主动掌控镜头；动作幅度清楚，眼神有互动，表情有真实反应，可以大步走近、稳稳急停、回看镜头和自然微笑，但不要尬舞或夸张搞笑。";
        };
    }

    private String tryOnPerformanceBoostPrompt(List<String> boosts) {
        if (boosts == null || boosts.isEmpty()) {
            return "";
        }
        String boostText = String.join("、", boosts);
        String details = boosts.stream()
                .map(this::tryOnPerformanceBoostDetail)
                .collect(Collectors.joining(""));
        return "动作表情强化：" + boostText + "。" + details;
    }

    private String tryOnPerformanceBoostDetail(String boost) {
        return switch (boost) {
            case "轻跑靠近镜头" -> "轻跑靠近镜头：模特从中远景轻跑两三步靠近镜头，步伐有弹性，发丝、衣摆或裙摆被运动带起，靠近后自然降速并保持服装主体清晰。";
            case "快走急停回看" -> "快走急停回看：模特以有气场的快走斜向靠近镜头，走到合适距离后稳稳急停，身体轻微旋转回看镜头，急停时重心稳定，衣摆有自然回弹。";
            case "旋身甩发" -> "旋身甩发：肩线和髋部先带动身体旋身，发丝甩出清晰动态弧线，旋身后短暂停住看向镜头，避免机械原地转圈和头发漂移。";
            case "主动靠近镜头" -> "主动靠近镜头：模特从场景中主动走近镜头，镜头可以轻微后退或推近，形成强烈现场感和压迫感，靠近时脸部、眼神和服装上半身都要稳定自然。";
            case "回头挑眉" -> "回头挑眉：模特先看向镜外导视点，再回头看向镜头，眉峰轻挑并停留半秒，眼神有明确互动和一点张扬感，不要空洞盯远方。";
            case "大胆回看镜头" -> "大胆回看镜头：模特在动作完成的停顿点直接回看镜头，眼神自信、有焦点、有短暂停留，再自然移开，形成和观众的镜头交流。";
            case "自信微笑" -> "自信微笑：嘴角自然上扬，笑意从眼神带出来，表情松弛有掌控感，不要假笑、僵硬笑或只动嘴角。";
            case "开朗笑起来" -> "开朗笑起来：模特在转身、靠近或整理衣服后自然笑起来，笑容明亮，肩颈和呼吸有真实变化，不能像摆拍定格笑。";
            case "爽朗大笑" -> "爽朗大笑：保留一小段真实大笑或笑着回看镜头的瞬间，动作外放但高级，不能夸张搞笑，不能牺牲服装主体清晰度。";
            default -> "";
        };
    }

    private String tryOnActionVoiceover(String action) {
        return switch (action) {
            case "全身站立" -> "先看整体上身效果，版型和长度都比较直观。";
            case "转身展示" -> "转身看一下侧面和背面的线条。";
            case "走动展示" -> "走动时也能看到面料的垂感和轮廓。";
            case "细节展示" -> "细节位置也给大家看清楚。";
            case "半身近景" -> "近一点看上半身的剪裁和质感。";
            case "坐姿展示" -> "坐下后也能看到舒适度和褶皱状态。";
            case "抬手展示" -> "抬手时袖长和活动空间也很关键。";
            case "整理衣摆" -> "整理一下衣摆，看衣长和下摆回落状态。";
            case "拿包通勤" -> "搭配通勤包时也能看出日常穿着比例。";
            case "戴帽展示" -> "如果是连帽款，戴上帽子看一下帽型和领口。";
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
        String turnaroundGuard = ", 正面+标准90度侧面+标准背面, 三视图发根发缝一致, 侧面头发贴合头部自然垂落, 背面发束中心线对齐脊柱, 禁止头发漂移";
        if ("儿童".equals(gender)) {
            return "真人写实, 电商服装带货, 大头特写, 右侧三视图" + turnaroundGuard + ", 纯白背景, " + bodyKeyword + ", 浅色儿童基础打底服, 不透, 低干扰";
        }
        if ("男".equals(gender)) {
            return "真人写实, 电商服装带货, 大头特写, 右侧三视图" + turnaroundGuard + ", 纯白背景, " + bodyKeyword + ", 男模特, 上半身不穿, 上半身裸露, 普通浅色四角内裤, 纯色无花纹, 基础男士款, 低干扰, 惊艳但真实";
        }
        return "真人写实, 电商服装带货, 大头特写, 右侧三视图" + turnaroundGuard + ", 纯白背景, " + bodyKeyword + ", 女模特, 白色微透蕾丝内衣, 低干扰, 惊艳但真实";
    }

    private String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
