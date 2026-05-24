package com.manga.ai.commerce.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.manga.ai.commerce.dto.CommerceSeriesCreateRequest;
import com.manga.ai.commerce.dto.CommerceSeriesVO;
import com.manga.ai.commerce.dto.CommerceModelCreateRequest;
import com.manga.ai.commerce.dto.CommerceModelVO;
import com.manga.ai.commerce.dto.CommerceProductCreateRequest;
import com.manga.ai.commerce.dto.CommerceProductVO;
import com.manga.ai.commerce.dto.CommerceProjectCreateRequest;
import com.manga.ai.commerce.dto.CommerceProjectVO;
import com.manga.ai.commerce.dto.CommerceSceneBatchCreateRequest;
import com.manga.ai.commerce.dto.CommerceSceneGenerationStartRequest;
import com.manga.ai.commerce.dto.CommerceSceneVO;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class CommerceServiceImplTest {

    private final CommerceSeriesMapper seriesMapper = mock(CommerceSeriesMapper.class);
    private final CommerceModelMapper modelMapper = mock(CommerceModelMapper.class);
    private final CommerceProductMapper productMapper = mock(CommerceProductMapper.class);
    private final CommerceProjectMapper projectMapper = mock(CommerceProjectMapper.class);
    private final CommerceSceneMapper sceneMapper = mock(CommerceSceneMapper.class);
    private final CommerceShotMapper shotMapper = mock(CommerceShotMapper.class);
    private final SeedanceService seedanceService = mock(SeedanceService.class);
    private final UserService userService = mock(UserService.class);
    private final CommerceModelProfileService modelProfileService = mock(CommerceModelProfileService.class);
    private final CommerceProductImagePrivacyService productImagePrivacyService = mock(CommerceProductImagePrivacyService.class);
    private final ImageGenerateService imageGenerateService = mock(ImageGenerateService.class);
    private final OssService ossService = mock(OssService.class);
    private final List<Runnable> commerceImageTasks = new ArrayList<>();
    private final List<Runnable> commerceVideoTasks = new ArrayList<>();
    private final Executor commerceImageExecutor = commerceImageTasks::add;
    private final Executor commerceVideoExecutor = commerceVideoTasks::add;
    private final CommerceServiceImpl service = new CommerceServiceImpl(
            seriesMapper,
            modelMapper,
            productMapper,
            projectMapper,
            sceneMapper,
            shotMapper,
            seedanceService,
            userService,
            modelProfileService,
            productImagePrivacyService,
            imageGenerateService,
            ossService,
            commerceImageExecutor,
            commerceVideoExecutor
    );

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        commerceImageTasks.clear();
        commerceVideoTasks.clear();
    }

    private void keepProductReferencesUnchanged() {
        when(productImagePrivacyService.sanitizeProductReferences(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createSeriesDefaultsToClothingRealisticAndNineSixteen() {
        UserContextHolder.setUserId(9L);
        CommerceSeriesCreateRequest request = new CommerceSeriesCreateRequest();
        request.setName("小雅女装种草号");

        CommerceSeriesVO result = service.createSeries(request);

        assertThat(result.getCategory()).isEqualTo("clothing");
        assertThat(result.getVisualStyle()).isEqualTo("realistic");
        assertThat(result.getDefaultAspectRatio()).isEqualTo("9:16");
    }

    @Test
    void createSeriesRejectsUnsupportedRatio() {
        UserContextHolder.setUserId(9L);
        CommerceSeriesCreateRequest request = new CommerceSeriesCreateRequest();
        request.setName("小雅女装种草号");
        request.setDefaultAspectRatio("2:1");

        assertThatThrownBy(() -> service.createSeries(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的画幅比例");
    }

    @Test
    void generateModelPromptReturnsEditablePromptFromGuidedChoices() {
        UserContextHolder.setUserId(9L);
        CommerceModelProfile generatedProfile = new CommerceModelProfile();
        generatedProfile.setName("小雅");
        generatedProfile.setPrompt("年轻东亚女性，五官清爽，眼神有记忆点，性格亲和自然，适合女装种草。\n身材设定：微胖亲和");
        when(modelProfileService.generateProfile("", "女", "年轻美女", "中国", "微胖亲和")).thenReturn(generatedProfile);

        CommerceModelProfile result = service.generateModelPrompt("小雅", "女", "年轻美女", "中国", "微胖亲和");

        assertThat(result.getName()).isEqualTo("小雅");
        assertThat(result.getPrompt()).contains("性格亲和");
        assertThat(result.getPrompt()).doesNotContain("眼睛");
        assertThat(result.getPrompt()).doesNotContain("眼神");
        assertThat(result.getPrompt()).doesNotContain("eye");
        assertThat(result.getPrompt()).doesNotContain("身材设定");
        assertThat(result.getPrompt()).doesNotContain("微胖亲和");
        assertThat(result.getBodyProfile()).isEqualTo("微胖亲和");
        verify(modelProfileService).generateProfile("", "女", "年轻美女", "中国", "微胖亲和");
        verify(userService).deductCredits(
                eq(9L),
                eq(CreditConstants.CREDITS_PER_COMMERCE_MODEL_PROMPT),
                eq(CreditUsageType.IMAGE_GENERATION.getCode()),
                eq("电商模特提示词生成"),
                eq(9L),
                eq("MODEL_PROMPT")
        );
    }

    @Test
    void createModelRequiresConfirmedPromptAndReturnsQueuedPlaceholderBeforeImageGeneration() {
        UserContextHolder.setUserId(9L);
        CommerceSeries series = new CommerceSeries();
        series.setId(100L);
        series.setUserId(9L);
        series.setDefaultAspectRatio("9:16");
        when(seriesMapper.selectById(100L)).thenReturn(series);

        CommerceModelCreateRequest request = new CommerceModelCreateRequest();
        request.setName("小雅");
        request.setGender("女");
        request.setModelType("年轻美女");
        request.setEthnicity("中国");
        request.setBodyProfile("丰满曲线");
        request.setModelAspectRatio("16:9");
        request.setSourceType("uploaded");
        request.setBaseImageUrl("https://oss.example.com/model.png");
        request.setAppearancePrompt("年轻东亚女性，五官清爽，性格亲和自然，适合女装种草。");

        CommerceModelVO result = service.createModel(100L, request);

        assertThat(result.getName()).isEqualTo("小雅");
        assertThat(result.getSourceType()).isEqualTo("ai");
        assertThat(result.getBaseImageUrl()).isNull();
        assertThat(result.getGenderAge()).isEqualTo("女 / 年轻美女 / 中国");
        assertThat(result.getBodyProfile()).isEqualTo("丰满曲线");
        assertThat(result.getAppearancePrompt()).contains("性格亲和");
        assertThat(result.getStatus()).isEqualTo("queued");

        ArgumentCaptor<CommerceModel> modelCaptor = ArgumentCaptor.forClass(CommerceModel.class);
        verify(modelMapper).insert(modelCaptor.capture());
        CommerceModel saved = modelCaptor.getValue();
        assertThat(saved.getSourceType()).isEqualTo("ai");
        assertThat(saved.getBaseImageUrl()).isNull();
        assertThat(saved.getStatus()).isEqualTo("queued");
        assertThat(saved.getBodyProfile()).isEqualTo("丰满曲线");
        assertThat(saved.getAppearancePrompt()).isEqualTo("年轻东亚女性，五官清爽，性格亲和自然，适合女装种草。");
        verify(imageGenerateService, never()).generateCommerceModelImage(any());
        assertThat(commerceImageTasks).isEmpty();
        verify(userService, never()).deductCredits(
                eq(9L),
                eq(CreditConstants.CREDITS_PER_IMAGE),
                eq(CreditUsageType.IMAGE_GENERATION.getCode()),
                eq("电商模特生成-小雅"),
                eq(100L),
                eq("COMMERCE_SERIES")
        );
    }

    @Test
    void startModelImageGenerationMarksQueuedModelGeneratingAndQueuesImageTask() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(66L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setName("小雅");
        model.setGenderAge("女 / 年轻美女 / 中国");
        model.setBodyProfile("丰满曲线");
        model.setAppearancePrompt("年轻东亚女性，五官清爽，性格亲和自然，适合女装种草。");
        model.setModelAspectRatio("16:9");
        model.setStatus("queued");
        when(modelMapper.selectById(66L)).thenReturn(model);
        ImageGenerateResponse imageResponse = new ImageGenerateResponse();
        imageResponse.setStatus("success");
        imageResponse.setImageUrl("https://oss.example.com/generated-model.png");
        when(imageGenerateService.generateCommerceModelImage(any())).thenReturn(imageResponse);

        CommerceModelVO result = service.startModelImageGeneration(66L);

        assertThat(result.getStatus()).isEqualTo("generating");
        assertThat(model.getStatus()).isEqualTo("generating");
        verify(imageGenerateService, never()).generateCommerceModelImage(any());
        verify(userService, never()).deductCredits(anyLong(), anyInt(), anyString(), anyString(), anyLong(), anyString());
        assertThat(commerceImageTasks).hasSize(1);

        commerceImageTasks.get(0).run();

        verify(userService).deductCredits(
                eq(9L),
                eq(CreditConstants.CREDITS_PER_IMAGE),
                eq(CreditUsageType.IMAGE_GENERATION.getCode()),
                eq("电商模特生成-小雅"),
                eq(100L),
                eq("COMMERCE_SERIES")
        );
        ArgumentCaptor<ImageGenerateRequest> imageRequestCaptor = ArgumentCaptor.forClass(ImageGenerateRequest.class);
        verify(imageGenerateService).generateCommerceModelImage(imageRequestCaptor.capture());
        assertThat(imageRequestCaptor.getValue().getAspectRatio()).isEqualTo("16:9");
        assertThat(imageRequestCaptor.getValue().getQuality()).isEqualTo("4k");
        assertThat(imageRequestCaptor.getValue().getCustomPrompt()).contains("身材设定：丰满曲线");
        assertThat(imageRequestCaptor.getValue().getStyleKeywords()).contains("白色微透蕾丝内衣");
        assertThat(model.getStatus()).isEqualTo("pending_review");
        assertThat(model.getBaseImageUrl()).isEqualTo("https://oss.example.com/generated-model.png");
    }

    @Test
    void startModelImageGenerationIsIdempotentWhenModelAlreadyGenerating() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(66L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setName("小雅");
        model.setAppearancePrompt("年轻东亚女性，五官清爽，性格亲和自然，适合女装种草。");
        model.setStatus("generating");
        when(modelMapper.selectById(66L)).thenReturn(model);

        CommerceModelVO result = service.startModelImageGeneration(66L);

        assertThat(result.getStatus()).isEqualTo("generating");
        assertThat(commerceImageTasks).isEmpty();
        verify(modelMapper, never()).updateById(any());
        verify(userService, never()).deductCredits(anyLong(), anyInt(), anyString(), anyString(), anyLong(), anyString());
        verify(imageGenerateService, never()).generateCommerceModelImage(any());
    }


    @Test
    void createModelAlwaysStoresAndGeneratesFixedSixteenNineModelRatio() {
        UserContextHolder.setUserId(9L);
        assignModelIdOnInsert(66L);
        CommerceSeries series = new CommerceSeries();
        series.setId(100L);
        series.setUserId(9L);
        series.setDefaultAspectRatio("9:16");
        when(seriesMapper.selectById(100L)).thenReturn(series);

        CommerceModelCreateRequest request = new CommerceModelCreateRequest();
        request.setName("小雅");
        request.setGender("女");
        request.setModelType("年轻美女");
        request.setEthnicity("中国");
        request.setBodyProfile("标准匀称");
        request.setModelAspectRatio("9:16");
        request.setAppearancePrompt("年轻东亚女性，五官清爽，性格亲和自然，适合女装种草。");
        ImageGenerateResponse imageResponse = new ImageGenerateResponse();
        imageResponse.setStatus("success");
        imageResponse.setImageUrl("https://oss.example.com/generated-model.png");
        when(imageGenerateService.generateCommerceModelImage(any())).thenReturn(imageResponse);

        CommerceModelVO result = service.createModel(100L, request);

        assertThat(result.getModelAspectRatio()).isEqualTo("16:9");
        ArgumentCaptor<CommerceModel> modelCaptor = ArgumentCaptor.forClass(CommerceModel.class);
        verify(modelMapper).insert(modelCaptor.capture());
        CommerceModel saved = modelCaptor.getValue();
        assertThat(saved.getModelAspectRatio()).isEqualTo("16:9");
        verify(imageGenerateService, never()).generateCommerceModelImage(any());
        when(modelMapper.selectById(66L)).thenReturn(saved);
        service.startModelImageGeneration(66L);
        commerceImageTasks.get(0).run();

        ArgumentCaptor<ImageGenerateRequest> imageRequestCaptor = ArgumentCaptor.forClass(ImageGenerateRequest.class);
        verify(imageGenerateService).generateCommerceModelImage(imageRequestCaptor.capture());
        assertThat(imageRequestCaptor.getValue().getAspectRatio()).isEqualTo("16:9");
    }

    @Test
    void createMaleModelUsesPlainBoxerBriefsInStyleKeywordsForAnyMaleType() {
        UserContextHolder.setUserId(9L);
        assignModelIdOnInsert(66L);
        CommerceSeries series = new CommerceSeries();
        series.setId(100L);
        series.setUserId(9L);
        when(seriesMapper.selectById(100L)).thenReturn(series);

        CommerceModelCreateRequest request = new CommerceModelCreateRequest();
        request.setName("阿泽");
        request.setGender("男");
        request.setModelType("亲和素人男性");
        request.setEthnicity("中国");
        request.setBodyProfile("标准匀称");
        request.setModelAspectRatio("16:9");
        request.setAppearancePrompt("年轻中国男性，五官清爽，性格亲和自然，适合男装展示。");
        ImageGenerateResponse imageResponse = new ImageGenerateResponse();
        imageResponse.setStatus("success");
        imageResponse.setImageUrl("https://oss.example.com/generated-male-model.png");
        when(imageGenerateService.generateCommerceModelImage(any())).thenReturn(imageResponse);

        service.createModel(100L, request);

        ArgumentCaptor<CommerceModel> modelCaptor = ArgumentCaptor.forClass(CommerceModel.class);
        verify(modelMapper).insert(modelCaptor.capture());
        when(modelMapper.selectById(66L)).thenReturn(modelCaptor.getValue());
        verify(imageGenerateService, never()).generateCommerceModelImage(any());
        service.startModelImageGeneration(66L);
        commerceImageTasks.get(0).run();

        ArgumentCaptor<ImageGenerateRequest> imageRequestCaptor = ArgumentCaptor.forClass(ImageGenerateRequest.class);
        verify(imageGenerateService).generateCommerceModelImage(imageRequestCaptor.capture());
        assertThat(imageRequestCaptor.getValue().getStyleKeywords())
                .contains("男模特")
                .contains("上半身不穿")
                .contains("普通浅色四角内裤")
                .doesNotContain("蕾丝")
                .doesNotContain("网纱")
                .doesNotContain("内衣")
                .doesNotContain("白色微透蕾丝内衣");
    }

    @Test
    void createModelPersistsFailedModelWhenImageGenerationFails() {
        UserContextHolder.setUserId(9L);
        assignModelIdOnInsert(66L);
        CommerceSeries series = new CommerceSeries();
        series.setId(100L);
        series.setUserId(9L);
        when(seriesMapper.selectById(100L)).thenReturn(series);

        CommerceModelCreateRequest request = new CommerceModelCreateRequest();
        request.setName("妹妹");
        request.setGender("女");
        request.setModelType("年轻美女");
        request.setEthnicity("中国");
        request.setModelAspectRatio("16:9");
        request.setAppearancePrompt("年轻东亚女性，五官清爽，性格亲和自然，适合女装种草。");
        ImageGenerateResponse imageResponse = new ImageGenerateResponse();
        imageResponse.setStatus("failed");
        imageResponse.setErrorMessage("图片内容可能触发安全策略，请调整提示词后重新生成");
        when(imageGenerateService.generateCommerceModelImage(any())).thenReturn(imageResponse);

        CommerceModelVO result = service.createModel(100L, request);

        assertThat(result.getName()).isEqualTo("妹妹");
        assertThat(result.getStatus()).isEqualTo("queued");
        assertThat(result.getBaseImageUrl()).isNull();
        assertThat(result.getErrorMessage()).isNull();

        ArgumentCaptor<CommerceModel> modelCaptor = ArgumentCaptor.forClass(CommerceModel.class);
        verify(modelMapper).insert(modelCaptor.capture());
        CommerceModel saved = modelCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo("queued");
        assertThat(saved.getErrorMessage()).isNull();
        assertThat(saved.getBaseImageUrl()).isNull();
        verify(imageGenerateService, never()).generateCommerceModelImage(any());

        when(modelMapper.selectById(66L)).thenReturn(saved);
        service.startModelImageGeneration(66L);
        assertThat(saved.getStatus()).isEqualTo("generating");
        commerceImageTasks.get(0).run();

        assertThat(saved.getStatus()).isEqualTo("failed");
        assertThat(saved.getErrorMessage()).contains("安全策略");
        assertThat(saved.getBaseImageUrl()).isNull();
        verify(modelMapper).update(eq(null), any(UpdateWrapper.class));
        verify(userService).deductCredits(
                eq(9L),
                eq(CreditConstants.CREDITS_PER_IMAGE),
                eq(CreditUsageType.IMAGE_GENERATION.getCode()),
                eq("电商模特生成-妹妹"),
                eq(100L),
                eq("COMMERCE_SERIES")
        );
    }

    @Test
    void regenerateFailedModelUsesStoredPromptAndUpdatesImage() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(66L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setName("妹妹");
        model.setGenderAge("女 / 年轻美女 / 中国");
        model.setAppearancePrompt("年轻东亚女性，五官清爽，性格亲和自然，适合女装种草。");
        model.setStatus("failed");
        model.setErrorMessage("图片内容可能触发安全策略，请调整提示词后重新生成");
        when(modelMapper.selectById(66L)).thenReturn(model);
        ImageGenerateResponse imageResponse = new ImageGenerateResponse();
        imageResponse.setStatus("success");
        imageResponse.setImageUrl("https://oss.example.com/regenerated-model.png");
        when(imageGenerateService.generateCommerceModelImage(any())).thenReturn(imageResponse);

        CommerceModelVO result = service.regenerateModel(66L);

        assertThat(result.getStatus()).isEqualTo("generating");
        assertThat(result.getBaseImageUrl()).isNull();
        assertThat(result.getErrorMessage()).isNull();
        verify(imageGenerateService, never()).generateCommerceModelImage(any());
        assertThat(commerceImageTasks).hasSize(1);

        commerceImageTasks.get(0).run();

        ArgumentCaptor<ImageGenerateRequest> imageRequestCaptor = ArgumentCaptor.forClass(ImageGenerateRequest.class);
        verify(imageGenerateService).generateCommerceModelImage(imageRequestCaptor.capture());
        assertThat(imageRequestCaptor.getValue().getRoleName()).isEqualTo("妹妹");
        assertThat(imageRequestCaptor.getValue().getCustomPrompt()).contains("年轻东亚女性");
        assertThat(imageRequestCaptor.getValue().getAspectRatio()).isEqualTo("16:9");
        assertThat(imageRequestCaptor.getValue().getStyleKeywords()).contains("白色微透蕾丝内衣");
        assertThat(model.getStatus()).isEqualTo("pending_review");
        assertThat(model.getBaseImageUrl()).isEqualTo("https://oss.example.com/regenerated-model.png");
        verify(modelMapper).updateById(model);
        ArgumentCaptor<UpdateWrapper<CommerceModel>> updateWrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(modelMapper).update(eq(null), updateWrapperCaptor.capture());
        assertThat(updateWrapperCaptor.getValue().getSqlSet())
                .contains("base_image_url")
                .contains("status")
                .contains("error_message")
                .contains("updated_at");
        verify(userService).deductCredits(
                eq(9L),
                eq(CreditConstants.CREDITS_PER_IMAGE),
                eq(CreditUsageType.IMAGE_GENERATION.getCode()),
                eq("电商模特重新生成-妹妹"),
                eq(66L),
                eq("COMMERCE_MODEL")
        );
    }

    @Test
    void regenerateModelRejectsGeneratingStatusBeforeDeductingCreditsOrGeneratingImage() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(66L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setName("妹妹");
        model.setGenderAge("女 / 年轻美女 / 中国");
        model.setAppearancePrompt("年轻东亚女性，五官清爽，性格亲和自然，适合女装种草。");
        model.setStatus("generating");
        when(modelMapper.selectById(66L)).thenReturn(model);

        assertThatThrownBy(() -> service.regenerateModel(66L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前模特正在生成中，请稍后再试");
        verify(userService, never()).deductCredits(anyLong(), anyInt(), anyString(), anyString(), anyLong(), anyString());
        verify(imageGenerateService, never()).generateCommerceModelImage(any());
        verify(modelMapper, never()).updateById(any());
    }

    @Test
    void regenerateLockedModelRequiresUnlockFirst() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(66L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setName("妹妹");
        model.setGenderAge("女 / 年轻美女 / 中国");
        model.setAppearancePrompt("年轻东亚女性，五官清爽，性格亲和自然，适合女装种草。");
        model.setBaseImageUrl("https://oss.example.com/old-model.png");
        model.setStatus("locked");
        when(modelMapper.selectById(66L)).thenReturn(model);

        assertThatThrownBy(() -> service.regenerateModel(66L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已锁定模特需要先解锁才能重新生成");
        verify(userService, never()).deductCredits(anyLong(), anyInt(), anyString(), anyString(), anyLong(), anyString());
        verify(modelMapper, never()).updateById(any());
    }

    @Test
    void createModelFallsBackToRecommendedSixteenNineModelRatio() {
        UserContextHolder.setUserId(9L);
        assignModelIdOnInsert(66L);
        CommerceSeries series = new CommerceSeries();
        series.setId(100L);
        series.setUserId(9L);
        series.setDefaultAspectRatio("9:16");
        when(seriesMapper.selectById(100L)).thenReturn(series);

        CommerceModelCreateRequest request = new CommerceModelCreateRequest();
        request.setName("小雅");
        request.setGender("女");
        request.setModelType("年轻美女");
        request.setEthnicity("中国");
        request.setModelAspectRatio("2:1");
        request.setAppearancePrompt("年轻东亚女性，五官清爽，性格亲和自然，适合女装种草。");
        ImageGenerateResponse imageResponse = new ImageGenerateResponse();
        imageResponse.setStatus("success");
        imageResponse.setImageUrl("https://oss.example.com/generated-model.png");
        when(imageGenerateService.generateCommerceModelImage(any())).thenReturn(imageResponse);

        service.createModel(100L, request);

        ArgumentCaptor<CommerceModel> modelCaptor = ArgumentCaptor.forClass(CommerceModel.class);
        verify(modelMapper).insert(modelCaptor.capture());
        when(modelMapper.selectById(66L)).thenReturn(modelCaptor.getValue());
        verify(imageGenerateService, never()).generateCommerceModelImage(any());
        service.startModelImageGeneration(66L);
        commerceImageTasks.get(0).run();

        ArgumentCaptor<ImageGenerateRequest> imageRequestCaptor = ArgumentCaptor.forClass(ImageGenerateRequest.class);
        verify(imageGenerateService).generateCommerceModelImage(imageRequestCaptor.capture());
        assertThat(imageRequestCaptor.getValue().getAspectRatio()).isEqualTo("16:9");
    }

    @Test
    void createModelRequiresModelNameBeforeDeductingCreditsOrGeneratingImage() {
        UserContextHolder.setUserId(9L);
        CommerceSeries series = new CommerceSeries();
        series.setId(100L);
        series.setUserId(9L);
        when(seriesMapper.selectById(100L)).thenReturn(series);

        CommerceModelCreateRequest request = new CommerceModelCreateRequest();
        request.setName("   ");
        request.setGender("女");
        request.setModelType("年轻美女");
        request.setEthnicity("中国");
        request.setAppearancePrompt("年轻中国女性，五官清爽，性格亲和自然，适合女装种草。");

        assertThatThrownBy(() -> service.createModel(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请输入模特名称");
        verify(userService, never()).deductCredits(anyLong(), anyInt(), anyString(), anyString(), anyLong(), anyString());
        verify(imageGenerateService, never()).generateCommerceModelImage(any());
    }

    @Test
    void confirmAndUnlockModelToggleReviewStateForCompletedModel() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(66L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setBaseImageUrl("https://oss.example.com/model.png");
        model.setStatus("pending_review");
        when(modelMapper.selectById(66L)).thenReturn(model);

        CommerceModelVO locked = service.confirmModel(66L);

        assertThat(locked.getStatus()).isEqualTo("locked");
        assertThat(model.getStatus()).isEqualTo("locked");

        CommerceModelVO unlocked = service.unlockModel(66L);

        assertThat(unlocked.getStatus()).isEqualTo("pending_review");
        assertThat(model.getStatus()).isEqualTo("pending_review");
        verify(modelMapper, times(2)).updateById(model);
    }

    @Test
    void getModelReturnsOwnedModelForBindingGate() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(66L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setStatus("locked");
        when(modelMapper.selectById(66L)).thenReturn(model);

        CommerceModelVO result = service.getModel(66L);

        assertThat(result.getId()).isEqualTo(66L);
        assertThat(result.getStatus()).isEqualTo("locked");
        assertThat(result.getSeriesId()).isEqualTo(100L);
    }

    @Test
    void listModelLibraryReturnsLockedModelsWithSourceSeriesAndTargetFlag() {
        UserContextHolder.setUserId(9L);
        CommerceSeries targetSeries = new CommerceSeries();
        targetSeries.setId(100L);
        targetSeries.setUserId(9L);
        targetSeries.setName("夏季女装");
        CommerceSeries sourceSeries = new CommerceSeries();
        sourceSeries.setId(200L);
        sourceSeries.setUserId(9L);
        sourceSeries.setName("春季女装");
        when(seriesMapper.selectById(100L)).thenReturn(targetSeries);
        when(seriesMapper.selectById(200L)).thenReturn(sourceSeries);

        CommerceModel libraryModel = new CommerceModel();
        libraryModel.setId(66L);
        libraryModel.setSeriesId(200L);
        libraryModel.setUserId(9L);
        libraryModel.setName("小雅");
        libraryModel.setGenderAge("女 / 年轻美女 / 中国");
        libraryModel.setBodyProfile("高挑模特");
        libraryModel.setBaseImageUrl("https://oss.example.com/model.png");
        libraryModel.setStatus("locked");

        CommerceModel currentModel = new CommerceModel();
        currentModel.setId(67L);
        currentModel.setSeriesId(100L);
        currentModel.setUserId(9L);
        currentModel.setName("小夏");
        currentModel.setGenderAge("女 / 亲和素人女性 / 中国");
        currentModel.setBodyProfile("标准匀称");
        currentModel.setBaseImageUrl("https://oss.example.com/current-model.png");
        currentModel.setStatus("locked");
        when(modelMapper.selectList(any())).thenReturn(List.of(libraryModel, currentModel));

        List<CommerceModelVO> result = service.listModelLibrary(100L);

        assertThat(result).extracting(CommerceModelVO::getId).containsExactly(66L, 67L);
        assertThat(result.get(0).getSeriesName()).isEqualTo("春季女装");
        assertThat(result.get(0).getInTargetSeries()).isFalse();
        assertThat(result.get(1).getSeriesName()).isEqualTo("夏季女装");
        assertThat(result.get(1).getInTargetSeries()).isTrue();
    }

    @Test
    void reuseModelCopiesLockedSourceIntoTargetSeriesWithoutImageGenerationOrCredits() {
        UserContextHolder.setUserId(9L);
        CommerceSeries targetSeries = new CommerceSeries();
        targetSeries.setId(100L);
        targetSeries.setUserId(9L);
        targetSeries.setName("夏季女装");
        when(seriesMapper.selectById(100L)).thenReturn(targetSeries);

        CommerceModel source = new CommerceModel();
        source.setId(66L);
        source.setSeriesId(200L);
        source.setUserId(9L);
        source.setName("小雅");
        source.setSourceType("ai");
        source.setGenderAge("女 / 年轻美女 / 中国");
        source.setBodyProfile("高挑模特");
        source.setStyleTags("真人写实, 电商带货");
        source.setSpeakingPersona("温柔亲和");
        source.setAppearancePrompt("年轻中国女性，气质自信，适合女装种草。");
        source.setBaseImageUrl("https://oss.example.com/model.png");
        source.setModelAspectRatio("16:9");
        source.setStatus("locked");
        when(modelMapper.selectById(66L)).thenReturn(source);

        CommerceModelVO result = service.reuseModelIntoSeries(100L, 66L);

        ArgumentCaptor<CommerceModel> modelCaptor = ArgumentCaptor.forClass(CommerceModel.class);
        verify(modelMapper).insert(modelCaptor.capture());
        CommerceModel saved = modelCaptor.getValue();
        assertThat(saved.getSeriesId()).isEqualTo(100L);
        assertThat(saved.getUserId()).isEqualTo(9L);
        assertThat(saved.getName()).isEqualTo("小雅");
        assertThat(saved.getSourceType()).isEqualTo("library");
        assertThat(saved.getGenderAge()).isEqualTo("女 / 年轻美女 / 中国");
        assertThat(saved.getBodyProfile()).isEqualTo("高挑模特");
        assertThat(saved.getAppearancePrompt()).isEqualTo("年轻中国女性，气质自信，适合女装种草。");
        assertThat(saved.getBaseImageUrl()).isEqualTo("https://oss.example.com/model.png");
        assertThat(saved.getModelAspectRatio()).isEqualTo("16:9");
        assertThat(saved.getStatus()).isEqualTo("locked");
        assertThat(result.getStatus()).isEqualTo("locked");
        assertThat(result.getSourceType()).isEqualTo("library");
        assertThat(commerceImageTasks).isEmpty();
        verify(imageGenerateService, never()).generateCommerceModelImage(any());
        verify(userService, never()).deductCredits(anyLong(), anyInt(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void reuseModelRejectsUnlockedSource() {
        UserContextHolder.setUserId(9L);
        CommerceSeries targetSeries = new CommerceSeries();
        targetSeries.setId(100L);
        targetSeries.setUserId(9L);
        when(seriesMapper.selectById(100L)).thenReturn(targetSeries);
        CommerceModel source = new CommerceModel();
        source.setId(66L);
        source.setSeriesId(200L);
        source.setUserId(9L);
        source.setName("小雅");
        source.setStatus("pending_review");
        source.setBaseImageUrl("https://oss.example.com/model.png");
        when(modelMapper.selectById(66L)).thenReturn(source);

        assertThatThrownBy(() -> service.reuseModelIntoSeries(100L, 66L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能沿用已锁定的模特");

        verify(modelMapper, never()).insert(any());
        verify(userService, never()).deductCredits(anyLong(), anyInt(), anyString(), anyString(), anyLong(), anyString());
        verify(imageGenerateService, never()).generateCommerceModelImage(any());
    }

    @Test
    void confirmModelRejectsGeneratingModel() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(66L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setBaseImageUrl("https://oss.example.com/model.png");
        model.setStatus("generating");
        when(modelMapper.selectById(66L)).thenReturn(model);

        assertThatThrownBy(() -> service.confirmModel(66L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有待锁定的模特可以锁定");
    }

    @Test
    void createModelRequiresConfirmedPromptForImageGeneration() {
        UserContextHolder.setUserId(9L);
        CommerceSeries series = new CommerceSeries();
        series.setId(100L);
        series.setUserId(9L);
        when(seriesMapper.selectById(100L)).thenReturn(series);

        CommerceModelCreateRequest request = new CommerceModelCreateRequest();
        request.setName("小雅");
        request.setGender("女");
        request.setModelType("年轻美女");
        request.setEthnicity("中国");

        assertThatThrownBy(() -> service.createModel(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先生成并确认模特提示词");
        verify(userService, never()).deductCredits(anyLong(), anyInt(), anyString(), anyString(), anyLong(), anyString());
        verify(imageGenerateService, never()).generateCommerceModelImage(any());
    }

    @Test
    void createModelRejectsMismatchedGenderAndModelType() {
        UserContextHolder.setUserId(9L);
        CommerceSeries series = new CommerceSeries();
        series.setId(100L);
        series.setUserId(9L);
        when(seriesMapper.selectById(100L)).thenReturn(series);

        CommerceModelCreateRequest request = new CommerceModelCreateRequest();
        request.setName("小雅");
        request.setGender("男");
        request.setModelType("年轻美女");
        request.setEthnicity("中国");

        assertThatThrownBy(() -> service.createModel(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模特类型与性别不匹配");
        verify(userService, never()).deductCredits(anyLong(), anyInt(), anyString(), anyString(), anyLong(), anyString());
        verify(imageGenerateService, never()).generateCommerceModelImage(any());
    }

    @Test
    void createProductRequiresImageAndBelongsToModel() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(200L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setStatus("locked");
        when(modelMapper.selectById(200L)).thenReturn(model);

        CommerceProductCreateRequest request = new CommerceProductCreateRequest();
        request.setName("冰丝防晒衬衫");
        CommerceProductCreateRequest.ProductImageItem mainImage = new CommerceProductCreateRequest.ProductImageItem();
        mainImage.setUrl("https://oss.example.com/product-main.png");
        mainImage.setUsage("主图");
        CommerceProductCreateRequest.ProductImageItem detailImage = new CommerceProductCreateRequest.ProductImageItem();
        detailImage.setUrl("https://oss.example.com/product-detail.png");
        detailImage.setUsage("细节图");
        CommerceProductCreateRequest.ProductImageItem wornImage = new CommerceProductCreateRequest.ProductImageItem();
        wornImage.setUrl("https://oss.example.com/product-worn.png");
        wornImage.setUsage("上身参考");
        request.setImages(List.of(detailImage, wornImage, mainImage));
        request.setProductType("上衣");
        request.setSellingPoints("凉感, 显瘦, 通勤");
        request.setTargetAudience("通勤人群");
        request.setPriceOffer("限时优惠");
        request.setTryOnRequirements("街景试穿");

        CommerceProductVO result = service.createProduct(200L, request);

        assertThat(result.getName()).isEqualTo("冰丝防晒衬衫");
        assertThat(result.getImageUrl()).isEqualTo("https://oss.example.com/product-main.png");
        assertThat(result.getImages()).extracting(CommerceProductVO.ProductImageVO::getUsage)
                .containsExactly("主图", "上身参考", "细节图");
        assertThat(result.getImages()).extracting(CommerceProductVO.ProductImageVO::getUrl)
                .containsExactly(
                        "https://oss.example.com/product-main.png",
                        "https://oss.example.com/product-worn.png",
                        "https://oss.example.com/product-detail.png");
        assertThat(result.getSeriesId()).isEqualTo(100L);
        assertThat(result.getProductType()).isEqualTo("上衣");
        assertThat(result.getSellingPoints()).isNull();
        assertThat(result.getTargetAudience()).isNull();
        assertThat(result.getPriceOffer()).isNull();
        assertThat(result.getTryOnRequirements()).isNull();
        ArgumentCaptor<CommerceProduct> productCaptor = ArgumentCaptor.forClass(CommerceProduct.class);
        verify(productMapper).insert(productCaptor.capture());
        CommerceProduct inserted = productCaptor.getValue();
        assertThat(inserted.getImageUrl()).isEqualTo("https://oss.example.com/product-main.png");
        assertThat(inserted.getImagesJson())
                .contains("\"usage\":\"主图\"")
                .contains("\"usage\":\"上身参考\"")
                .contains("\"usage\":\"细节图\"");
        assertThat(inserted.getSellingPoints()).isNull();
        assertThat(inserted.getTryOnRequirements()).isNull();
    }

    @Test
    void createProductRejectsMoreThanFiveImages() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(200L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setStatus("locked");
        when(modelMapper.selectById(200L)).thenReturn(model);

        CommerceProductCreateRequest request = new CommerceProductCreateRequest();
        request.setName("冰丝防晒衬衫");
        List<CommerceProductCreateRequest.ProductImageItem> images = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            CommerceProductCreateRequest.ProductImageItem item = new CommerceProductCreateRequest.ProductImageItem();
            item.setUrl("https://oss.example.com/product-" + i + ".png");
            item.setUsage("细节图");
            images.add(item);
        }
        request.setImages(images);

        assertThatThrownBy(() -> service.createProduct(200L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多上传5张商品图片");

        verify(productMapper, never()).insert(any());
    }

    @Test
    void createProductRejectsImageWithoutUsage() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(200L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setStatus("locked");
        when(modelMapper.selectById(200L)).thenReturn(model);

        CommerceProductCreateRequest request = new CommerceProductCreateRequest();
        request.setName("冰丝防晒衬衫");
        CommerceProductCreateRequest.ProductImageItem item = new CommerceProductCreateRequest.ProductImageItem();
        item.setUrl("https://oss.example.com/product.png");
        request.setImages(List.of(item));

        assertThatThrownBy(() -> service.createProduct(200L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请选择商品图片用途");

        verify(productMapper, never()).insert(any());
    }

    @Test
    void updateProductImagesReplacesImageGroupAndUsesNewMainImage() {
        UserContextHolder.setUserId(9L);
        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setModelId(200L);
        product.setSeriesId(100L);
        product.setUserId(9L);
        product.setName("冰丝防晒衬衫");
        product.setImageUrl("https://oss.example.com/old-main.png");
        product.setImagesJson("[{\"url\":\"https://oss.example.com/old-main.png\",\"usage\":\"主图\"}]");
        when(productMapper.selectById(300L)).thenReturn(product);

        CommerceProductCreateRequest request = new CommerceProductCreateRequest();
        CommerceProductCreateRequest.ProductImageItem detailImage = new CommerceProductCreateRequest.ProductImageItem();
        detailImage.setUrl("https://oss.example.com/new-detail.png");
        detailImage.setUsage("细节图");
        CommerceProductCreateRequest.ProductImageItem mainImage = new CommerceProductCreateRequest.ProductImageItem();
        mainImage.setUrl("https://oss.example.com/new-main.png");
        mainImage.setUsage("主图");
        CommerceProductCreateRequest.ProductImageItem wornImage = new CommerceProductCreateRequest.ProductImageItem();
        wornImage.setUrl("https://oss.example.com/new-worn.png");
        wornImage.setUsage("上身参考");
        request.setImages(List.of(detailImage, mainImage, wornImage));

        CommerceProductVO result = service.updateProductImages(300L, request);

        assertThat(result.getImageUrl()).isEqualTo("https://oss.example.com/new-main.png");
        assertThat(result.getImages()).extracting(CommerceProductVO.ProductImageVO::getUsage)
                .containsExactly("主图", "上身参考", "细节图");
        assertThat(result.getImages()).extracting(CommerceProductVO.ProductImageVO::getUrl)
                .containsExactly(
                        "https://oss.example.com/new-main.png",
                        "https://oss.example.com/new-worn.png",
                        "https://oss.example.com/new-detail.png");
        ArgumentCaptor<CommerceProduct> productCaptor = ArgumentCaptor.forClass(CommerceProduct.class);
        verify(productMapper).updateById(productCaptor.capture());
        CommerceProduct updated = productCaptor.getValue();
        assertThat(updated.getImageUrl()).isEqualTo("https://oss.example.com/new-main.png");
        assertThat(updated.getImagesJson())
                .contains("\"url\":\"https://oss.example.com/new-main.png\"")
                .contains("\"usage\":\"主图\"")
                .doesNotContain("old-main.png");
        verify(projectMapper, never()).updateById(any());
    }

    @Test
    void updateProductImagesRejectsEmptyImageGroup() {
        UserContextHolder.setUserId(9L);
        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setUserId(9L);
        product.setImageUrl("https://oss.example.com/old-main.png");
        when(productMapper.selectById(300L)).thenReturn(product);

        CommerceProductCreateRequest request = new CommerceProductCreateRequest();

        assertThatThrownBy(() -> service.updateProductImages(300L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请上传商品图片");

        verify(productMapper, never()).updateById(any());
    }

    @Test
    void uploadProductImageStoresFileInCommerceProductsOssFolder() {
        UserContextHolder.setUserId(9L);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "dress.webp",
                "image/webp",
                new byte[]{1, 2, 3}
        );
        when(ossService.uploadImage(any(byte[].class), eq("commerce/products"), eq("image/webp"), eq("webp")))
                .thenReturn("https://oss.example.com/commerce/products/dress.webp");

        String result = service.uploadProductImage(file);

        assertThat(result).isEqualTo("https://oss.example.com/commerce/products/dress.webp");
        verify(ossService).uploadImage(any(byte[].class), eq("commerce/products"), eq("image/webp"), eq("webp"));
    }

    @Test
    void uploadProductImageRejectsNonImageFiles() {
        UserContextHolder.setUserId(9L);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "product.txt",
                "text/plain",
                new byte[]{1, 2, 3}
        );

        assertThatThrownBy(() -> service.uploadProductImage(file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只支持 JPG、PNG、WEBP 格式的商品图片");
        verify(ossService, never()).uploadImage(any(byte[].class), anyString(), anyString(), anyString());
    }

    @Test
    void prepareSceneBatchCreatesGeneratingScenesWithoutStartingImages() {
        UserContextHolder.setUserId(9L);
        CommerceSeries series = new CommerceSeries();
        series.setId(100L);
        series.setUserId(9L);
        series.setName("小雅女装种草号");
        when(seriesMapper.selectById(100L)).thenReturn(series);
        List<CommerceScene> insertedScenes = new ArrayList<>();
        doAnswer(invocation -> {
            CommerceScene scene = invocation.getArgument(0);
            scene.setId(700L + insertedScenes.size());
            insertedScenes.add(scene);
            return 1;
        }).when(sceneMapper).insert(any(CommerceScene.class));
        CommerceSceneBatchCreateRequest request = new CommerceSceneBatchCreateRequest();
        request.setSceneTypes(List.of("品牌场景", "直播间背景", "街拍场景", "田间自然", "海边旅拍", "海边氛围", "艺术馆", "城市夜景"));
        request.setBrandSceneName("LUMIA Studio");
        request.setCustomScenes(List.of("咖啡店落地窗旁"));
        request.setAspectRatio("16:9");
        request.setResolution("3K");
        request.setCountPerScene(1);
        request.setTimeMood("night");
        request.setCrowdMode("ambient");
        request.setDescription("浅色女装品牌调性，干净高级");

        List<CommerceSceneVO> result = service.prepareSceneBatch(100L, request);

        assertThat(result).extracting(CommerceSceneVO::getSceneType)
                .containsExactly("品牌场景", "直播间背景", "街拍场景", "田间自然", "海边旅拍", "海边氛围", "艺术馆", "城市夜景", "自定义");
        assertThat(result).extracting(CommerceSceneVO::getName)
                .containsExactly("品牌场景", "直播间背景", "街拍场景", "田间自然", "海边旅拍", "海边氛围", "艺术馆", "城市夜景", "咖啡店落地窗旁");
        assertThat(result).extracting(CommerceSceneVO::getStatus)
                .containsOnly("generating");
        ArgumentCaptor<CommerceScene> sceneCaptor = ArgumentCaptor.forClass(CommerceScene.class);
        verify(sceneMapper, times(9)).insert(sceneCaptor.capture());
        assertThat(sceneCaptor.getAllValues()).extracting(CommerceScene::getLibraryType)
                .containsExactly(
                        "品牌场景库", "直播间背景库", "街拍场景库",
                        "自然氛围场景库", "旅拍场景库", "海边氛围场景库", "生活方式场景库", "街拍场景库",
                        "自定义场景库");
        assertThat(sceneCaptor.getAllValues()).extracting(CommerceScene::getPrompt)
                .allSatisfy(prompt -> assertThat(prompt)
                        .contains("电商服装试穿场景背景图")
                        .contains("用于后续虚拟试穿合成")
                        .contains("完整真人模特站立空间")
                        .contains("电影感")
                        .contains("情绪化光影")
                        .contains("景深效果")
                        .contains("照片级真实感")
                        .contains("电影级摄影")
                        .contains("低角度")
                        .contains("选择性浅景深")
                        .contains("前景强虚化")
                        .contains("胶片模拟质感")
                        .contains("非对称构图")
                        .contains("严格主体要求")
                        .contains("允许远景或画面边缘出现少量模糊低存在感的氛围人群")
                        .contains("夜晚氛围")
                        .contains("必须有少量")
                        .contains("虚化氛围")
                        .contains("不要出现人物、人体、模特、手脚")
                        .contains("浅色女装品牌调性"));
        assertThat(sceneCaptor.getAllValues().subList(1, 9)).extracting(CommerceScene::getPrompt)
                .allSatisfy(prompt -> assertThat(prompt).contains("无文字、无水印、无Logo"));
        assertThat(sceneCaptor.getAllValues().get(0).getPrompt())
                .contains("LUMIA Studio")
                .contains("品牌门头")
                .contains("Logo墙")
                .contains("广告牌")
                .contains("必须出现用户填写的品牌字样「LUMIA Studio」")
                .contains("品牌字样必须清晰可读")
                .contains("不要翻译、不要改写、不要改变大小写")
                .doesNotContain("不要出现品牌字样")
                .doesNotContain("无文字、无水印、无Logo");
        CommerceScene brandScene = sceneCaptor.getAllValues().get(0);
        CommerceScene liveScene = sceneCaptor.getAllValues().get(1);
        CommerceScene streetScene = sceneCaptor.getAllValues().get(2);
        CommerceScene fieldScene = sceneCaptor.getAllValues().get(3);
        CommerceScene seasideScene = sceneCaptor.getAllValues().get(4);
        CommerceScene seasideMoodScene = sceneCaptor.getAllValues().get(5);
        CommerceScene galleryScene = sceneCaptor.getAllValues().get(6);
        CommerceScene cityNightScene = sceneCaptor.getAllValues().get(7);
        CommerceScene customScene = sceneCaptor.getAllValues().get(8);
        assertThat(liveScene.getPrompt())
                .contains("室内夜晚氛围")
                .contains("低照度室内环境")
                .contains("室内灯带")
                .contains("背景氛围光")
                .contains("必须有少量顾客/工作人员轮廓")
                .doesNotContain("蓝天")
                .doesNotContain("白昼")
                .doesNotContain("湿润的沥青路面")
                .doesNotContain("交通信号灯");
        assertThat(brandScene.getPrompt())
                .contains("室内夜晚氛围")
                .contains("必须有少量顾客/工作人员轮廓")
                .doesNotContain("湿润的沥青路面")
                .doesNotContain("交通信号灯");
        assertThat(streetScene.getPrompt())
                .contains("电影感城市夜景")
                .contains("湿润的沥青路面")
                .contains("城市灯光反射")
                .contains("虚化交通信号灯")
                .contains("低照度环境")
                .contains("远处背景必须有少量路人");
        assertThat(fieldScene.getPrompt())
                .contains("田间自然场景")
                .contains("开阔麦田")
                .contains("草叶和谷穗")
                .contains("生活方式大片氛围")
                .contains("自然旅拍夜晚氛围")
                .contains("远处背景必须有少量路人")
                .doesNotContain("交通信号灯")
                .doesNotContain("沥青路面");
        assertThat(seasideScene.getPrompt())
                .contains("高级海边旅拍场景")
                .contains("海平线")
                .contains("海风流动感")
                .contains("度假大片感")
                .contains("自然旅拍夜晚氛围")
                .doesNotContain("交通信号灯")
                .doesNotContain("沥青路面");
        assertThat(seasideMoodScene.getPrompt())
                .contains("海边氛围")
                .contains("浪漫电影手法")
                .contains("海岸线")
                .contains("自由与松弛感")
                .contains("前景强虚化");
        assertThat(galleryScene.getPrompt())
                .contains("艺术馆")
                .contains("极简高级")
                .contains("低照度室内环境")
                .contains("必须有少量顾客/工作人员轮廓");
        assertThat(cityNightScene.getPrompt())
                .contains("都市夜景时装大片场景")
                .contains("霓虹")
                .contains("人物预留补光")
                .contains("远处背景必须有少量路人");
        assertThat(customScene.getPrompt())
                .contains("用户填写的词仅作为场景主题")
                .contains("生成空场景背景")
                .contains("后续可以自然放入真人模特和服装商品")
                .contains("室内夜晚氛围")
                .contains("室内灯带")
                .contains("必须有少量顾客/工作人员轮廓")
                .doesNotContain("湿润的沥青路面")
                .doesNotContain("交通信号灯")
                .contains("不要根据用户词生成正在试穿的人、衣服、商品展示架或广告牌")
                .contains("主体落位区");
        assertThat(commerceImageTasks).isEmpty();
        verify(imageGenerateService, never()).generateCommerceSceneImage(any());
    }

    @Test
    void prepareSceneBatchKeepsUrbanScenePromptConsistentWithSelectedTimeMood() {
        UserContextHolder.setUserId(9L);
        CommerceSeries series = new CommerceSeries();
        series.setId(100L);
        series.setUserId(9L);
        series.setName("小雅女装种草号");
        when(seriesMapper.selectById(100L)).thenReturn(series);
        List<CommerceScene> insertedScenes = new ArrayList<>();
        doAnswer(invocation -> {
            CommerceScene scene = invocation.getArgument(0);
            scene.setId(900L + insertedScenes.size());
            insertedScenes.add(scene);
            return 1;
        }).when(sceneMapper).insert(any(CommerceScene.class));
        CommerceSceneBatchCreateRequest request = new CommerceSceneBatchCreateRequest();
        request.setSceneTypes(List.of("街拍场景", "城市夜景"));
        request.setAspectRatio("9:16");
        request.setResolution("2K");
        request.setCountPerScene(1);
        request.setTimeMood("day");
        request.setCrowdMode("empty");

        service.prepareSceneBatch(100L, request);

        assertThat(insertedScenes).hasSize(2);
        CommerceScene streetScene = insertedScenes.get(0);
        CommerceScene cityScene = insertedScenes.get(1);
        assertThat(streetScene.getPrompt())
                .contains("日光街拍")
                .contains("明亮自然光")
                .contains("通勤街景")
                .doesNotContain("电影感城市夜景")
                .doesNotContain("湿润的沥青路面")
                .doesNotContain("城市灯光反射")
                .doesNotContain("虚化交通信号灯")
                .doesNotContain("低照度环境");
        assertThat(cityScene.getPrompt())
                .contains("白天都市大片")
                .contains("玻璃幕墙")
                .contains("干净城市广场")
                .contains("明亮自然光")
                .doesNotContain("都市夜景时装大片场景")
                .doesNotContain("霓虹")
                .doesNotContain("交通信号灯")
                .doesNotContain("低照度环境");
    }

    @Test
    void prepareSceneBatchBuildsCleanSempornaBeachPrompts() {
        UserContextHolder.setUserId(9L);
        CommerceSeries series = new CommerceSeries();
        series.setId(100L);
        series.setUserId(9L);
        series.setName("小雅女装种草号");
        when(seriesMapper.selectById(100L)).thenReturn(series);
        List<CommerceScene> insertedScenes = new ArrayList<>();
        doAnswer(invocation -> {
            CommerceScene scene = invocation.getArgument(0);
            scene.setId(960L + insertedScenes.size());
            insertedScenes.add(scene);
            return 1;
        }).when(sceneMapper).insert(any(CommerceScene.class));
        CommerceSceneBatchCreateRequest request = new CommerceSceneBatchCreateRequest();
        request.setSceneTypes(List.of("海边旅拍", "海边氛围"));
        request.setAspectRatio("9:16");
        request.setResolution("2K");
        request.setCountPerScene(1);
        request.setTimeMood("day");
        request.setCrowdMode("empty");

        service.prepareSceneBatch(100L, request);

        assertThat(insertedScenes).hasSize(2);
        CommerceScene seasideTravelScene = insertedScenes.get(0);
        CommerceScene seasideMoodScene = insertedScenes.get(1);
        assertThat(insertedScenes).extracting(CommerceScene::getPrompt)
                .allSatisfy(prompt -> assertThat(prompt)
                        .contains("仙本那")
                        .contains("果冻海")
                        .contains("奶粉沙滩")
                        .contains("蓝绿色透明浅海")
                        .contains("干净开阔")
                        .contains("海平线必须保持水平")
                        .contains("海水只能位于海岸线的一侧")
                        .contains("沙滩必须连续")
                        .contains("不要生成白沙舌、沙洲孤岛、环形浅滩、双重水线")
                        .contains("不要出现杂草、芦苇、乱草、海草前景")
                        .contains("后续模特和服装可以自然落位")
                        .doesNotContain("旷野沙丘")
                        .doesNotContain("潮湿沙滩")
                        .doesNotContain("强虚化海草")
                        .doesNotContain("可用草叶、芦苇")
                        .doesNotContain("月光"));
        assertThat(seasideTravelScene.getPrompt())
                .contains("度假旅拍动线")
                .contains("干净可行走的弧形海岸线")
                .contains("远处水上屋")
                .contains("主体落位区像真实海岛旅拍站位")
                .doesNotContain("纯净海岛氛围空镜");
        assertThat(seasideMoodScene.getPrompt())
                .contains("纯净海岛氛围空镜")
                .contains("极简海天分层")
                .contains("大片感空镜")
                .contains("主体落位区像高级海边广告片留白")
                .doesNotContain("远处水上屋");
    }

    @Test
    void prepareSceneBatchKeepsBeachPromptsConsistentWithNightAndAmbientCrowd() {
        UserContextHolder.setUserId(9L);
        CommerceSeries series = new CommerceSeries();
        series.setId(100L);
        series.setUserId(9L);
        series.setName("小雅女装种草号");
        when(seriesMapper.selectById(100L)).thenReturn(series);
        List<CommerceScene> insertedScenes = new ArrayList<>();
        doAnswer(invocation -> {
            CommerceScene scene = invocation.getArgument(0);
            scene.setId(970L + insertedScenes.size());
            insertedScenes.add(scene);
            return 1;
        }).when(sceneMapper).insert(any(CommerceScene.class));
        CommerceSceneBatchCreateRequest request = new CommerceSceneBatchCreateRequest();
        request.setSceneTypes(List.of("海边旅拍", "海边氛围"));
        request.setAspectRatio("9:16");
        request.setResolution("2K");
        request.setCountPerScene(1);
        request.setTimeMood("night");
        request.setCrowdMode("ambient");

        service.prepareSceneBatch(100L, request);

        assertThat(insertedScenes).hasSize(2);
        assertThat(insertedScenes).extracting(CommerceScene::getPrompt)
                .allSatisfy(prompt -> assertThat(prompt)
                        .contains("夜晚")
                        .contains("海岛蓝调")
                        .contains("月光")
                        .contains("少量")
                        .contains("虚化氛围")
                        .contains("低存在感")
                        .contains("允许远景或画面边缘出现少量模糊低存在感")
                        .doesNotContain("高透日光")
                        .doesNotContain("高透天空")
                        .doesNotContain("轻薄白云")
                        .doesNotContain("明亮治愈")
                        .doesNotContain("纯净海岛氛围空镜")
                        .doesNotContain("空无一人的室外环境")
                        .doesNotContain("人群氛围：无人")
                        .doesNotContain("不要生成真人、远行者、女性、模特或人物剪影"));
    }

    @Test
    void prepareSceneBatchKeepsAllSceneDetailsFreeFromConflictingTimeMoodTerms() {
        UserContextHolder.setUserId(9L);
        CommerceSeries series = new CommerceSeries();
        series.setId(100L);
        series.setUserId(9L);
        series.setName("小雅女装种草号");
        when(seriesMapper.selectById(100L)).thenReturn(series);
        List<CommerceScene> insertedScenes = new ArrayList<>();
        doAnswer(invocation -> {
            CommerceScene scene = invocation.getArgument(0);
            scene.setId(950L + insertedScenes.size());
            insertedScenes.add(scene);
            return 1;
        }).when(sceneMapper).insert(any(CommerceScene.class));
        CommerceSceneBatchCreateRequest request = new CommerceSceneBatchCreateRequest();
        request.setSceneTypes(List.of(
                "品牌场景", "直播间背景", "街拍场景", "棚拍场景", "商场试衣间", "室内全身镜",
                "田间自然", "海边旅拍", "海边氛围", "山野公路", "湖畔草地", "花房庭院", "咖啡店外",
                "复古街区", "城市夜景", "天台露台", "艺术馆", "酒店民宿", "地铁通勤",
                "校园林荫", "公园绿荫"));
        request.setAspectRatio("9:16");
        request.setResolution("2K");
        request.setCountPerScene(1);
        request.setTimeMood("day");
        request.setCrowdMode("empty");

        service.prepareSceneBatch(100L, request);

        assertThat(insertedScenes).hasSize(21);
        assertThat(insertedScenes).extracting(CommerceScene::getPrompt)
                .allSatisfy(prompt -> assertThat(prompt)
                        .doesNotContain("低照度")
                        .doesNotContain("暮色")
                        .doesNotContain("月光")
                        .doesNotContain("霓虹")
                        .doesNotContain("夜景街拍")
                        .doesNotContain("夜景或自然日光")
                        .doesNotContain("床边灯")
                        .doesNotContain("远处建筑灯光")
                        .doesNotContain("电影感城市夜景")
                        .doesNotContain("都市夜景时装大片场景")
                        .doesNotContain("虚化交通信号灯"));
    }

    @Test
    void startSceneBatchSchedulesSceneImagesConcurrentlyAndUpdatesResults() {
        UserContextHolder.setUserId(9L);
        CommerceSeries series = new CommerceSeries();
        series.setId(100L);
        series.setUserId(9L);
        series.setName("小雅女装种草号");
        when(seriesMapper.selectById(100L)).thenReturn(series);
        CommerceScene liveScene = new CommerceScene();
        liveScene.setId(700L);
        liveScene.setSeriesId(100L);
        liveScene.setUserId(9L);
        liveScene.setName("直播间背景");
        liveScene.setSceneType("直播间背景");
        liveScene.setPrompt("生成电商服装试穿场景图，场景类型：直播间背景。");
        liveScene.setAspectRatio("16:9");
        liveScene.setResolution("3K");
        liveScene.setStatus("generating");
        CommerceScene streetScene = new CommerceScene();
        streetScene.setId(701L);
        streetScene.setSeriesId(100L);
        streetScene.setUserId(9L);
        streetScene.setName("街拍场景");
        streetScene.setSceneType("街拍场景");
        streetScene.setPrompt("生成电商服装试穿场景图，场景类型：街拍场景。");
        streetScene.setAspectRatio("16:9");
        streetScene.setResolution("3K");
        streetScene.setStatus("generating");
        when(sceneMapper.selectBatchIds(List.of(700L, 701L))).thenReturn(List.of(liveScene, streetScene));
        when(sceneMapper.selectById(700L)).thenReturn(liveScene);
        when(sceneMapper.selectById(701L)).thenReturn(streetScene);
        ImageGenerateResponse imageResponse = new ImageGenerateResponse();
        imageResponse.setStatus("success");
        imageResponse.setImageUrl("https://oss.example.com/commerce/scenes/street.png");
        when(imageGenerateService.generateCommerceSceneImage(any())).thenReturn(imageResponse);

        CommerceSceneGenerationStartRequest request = new CommerceSceneGenerationStartRequest();
        request.setSceneIds(List.of(700L, 701L));

        service.startSceneBatch(100L, request);

        assertThat(commerceImageTasks).hasSize(2);

        commerceImageTasks.get(0).run();

        ArgumentCaptor<ImageGenerateRequest> imageRequestCaptor = ArgumentCaptor.forClass(ImageGenerateRequest.class);
        verify(imageGenerateService).generateCommerceSceneImage(imageRequestCaptor.capture());
        assertThat(imageRequestCaptor.getValue().getAspectRatio()).isEqualTo("16:9");
        assertThat(imageRequestCaptor.getValue().getQuality()).isEqualTo("3K");
        assertThat(imageRequestCaptor.getValue().getCustomPrompt())
                .contains("电商服装试穿场景图")
                .contains("直播间背景");
        ArgumentCaptor<UpdateWrapper<CommerceScene>> updateCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(sceneMapper).update(eq(null), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet()).contains("image_url", "status", "error_message");
        assertThat(updateCaptor.getValue().getParamNameValuePairs().values())
                .contains("https://oss.example.com/commerce/scenes/street.png", "completed");
    }

    @Test
    void listScenesReturnsSeriesScenesByUpdatedTime() {
        UserContextHolder.setUserId(9L);
        CommerceSeries series = new CommerceSeries();
        series.setId(100L);
        series.setUserId(9L);
        when(seriesMapper.selectById(100L)).thenReturn(series);

        CommerceScene scene = new CommerceScene();
        scene.setId(700L);
        scene.setSeriesId(100L);
        scene.setUserId(9L);
        scene.setName("直播间背景");
        scene.setSceneType("直播间背景");
        scene.setLibraryType("直播间背景库");
        scene.setImageUrl("https://oss.example.com/commerce/scenes/live.png");
        scene.setStatus("completed");
        when(sceneMapper.selectList(any())).thenReturn(List.of(scene));

        List<CommerceSceneVO> result = service.listScenes(100L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("直播间背景");
        assertThat(result.get(0).getLibraryType()).isEqualTo("直播间背景库");
        assertThat(result.get(0).getImageUrl()).isEqualTo("https://oss.example.com/commerce/scenes/live.png");
    }

    @Test
    void createSceneBatchRejectsUnsupportedSceneType() {
        UserContextHolder.setUserId(9L);
        CommerceSeries series = new CommerceSeries();
        series.setId(100L);
        series.setUserId(9L);
        when(seriesMapper.selectById(100L)).thenReturn(series);

        CommerceSceneBatchCreateRequest request = new CommerceSceneBatchCreateRequest();
        request.setSceneTypes(List.of("未知场景"));

        assertThatThrownBy(() -> service.prepareSceneBatch(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的场景类型");
        verify(sceneMapper, never()).insert(any());
    }

    @Test
    void createProductRequiresLockedModel() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(200L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setStatus("pending_review");
        when(modelMapper.selectById(200L)).thenReturn(model);

        CommerceProductCreateRequest request = new CommerceProductCreateRequest();
        request.setName("冰丝防晒衬衫");
        request.setImageUrl("https://oss.example.com/product.png");

        assertThatThrownBy(() -> service.createProduct(200L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先锁定模特再绑定商品");
    }

    @Test
    void createTryOnProjectBuildsFiveShotsAndUsesModelAndProductImages() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(200L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setName("小雅");
        model.setBaseImageUrl("https://oss.example.com/model.png");
        model.setStatus("locked");

        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setModelId(200L);
        product.setSeriesId(100L);
        product.setUserId(9L);
        product.setName("冰丝防晒衬衫");
        product.setImageUrl("https://oss.example.com/product.png");
        product.setImagesJson("["
                + "{\"url\":\"https://oss.example.com/product-detail.png\",\"usage\":\"细节图\"},"
                + "{\"url\":\"https://oss.example.com/product-worn.png\",\"usage\":\"上身参考\"},"
                + "{\"url\":\"https://oss.example.com/product-main.png\",\"usage\":\"主图\"}"
                + "]");
        product.setSellingPoints("凉感, 显瘦, 通勤");
        CommerceScene scene = new CommerceScene();
        scene.setId(700L);
        scene.setSeriesId(100L);
        scene.setUserId(9L);
        scene.setImageUrl("https://oss.example.com/commerce/scenes/street.png");
        scene.setStatus("completed");

        when(productMapper.selectById(300L)).thenReturn(product);
        when(modelMapper.selectById(200L)).thenReturn(model);
        when(sceneMapper.selectBatchIds(List.of(700L))).thenReturn(List.of(scene));

        CommerceProjectCreateRequest request = new CommerceProjectCreateRequest();
        request.setContentType("try_on");
        request.setAspectRatio("3:4");
        request.setSceneIds(List.of(700L));
        request.setDurationSeconds(12);
        request.setResolution("1080p");
        request.setVideoModel("seedance-2.0");

        CommerceProjectVO result = service.createProject(300L, request);

        assertThat(result.getContentType()).isEqualTo("try_on");
        assertThat(result.getAspectRatio()).isEqualTo("3:4");
        assertThat(result.getModelImageUrl()).isEqualTo("https://oss.example.com/model.png");
        assertThat(result.getProductImageUrl()).isEqualTo("https://oss.example.com/product.png");
        assertThat(result.getSceneImageUrls()).containsExactly("https://oss.example.com/commerce/scenes/street.png");
        assertThat(result.getShots()).hasSize(4);
        assertThat(result.getShots())
                .extracting(shot -> shot.getTitle())
                .containsExactly("全身站立", "转身展示", "走动展示", "细节展示");
        assertThat(result.getShots()).extracting(shot -> shot.getDurationSeconds()).containsOnly(12);
        assertThat(result.getShots()).extracting(shot -> shot.getResolution()).containsOnly("1080p");
        assertThat(result.getShots()).extracting(shot -> shot.getVideoModel()).containsOnly("seedance-2.0");
        assertThat(result.getStatus()).isEqualTo("generating");
        assertThat(result.getShots()).extracting(shot -> shot.getGenerationStatus()).containsOnly("generating");
        assertThat(result.getShots().get(0).getVisualDescription()).contains("模特").contains("商品");

        ArgumentCaptor<CommerceShot> shotCaptor = ArgumentCaptor.forClass(CommerceShot.class);
        verify(shotMapper, times(4)).insert(shotCaptor.capture());
        assertThat(shotCaptor.getAllValues())
                .extracting(CommerceShot::getTitle)
                .containsExactly("全身站立", "转身展示", "走动展示", "细节展示");
        assertThat(shotCaptor.getAllValues()).extracting(CommerceShot::getDurationSeconds).containsOnly(12);
        assertThat(shotCaptor.getAllValues()).extracting(CommerceShot::getResolution).containsOnly("1080p");
        assertThat(shotCaptor.getAllValues()).extracting(CommerceShot::getVideoModel).containsOnly("seedance-2.0");
        assertThat(shotCaptor.getAllValues()).extracting(CommerceShot::getGenerationStatus).containsOnly("generating");
    }

    @Test
    void createTryOnProjectUsesSelectedDisplayActionsAsShotScript() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(200L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setName("小雅");
        model.setBaseImageUrl("https://oss.example.com/model.png");
        model.setStatus("locked");

        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setModelId(200L);
        product.setSeriesId(100L);
        product.setUserId(9L);
        product.setName("冰丝防晒衬衫");
        product.setImageUrl("https://oss.example.com/product.png");

        CommerceScene scene = new CommerceScene();
        scene.setId(700L);
        scene.setSeriesId(100L);
        scene.setUserId(9L);
        scene.setImageUrl("https://oss.example.com/commerce/scenes/street.png");
        scene.setStatus("completed");

        when(productMapper.selectById(300L)).thenReturn(product);
        when(modelMapper.selectById(200L)).thenReturn(model);
        when(sceneMapper.selectBatchIds(List.of(700L))).thenReturn(List.of(scene));

        CommerceProjectCreateRequest request = new CommerceProjectCreateRequest();
        request.setContentType("try_on");
        request.setAspectRatio("9:16");
        request.setTryOnRequirements("展示方式：转身展示、拿包通勤、搭配展示、戴帽展示；表演风格：自信大片；动作表情：快走急停回看、回头挑眉、自信微笑");
        request.setSceneIds(List.of(700L));

        CommerceProjectVO result = service.createProject(300L, request);

        assertThat(result.getTryOnRequirements())
                .isEqualTo("展示方式：转身展示、拿包通勤、搭配展示、戴帽展示；表演风格：自信大片；动作表情：快走急停回看、回头挑眉、自信微笑");
        assertThat(result.getShots()).hasSize(4);
        assertThat(result.getShots())
                .extracting(shot -> shot.getTitle())
                .containsExactly("转身展示", "拿包通勤", "搭配展示", "戴帽展示");
        assertThat(result.getShots())
                .extracting(shot -> shot.getTitle())
                .doesNotContain("正面展示", "侧面展示", "细节特写", "场景收尾");
        assertThat(result.getShots().get(0).getVisualDescription())
                .contains("转身展示")
                .contains("动作镜头需要和前后展示方式自然衔接")
                .contains("重心转移")
                .contains("镜头推进")
                .contains("侧逆光")
                .contains("动作幅度要比普通试穿更明确")
                .contains("眼神从镜外导视点回到镜头")
                .contains("镜头轻微环绕")
                .contains("表演风格：自信大片")
                .contains("动作表情强化：快走急停回看、回头挑眉、自信微笑")
                .contains("快走斜向靠近镜头")
                .contains("稳稳急停")
                .contains("回头挑眉")
                .contains("自信微笑");
        ArgumentCaptor<CommerceProject> projectCaptor = ArgumentCaptor.forClass(CommerceProject.class);
        verify(projectMapper).insert(projectCaptor.capture());
        assertThat(projectCaptor.getValue().getTryOnRequirements())
                .isEqualTo("展示方式：转身展示、拿包通勤、搭配展示、戴帽展示；表演风格：自信大片；动作表情：快走急停回看、回头挑眉、自信微笑");
        assertThat(result.getShots().get(1).getVisualDescription())
                .contains("包从镜头外自然递入")
                .contains("不能在手中凭空出现")
                .contains("通勤大片")
                .contains("极具张力的台步");
        assertThat(result.getShots().get(2).getVisualDescription())
                .contains("搭配单品必须从镜头外自然拿取或由镜头外助手递入")
                .contains("不能突然出现在人物手中或身上")
                .contains("时尚杂志封面质感")
                .contains("大面积留白");
        assertThat(result.getShots().get(3).getVisualDescription())
                .contains("商品本身有帽子、兜帽或连帽结构")
                .contains("不能凭空新增帽子或兜帽")
                .contains("发丝边缘光")
                .contains("慢动作");

        ArgumentCaptor<CommerceShot> shotCaptor = ArgumentCaptor.forClass(CommerceShot.class);
        verify(shotMapper, times(4)).insert(shotCaptor.capture());
        assertThat(shotCaptor.getAllValues())
                .extracting(CommerceShot::getTitle)
                .containsExactly("转身展示", "拿包通勤", "搭配展示", "戴帽展示");
    }

    @Test
    void createTryOnProjectNormalizesInvalidVideoOptionsToShortDramaDefaults() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(200L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setName("小雅");
        model.setBaseImageUrl("https://oss.example.com/model.png");
        model.setStatus("locked");

        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setModelId(200L);
        product.setSeriesId(100L);
        product.setUserId(9L);
        product.setName("冰丝防晒衬衫");
        product.setImageUrl("https://oss.example.com/product.png");

        CommerceScene scene = new CommerceScene();
        scene.setId(700L);
        scene.setSeriesId(100L);
        scene.setUserId(9L);
        scene.setImageUrl("https://oss.example.com/commerce/scenes/street.png");
        scene.setStatus("completed");

        when(productMapper.selectById(300L)).thenReturn(product);
        when(modelMapper.selectById(200L)).thenReturn(model);
        when(sceneMapper.selectBatchIds(List.of(700L))).thenReturn(List.of(scene));

        CommerceProjectCreateRequest request = new CommerceProjectCreateRequest();
        request.setContentType("try_on");
        request.setAspectRatio("9:16");
        request.setSceneIds(List.of(700L));
        request.setDurationSeconds(99);
        request.setResolution("8k");
        request.setVideoModel("unknown-model");

        CommerceProjectVO result = service.createProject(300L, request);

        assertThat(result.getShots()).hasSize(4);
        assertThat(result.getShots())
                .extracting(shot -> shot.getTitle())
                .containsExactly("全身站立", "转身展示", "走动展示", "细节展示");
        assertThat(result.getShots()).extracting(shot -> shot.getDurationSeconds()).containsOnly(8);
        assertThat(result.getShots()).extracting(shot -> shot.getResolution()).containsOnly("480p");
        assertThat(result.getShots()).extracting(shot -> shot.getVideoModel()).containsOnly("seedance-2.0");
    }

    @Test
    void createTalkingProjectUsesRequestSellingSettingsInsteadOfProductRecord() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(200L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setName("小雅");
        model.setBaseImageUrl("https://oss.example.com/model.png");
        model.setStatus("locked");

        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setModelId(200L);
        product.setSeriesId(100L);
        product.setUserId(9L);
        product.setName("冰丝防晒衬衫");
        product.setImageUrl("https://oss.example.com/product.png");
        product.setSellingPoints("商品表旧卖点不应使用");
        product.setTargetAudience("商品表旧人群不应使用");

        when(productMapper.selectById(300L)).thenReturn(product);
        when(modelMapper.selectById(200L)).thenReturn(model);

        CommerceProjectCreateRequest request = new CommerceProjectCreateRequest();
        request.setContentType("talking");
        request.setAspectRatio("9:16");
        request.setSellingPoints("凉感显瘦");
        request.setTargetAudience("通勤人群");
        request.setPriceOffer("限时优惠");

        CommerceProjectVO result = service.createProject(300L, request);

        String mergedVoiceover = result.getShots().stream()
                .map(shot -> defaultString(shot.getVoiceoverText()) + "\n" + defaultString(shot.getVisualDescription()))
                .reduce("", String::concat);
        assertThat(mergedVoiceover).contains("凉感显瘦").contains("通勤人群");
        assertThat(mergedVoiceover).doesNotContain("商品表旧卖点不应使用");
        assertThat(mergedVoiceover).doesNotContain("商品表旧人群不应使用");
    }

    @Test
    void createTryOnProjectUsesRequestSceneRequirementsWithoutSellingSettings() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(200L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setName("小雅");
        model.setBaseImageUrl("https://oss.example.com/model.png");
        model.setStatus("locked");

        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setModelId(200L);
        product.setSeriesId(100L);
        product.setUserId(9L);
        product.setName("冰丝防晒衬衫");
        product.setImageUrl("https://oss.example.com/product.png");
        product.setTryOnRequirements("商品表旧试穿要求不应使用");
        CommerceScene scene = new CommerceScene();
        scene.setId(700L);
        scene.setSeriesId(100L);
        scene.setUserId(9L);
        scene.setImageUrl("https://oss.example.com/commerce/scenes/street.png");
        scene.setStatus("completed");

        when(productMapper.selectById(300L)).thenReturn(product);
        when(modelMapper.selectById(200L)).thenReturn(model);
        when(sceneMapper.selectBatchIds(List.of(700L))).thenReturn(List.of(scene));

        CommerceProjectCreateRequest request = new CommerceProjectCreateRequest();
        request.setContentType("try_on");
        request.setAspectRatio("9:16");
        request.setSellingPoints("试穿不应该用卖点");
        request.setTryOnRequirements("通勤街景、室内全身镜、户外自然光");
        request.setSceneIds(List.of(700L));

        CommerceProjectVO result = service.createProject(300L, request);

        String mergedVisuals = result.getShots().stream()
                .map(shot -> defaultString(shot.getVisualDescription()))
                .reduce("", String::concat);
        assertThat(mergedVisuals).contains("通勤街景、室内全身镜、户外自然光");
        assertThat(mergedVisuals).doesNotContain("商品表旧试穿要求不应使用");
        assertThat(mergedVisuals).doesNotContain("试穿不应该用卖点");
    }

    @Test
    void createTryOnProjectIgnoresRetiredFirstFrameSettingsAndUsesSingleScene() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(200L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setName("小雅");
        model.setBaseImageUrl("https://oss.example.com/model.png");
        model.setStatus("locked");

        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setModelId(200L);
        product.setSeriesId(100L);
        product.setUserId(9L);
        product.setName("冰丝防晒衬衫");
        product.setImageUrl("https://oss.example.com/product.png");
        product.setImagesJson("["
                + "{\"url\":\"https://oss.example.com/product-detail.png\",\"usage\":\"细节图\"},"
                + "{\"url\":\"https://oss.example.com/product-worn.png\",\"usage\":\"上身参考\"},"
                + "{\"url\":\"https://oss.example.com/product-main.png\",\"usage\":\"主图\"}"
                + "]");
        CommerceScene scene = new CommerceScene();
        scene.setId(700L);
        scene.setSeriesId(100L);
        scene.setUserId(9L);
        scene.setImageUrl("https://oss.example.com/commerce/scenes/street.png");
        scene.setStatus("completed");

        when(productMapper.selectById(300L)).thenReturn(product);
        when(modelMapper.selectById(200L)).thenReturn(model);
        when(sceneMapper.selectBatchIds(List.of(700L))).thenReturn(List.of(scene));

        CommerceProjectCreateRequest request = new CommerceProjectCreateRequest();
        request.setContentType("try_on");
        request.setAspectRatio("9:16");
        request.setTryOnRequirements("展示方式：全身站立；补充要求：柔和补光");
        request.setTryOnFirstFrameEnabled(true);
        request.setTryOnFirstFrameAspectRatio("3:4");
        request.setTryOnFirstFrameResolution("4K");
        request.setSceneIds(List.of(700L));

        CommerceProjectVO result = service.createProject(300L, request);

        ArgumentCaptor<CommerceProject> projectCaptor = ArgumentCaptor.forClass(CommerceProject.class);
        verify(projectMapper).insert(projectCaptor.capture());
        CommerceProject insertedProject = projectCaptor.getValue();
        assertThat(insertedProject.getTryOnFirstFrameEnabled()).isEqualTo(0);
        assertThat(insertedProject.getTryOnFirstFrameAspectRatio()).isNull();
        assertThat(insertedProject.getTryOnFirstFrameResolution()).isNull();
        assertThat(insertedProject.getTryOnFirstFrameStatus()).isNull();
        assertThat(insertedProject.getTryOnFirstFrameUrl()).isNull();
        assertThat(insertedProject.getTryOnFirstFrameError()).isNull();
        assertThat(insertedProject.getTryOnFirstFrameConfirmed()).isEqualTo(0);
        assertThat(insertedProject.getSceneImageUrlsJson()).contains("https://oss.example.com/commerce/scenes/street.png");
        assertThat(result.getTryOnFirstFrameEnabled()).isFalse();
        assertThat(result.getTryOnFirstFrameAspectRatio()).isNull();
        assertThat(result.getTryOnFirstFrameResolution()).isNull();
        assertThat(result.getTryOnFirstFrameConfirmed()).isFalse();
        assertThat(result.getSceneImageUrls()).containsExactly("https://oss.example.com/commerce/scenes/street.png");
        assertThat(commerceImageTasks).isEmpty();
        verify(imageGenerateService, never()).generateCommerceTryOnFirstFrame(any());
    }

    @Test
    void createTryOnProjectRejectsMultipleScenes() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(200L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setBaseImageUrl("https://oss.example.com/model.png");
        model.setStatus("locked");
        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setModelId(200L);
        product.setSeriesId(100L);
        product.setUserId(9L);
        product.setImageUrl("https://oss.example.com/product.png");

        when(productMapper.selectById(300L)).thenReturn(product);
        when(modelMapper.selectById(200L)).thenReturn(model);

        CommerceProjectCreateRequest request = new CommerceProjectCreateRequest();
        request.setContentType("try_on");
        request.setAspectRatio("9:16");
        request.setSceneIds(List.of(700L, 701L));

        assertThatThrownBy(() -> service.createProject(300L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("试穿视频只能选择1张场景图");

        verify(sceneMapper, never()).selectBatchIds(any());
    }

    @Test
    void createProjectRejectsUnlockedModel() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(200L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        model.setStatus("pending_review");
        model.setBaseImageUrl("https://oss.example.com/model.png");

        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setModelId(200L);
        product.setSeriesId(100L);
        product.setUserId(9L);
        product.setImageUrl("https://oss.example.com/product.png");

        when(productMapper.selectById(300L)).thenReturn(product);
        when(modelMapper.selectById(200L)).thenReturn(model);

        CommerceProjectCreateRequest request = new CommerceProjectCreateRequest();
        request.setContentType("talking");

        assertThatThrownBy(() -> service.createProject(300L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先审核并锁定模特");
    }

    @Test
    void generateProjectVideoSubmitsModelProductImageGroupAndOnlyOneSceneReferenceImage() {
        keepProductReferencesUnchanged();
        UserContextHolder.setUserId(9L);
        CommerceProject project = new CommerceProject();
        project.setId(400L);
        project.setUserId(9L);
        project.setProductId(300L);
        project.setAspectRatio("3:4");
        project.setContentType("try_on");
        project.setModelImageUrl("https://oss.example.com/model.png");
        project.setProductImageUrl("https://oss.example.com/product.png");
        project.setSceneImageUrlsJson("["
                + "\"https://oss.example.com/commerce/scenes/street.png\","
                + "\"https://oss.example.com/commerce/scenes/legacy-extra.png\""
                + "]");
        project.setStatus("draft");
        when(projectMapper.selectById(400L)).thenReturn(project);
        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setUserId(9L);
        product.setImageUrl("https://oss.example.com/product.png");
        product.setImagesJson("["
                + "{\"url\":\"https://oss.example.com/product-detail-1.png\",\"usage\":\"细节图\"},"
                + "{\"url\":\"https://oss.example.com/product-worn.png\",\"usage\":\"上身参考\"},"
                + "{\"url\":\"https://oss.example.com/product-main.png\",\"usage\":\"主图\"},"
                + "{\"url\":\"https://oss.example.com/product-detail-2.png\",\"usage\":\"细节图\"}"
                + "]");
        when(productMapper.selectById(300L)).thenReturn(product);

        CommerceShot shot = new CommerceShot();
        shot.setId(500L);
        shot.setProjectId(400L);
        shot.setSortOrder(1);
        shot.setTitle("正面展示");
        shot.setVisualDescription("模特穿上商品正面展示");
        shot.setVoiceoverText("先看正面");
        shot.setDurationSeconds(12);
        shot.setResolution("1080p");
        shot.setVideoModel("seedance-2.0");
        shot.setGenerationStatus("draft");
        when(shotMapper.selectList(any())).thenReturn(List.of(shot));

        SeedanceResponse response = new SeedanceResponse();
        response.setStatus("completed");
        response.setVideoUrl("https://oss.example.com/video.mp4");
        when(seedanceService.generateVideo(any(SeedanceRequest.class))).thenReturn(response);

        CommerceProjectVO result = service.generateProjectVideo(400L);

        assertThat(result.getStatus()).isEqualTo("completed");
        ArgumentCaptor<SeedanceRequest> requestCaptor = ArgumentCaptor.forClass(SeedanceRequest.class);
        verify(seedanceService).generateVideo(requestCaptor.capture());
        SeedanceRequest request = requestCaptor.getValue();
        assertThat(request.getRatio()).isEqualTo("3:4");
        assertThat(request.getDuration()).isEqualTo(12);
        assertThat(request.getResolution()).isEqualTo("1080p");
        assertThat(request.getModel()).isEqualTo("doubao-seedance-2-0-260128");
        assertThat(request.getWidth()).isEqualTo(1248);
        assertThat(request.getHeight()).isEqualTo(1664);
        assertThat(request.getContents()).hasSize(6);
        assertThat(request.getContents().get(0).getImageUrl().getUrl()).isEqualTo("https://oss.example.com/model.png");
        assertThat(request.getContents().get(1).getImageUrl().getUrl()).isEqualTo("https://oss.example.com/product-main.png");
        assertThat(request.getContents().get(2).getImageUrl().getUrl()).isEqualTo("https://oss.example.com/product-worn.png");
        assertThat(request.getContents().get(3).getImageUrl().getUrl()).isEqualTo("https://oss.example.com/product-detail-1.png");
        assertThat(request.getContents().get(4).getImageUrl().getUrl()).isEqualTo("https://oss.example.com/product-detail-2.png");
        assertThat(request.getContents().get(5).getImageUrl().getUrl()).isEqualTo("https://oss.example.com/commerce/scenes/street.png");
        assertThat(request.getContents())
                .extracting(content -> content.getImageUrl().getUrl())
                .doesNotContain("https://oss.example.com/commerce/scenes/legacy-extra.png");
        assertThat(request.getPrompt())
                .contains("图1是当前商品绑定的模特基础参考图")
                .contains("图2是商品主图，用于确定商品整体款式、主色、版型、轮廓和主要穿着效果。")
                .contains("图3是商品上身参考，用于确定真实穿着状态、松紧程度、垂坠感、衣长袖长、肩线腰线和人体贴合关系。")
                .contains("图4是商品细节图，用于补充面料纹理、图案、纽扣、领口、袖口、腰线、下摆、拼接、装饰和局部工艺。")
                .contains("图5是商品细节图，用于补充面料纹理、图案、纽扣、领口、袖口、腰线、下摆、拼接、装饰和局部工艺。")
                .contains("图6是用户选中的试穿场景图，用于确定空间环境、光线、背景风格和画面氛围。")
                .contains("图6是唯一场景背景基准")
                .contains("不得替换成其他地点、其他天气、其他时间段或棚拍背景")
                .contains("人物必须真实站在图6的地面、透视、光线和环境层次中")
                .contains("如果图6是海边，就必须保留海岸、沙滩或海面等海边空间特征")
                .contains("通用电影感词只用于强化图6已有的光影和氛围")
                .contains("本次共提供4张商品参考图，必须按上述图号和用途综合利用。")
                .doesNotContain("接下来的 1 到 5 张参考图")
                .doesNotContain("最后 1 张参考图")
                .doesNotContain("最多会提供 5 张商品图片")
                .contains("导演式画面目标")
                .contains("不是普通平铺的商品展示")
                .contains("参考场景图的空间气质")
                .contains("拒绝廉价摆拍")
                .contains("禁止站桩式动作")
                .contains("王家卫式都市情绪大片")
                .contains("欧美 Vogue 杂志高冷秀场风")
                .contains("法式电影慵懒浪漫风")
                .contains("根据场景图自动选择动作气质")
                .contains("场景是城市夜景或街拍")
                .contains("场景是棚拍或极简空间")
                .contains("场景是咖啡店、花房、酒店民宿")
                .contains("场景是海边、田间、山野、公园")
                .contains("场景参考优先级高于通用风格词")
                .contains("不得因为通用风格词把海边改成城市夜景、棚拍、室内或其他不在场景图里的空间")
                .contains("镜头从脚部向上扫描")
                .contains("重心转移")
                .contains("发丝飞舞")
                .contains("裙摆摆动")
                .contains("眼神接触")
                .contains("轮廓光")
                .contains("霓虹反光")
                .contains("闪光灯直打")
                .contains("电影级布光")
                .contains("胶片颗粒")
                .contains("浅景深")
                .contains("光影弥散")
                .contains("材质反光")
                .contains("情绪叙事")
                .contains("自然抓拍感")
                .contains("每个动作都必须有明确的景别")
                .contains("特写、中景、低角度仰拍、镜头推进")
                .contains("每个动作都必须有身体物理参数")
                .contains("不要只是站着、转身、走两步")
                .contains("不要把背景虚化成无法识别的糊片")
                .contains("全身站立：完整展示头到脚的穿着效果")
                .contains("整理衣摆：人物自然整理衣摆、腰头或下摆")
                .contains("抬手展示：抬手幅度要清楚")
                .contains("拿包通勤：通勤包或极简手袋必须有明确入画来源")
                .contains("包从镜头外递入")
                .contains("不能从人物手中凭空出现")
                .contains("搭配展示：搭配单品必须从镜头外自然拿取或由镜头外助手递入")
                .contains("不能突然出现在人物手中或身上")
                .contains("戴帽展示：仅当商品本身有帽子、兜帽或连帽结构")
                .contains("如果商品没有帽子结构，不能凭空新增帽子或兜帽")
                .contains("动作镜头必须连贯")
                .contains("从上一个动作自然过渡到下一个动作")
                .contains("不要出现硬切跳变、瞬间换姿势或镜头断层")
                .contains("动作幅度比普通试穿更明显")
                .contains("表演气质要自信、大方、个性张扬")
                .contains("可以出现轻跑、快走、旋身、甩发、开朗笑容、爽朗大笑")
                .contains("不要扭扭捏捏、害羞拘谨、小心翼翼")
                .contains("可以从快走自然过渡到轻跑两三步")
                .contains("笑容要自然明亮，可以有自信微笑、开朗笑起来或爽朗大笑")
                .contains("眼神互动节奏")
                .contains("不要从头到尾盯着远方")
                .contains("推近、拉远、低角度仰拍、侧跟拍、轻微环绕")
                .contains("每个动作至少一次景别变化或视角变化")
                .contains("镜头不能像固定监控机位")
                .contains("眼神必须自然有焦点")
                .contains("轻微眨眼")
                .contains("微表情")
                .contains("合适的纯音乐BGM")
                .contains("不要口播")
                .contains("不要人声讲解")
                .doesNotContain("商品图作为角落参考卡片");
        assertThat(request.getPrompt()).doesNotContain("口播：");
        assertThat(request.getGenerateAudio()).isTrue();
        verify(userService).deductCredits(
                eq(9L),
                eq(CreditConstants.calculateCredits("1080p", 12, "seedance-2.0")),
                eq(CreditUsageType.VIDEO_GENERATION.getCode()),
                eq("电商带货视频生成-项目400"),
                eq(400L),
                eq("COMMERCE_PROJECT")
        );
    }

    @Test
    void generateProjectVideoMarksPartialCompletedWhenSomeShotsReturnVideos() {
        keepProductReferencesUnchanged();
        UserContextHolder.setUserId(9L);
        CommerceProject project = new CommerceProject();
        project.setId(400L);
        project.setUserId(9L);
        project.setProductId(300L);
        project.setAspectRatio("9:16");
        project.setContentType("try_on");
        project.setModelImageUrl("https://oss.example.com/model.png");
        project.setProductImageUrl("https://oss.example.com/product.png");
        project.setSceneImageUrlsJson("[\"https://oss.example.com/commerce/scenes/street.png\"]");
        project.setStatus("generating");
        when(projectMapper.selectById(400L)).thenReturn(project);

        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setUserId(9L);
        product.setImageUrl("https://oss.example.com/product.png");
        when(productMapper.selectById(300L)).thenReturn(product);

        CommerceShot completedShot = new CommerceShot();
        completedShot.setId(500L);
        completedShot.setProjectId(400L);
        completedShot.setSortOrder(1);
        completedShot.setTitle("转身展示");
        completedShot.setVisualDescription("模特穿上商品转身展示");
        completedShot.setDurationSeconds(8);
        completedShot.setResolution("480p");
        completedShot.setVideoModel("seedance-2.0");
        completedShot.setGenerationStatus("draft");

        CommerceShot failedShot = new CommerceShot();
        failedShot.setId(501L);
        failedShot.setProjectId(400L);
        failedShot.setSortOrder(2);
        failedShot.setTitle("走动展示");
        failedShot.setVisualDescription("模特穿上商品走动展示");
        failedShot.setDurationSeconds(8);
        failedShot.setResolution("480p");
        failedShot.setVideoModel("seedance-2.0");
        failedShot.setGenerationStatus("draft");
        when(shotMapper.selectList(any())).thenReturn(List.of(completedShot, failedShot));

        SeedanceResponse completedResponse = new SeedanceResponse();
        completedResponse.setStatus("completed");
        completedResponse.setVideoUrl("https://oss.example.com/turn.mp4");
        SeedanceResponse failedResponse = new SeedanceResponse();
        failedResponse.setStatus("failed");
        failedResponse.setErrorMessage("输入或生成内容可能涉及版权限制，请更换素材或调整描述后重试。");
        when(seedanceService.generateVideo(any(SeedanceRequest.class)))
                .thenReturn(completedResponse)
                .thenReturn(failedResponse);

        CommerceProjectVO result = service.generateProjectVideo(400L);

        assertThat(result.getStatus()).isEqualTo("partial_completed");
        assertThat(result.getErrorMessage()).contains("走动展示").contains("版权限制");
        assertThat(result.getShots()).extracting(shot -> shot.getGenerationStatus())
                .containsExactly("completed", "failed");
        assertThat(result.getShots()).extracting(shot -> shot.getVideoUrl())
                .containsExactly("https://oss.example.com/turn.mp4", null);

        ArgumentCaptor<CommerceProject> projectCaptor = ArgumentCaptor.forClass(CommerceProject.class);
        verify(projectMapper).updateById(projectCaptor.capture());
        assertThat(projectCaptor.getValue().getStatus()).isEqualTo("partial_completed");
        assertThat(projectCaptor.getValue().getErrorMessage()).contains("走动展示").contains("版权限制");
    }

    @Test
    void generateTryOnProjectVideoUsesVolcengineVipModel() {
        keepProductReferencesUnchanged();
        UserContextHolder.setUserId(9L);
        CommerceProject project = new CommerceProject();
        project.setId(400L);
        project.setUserId(9L);
        project.setProductId(300L);
        project.setAspectRatio("9:16");
        project.setContentType("try_on");
        project.setModelImageUrl("https://oss.example.com/model.png");
        project.setProductImageUrl("https://oss.example.com/product.png");
        project.setSceneImageUrlsJson("[\"https://oss.example.com/commerce/scenes/street.png\"]");
        project.setStatus("draft");
        when(projectMapper.selectById(400L)).thenReturn(project);

        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setUserId(9L);
        product.setImageUrl("https://oss.example.com/product.png");
        when(productMapper.selectById(300L)).thenReturn(product);

        CommerceShot shot = new CommerceShot();
        shot.setId(500L);
        shot.setProjectId(400L);
        shot.setSortOrder(1);
        shot.setTitle("全身站立");
        shot.setVisualDescription("模特穿上商品全身站立展示");
        shot.setDurationSeconds(8);
        shot.setResolution("480p");
        shot.setVideoModel("seedance-2.0");
        shot.setGenerationStatus("draft");
        when(shotMapper.selectList(any())).thenReturn(List.of(shot));

        SeedanceResponse response = new SeedanceResponse();
        response.setStatus("completed");
        response.setVideoUrl("https://oss.example.com/video.mp4");
        when(seedanceService.generateVideo(any(SeedanceRequest.class))).thenReturn(response);

        service.generateProjectVideo(400L);

        ArgumentCaptor<SeedanceRequest> requestCaptor = ArgumentCaptor.forClass(SeedanceRequest.class);
        verify(seedanceService).generateVideo(requestCaptor.capture());
        SeedanceRequest request = requestCaptor.getValue();
        assertThat(request.getResolution()).isEqualTo("480p");
        assertThat(request.getModel()).isEqualTo("doubao-seedance-2-0-260128");
    }

    @Test
    void generateTryOnProjectVideoSubmitsSanitizedProductReferences() {
        UserContextHolder.setUserId(9L);
        CommerceProject project = new CommerceProject();
        project.setId(400L);
        project.setUserId(9L);
        project.setProductId(300L);
        project.setAspectRatio("9:16");
        project.setContentType("try_on");
        project.setModelImageUrl("https://oss.example.com/model.png");
        project.setProductImageUrl("https://oss.example.com/original-main.png");
        project.setSceneImageUrlsJson("[\"https://oss.example.com/commerce/scenes/street.png\"]");
        project.setStatus("draft");
        when(projectMapper.selectById(400L)).thenReturn(project);

        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setUserId(9L);
        product.setImageUrl("https://oss.example.com/original-main.png");
        product.setImagesJson("["
                + "{\"url\":\"https://oss.example.com/original-main.png\",\"usage\":\"主图\"},"
                + "{\"url\":\"https://oss.example.com/original-worn.png\",\"usage\":\"上身参考\"}"
                + "]");
        when(productMapper.selectById(300L)).thenReturn(product);

        CommerceProductCreateRequest.ProductImageItem safeMain = new CommerceProductCreateRequest.ProductImageItem();
        safeMain.setUsage("主图");
        safeMain.setUrl("https://oss.example.com/safe-main.png");
        CommerceProductCreateRequest.ProductImageItem safeWorn = new CommerceProductCreateRequest.ProductImageItem();
        safeWorn.setUsage("上身参考");
        safeWorn.setUrl("https://oss.example.com/safe-worn.png");
        when(productImagePrivacyService.sanitizeProductReferences(any())).thenReturn(List.of(safeMain, safeWorn));

        CommerceShot shot = new CommerceShot();
        shot.setId(500L);
        shot.setProjectId(400L);
        shot.setSortOrder(1);
        shot.setTitle("全身站立");
        shot.setVisualDescription("模特穿上商品全身站立展示");
        shot.setDurationSeconds(8);
        shot.setResolution("480p");
        shot.setVideoModel("seedance-2.0");
        shot.setGenerationStatus("draft");
        when(shotMapper.selectList(any())).thenReturn(List.of(shot));

        SeedanceResponse response = new SeedanceResponse();
        response.setStatus("completed");
        response.setVideoUrl("https://oss.example.com/video.mp4");
        when(seedanceService.generateVideo(any(SeedanceRequest.class))).thenReturn(response);

        service.generateProjectVideo(400L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommerceProductCreateRequest.ProductImageItem>> referencesCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(productImagePrivacyService).sanitizeProductReferences(referencesCaptor.capture());
        assertThat(referencesCaptor.getValue())
                .extracting(CommerceProductCreateRequest.ProductImageItem::getUrl)
                .containsExactly("https://oss.example.com/original-main.png", "https://oss.example.com/original-worn.png");

        ArgumentCaptor<SeedanceRequest> requestCaptor = ArgumentCaptor.forClass(SeedanceRequest.class);
        verify(seedanceService).generateVideo(requestCaptor.capture());
        SeedanceRequest request = requestCaptor.getValue();
        assertThat(request.getContents()).hasSize(4);
        assertThat(request.getContents())
                .extracting(content -> content.getImageUrl().getUrl())
                .containsExactly(
                        "https://oss.example.com/model.png",
                        "https://oss.example.com/safe-main.png",
                        "https://oss.example.com/safe-worn.png",
                        "https://oss.example.com/commerce/scenes/street.png"
                );
        assertThat(request.getContents())
                .extracting(content -> content.getImageUrl().getUrl())
                .doesNotContain("https://oss.example.com/original-main.png", "https://oss.example.com/original-worn.png");
        assertThat(request.getPrompt())
                .contains("图2是商品主图")
                .contains("图3是商品上身参考");
    }

    @Test
    void listProductProjectsReturnsTryOnProjectsWithShotsNewestFirst() {
        UserContextHolder.setUserId(9L);
        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setUserId(9L);
        when(productMapper.selectById(300L)).thenReturn(product);

        CommerceProject olderTalking = new CommerceProject();
        olderTalking.setId(398L);
        olderTalking.setUserId(9L);
        olderTalking.setProductId(300L);
        olderTalking.setContentType("talking");
        olderTalking.setStatus("completed");

        CommerceProject newestTryOn = new CommerceProject();
        newestTryOn.setId(400L);
        newestTryOn.setUserId(9L);
        newestTryOn.setProductId(300L);
        newestTryOn.setContentType("try_on");
        newestTryOn.setAspectRatio("9:16");
        newestTryOn.setModelImageUrl("https://oss.example.com/model.png");
        newestTryOn.setProductImageUrl("https://oss.example.com/product.png");
        newestTryOn.setSceneImageUrlsJson("[\"https://oss.example.com/commerce/scenes/street.png\"]");
        newestTryOn.setStatus("generating");

        CommerceProject olderTryOn = new CommerceProject();
        olderTryOn.setId(399L);
        olderTryOn.setUserId(9L);
        olderTryOn.setProductId(300L);
        olderTryOn.setContentType("try_on");
        olderTryOn.setAspectRatio("3:4");
        olderTryOn.setModelImageUrl("https://oss.example.com/model.png");
        olderTryOn.setProductImageUrl("https://oss.example.com/product.png");
        olderTryOn.setStatus("completed");

        when(projectMapper.selectList(any())).thenReturn(List.of(newestTryOn, olderTryOn));

        CommerceShot generatingShot = new CommerceShot();
        generatingShot.setId(500L);
        generatingShot.setProjectId(400L);
        generatingShot.setSortOrder(1);
        generatingShot.setTitle("正面展示");
        generatingShot.setGenerationStatus("generating");

        CommerceShot completedShot = new CommerceShot();
        completedShot.setId(501L);
        completedShot.setProjectId(399L);
        completedShot.setSortOrder(1);
        completedShot.setTitle("正面展示");
        completedShot.setVideoUrl("https://oss.example.com/video.mp4");
        completedShot.setGenerationStatus("completed");

        when(shotMapper.selectList(any()))
                .thenReturn(List.of(generatingShot))
                .thenReturn(List.of(completedShot));

        List<CommerceProjectVO> result = service.listProductProjects(300L, "try_on");

        assertThat(result).extracting(CommerceProjectVO::getId).containsExactly(400L, 399L);
        assertThat(result).extracting(CommerceProjectVO::getContentType).containsOnly("try_on");
        assertThat(result.get(0).getShots()).extracting(shot -> shot.getGenerationStatus()).containsExactly("generating");
        assertThat(result.get(1).getShots()).extracting(shot -> shot.getVideoUrl()).containsExactly("https://oss.example.com/video.mp4");
    }

    @Test
    void listProductProjectsNormalizesFailedProjectWithCompletedShotAsPartialCompleted() {
        UserContextHolder.setUserId(9L);
        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setUserId(9L);
        when(productMapper.selectById(300L)).thenReturn(product);

        CommerceProject project = new CommerceProject();
        project.setId(400L);
        project.setUserId(9L);
        project.setProductId(300L);
        project.setContentType("try_on");
        project.setStatus("failed");
        project.setErrorMessage("输入或生成内容可能涉及版权限制，请更换素材或调整描述后重试。");
        when(projectMapper.selectList(any())).thenReturn(List.of(project));

        CommerceShot completedShot = new CommerceShot();
        completedShot.setId(500L);
        completedShot.setProjectId(400L);
        completedShot.setSortOrder(1);
        completedShot.setTitle("转身展示");
        completedShot.setVideoUrl("https://oss.example.com/turn.mp4");
        completedShot.setGenerationStatus("completed");

        CommerceShot failedShot = new CommerceShot();
        failedShot.setId(501L);
        failedShot.setProjectId(400L);
        failedShot.setSortOrder(2);
        failedShot.setTitle("走动展示");
        failedShot.setGenerationStatus("failed");
        failedShot.setErrorMessage("输入或生成内容可能涉及版权限制，请更换素材或调整描述后重试。");
        when(shotMapper.selectList(any())).thenReturn(List.of(completedShot, failedShot));

        List<CommerceProjectVO> result = service.listProductProjects(300L, "try_on");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("partial_completed");
        assertThat(result.get(0).getErrorMessage()).contains("走动展示").contains("版权限制");

        ArgumentCaptor<CommerceProject> projectCaptor = ArgumentCaptor.forClass(CommerceProject.class);
        verify(projectMapper).updateById(projectCaptor.capture());
        assertThat(projectCaptor.getValue().getStatus()).isEqualTo("partial_completed");
    }

    @Test
    void startProjectVideoGenerationQueuesBackgroundTaskWithoutCallingSeedanceImmediately() {
        UserContextHolder.setUserId(9L);
        CommerceProject project = new CommerceProject();
        project.setId(400L);
        project.setUserId(9L);
        project.setProductId(300L);
        project.setAspectRatio("3:4");
        project.setContentType("try_on");
        project.setModelImageUrl("https://oss.example.com/model.png");
        project.setProductImageUrl("https://oss.example.com/product.png");
        project.setSceneImageUrlsJson("[\"https://oss.example.com/commerce/scenes/street.png\"]");
        project.setStatus("generating");
        when(projectMapper.selectById(400L)).thenReturn(project);

        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setUserId(9L);
        product.setImageUrl("https://oss.example.com/product.png");
        when(productMapper.selectById(300L)).thenReturn(product);

        CommerceShot shot = new CommerceShot();
        shot.setId(500L);
        shot.setProjectId(400L);
        shot.setSortOrder(1);
        shot.setTitle("正面展示");
        shot.setVisualDescription("模特穿上商品正面展示");
        shot.setVoiceoverText("先看正面");
        shot.setDurationSeconds(8);
        shot.setResolution("480p");
        shot.setVideoModel("seedance-2.0");
        shot.setGenerationStatus("generating");
        when(shotMapper.selectList(any())).thenReturn(List.of(shot));

        SeedanceResponse response = new SeedanceResponse();
        response.setStatus("completed");
        response.setVideoUrl("https://oss.example.com/video.mp4");
        when(seedanceService.generateVideo(any(SeedanceRequest.class))).thenReturn(response);

        CommerceProjectVO queued = service.startProjectVideoGeneration(400L);

        assertThat(queued.getStatus()).isEqualTo("generating");
        assertThat(commerceVideoTasks).hasSize(1);
        verify(seedanceService, never()).generateVideo(any());

        UserContextHolder.clear();
        commerceVideoTasks.get(0).run();

        verify(seedanceService).generateVideo(any(SeedanceRequest.class));
        verify(projectMapper).updateById(project);
        assertThat(project.getStatus()).isEqualTo("completed");
        assertThat(shot.getGenerationStatus()).isEqualTo("completed");
        assertThat(shot.getVideoUrl()).isEqualTo("https://oss.example.com/video.mp4");
    }

    @Test
    void startProjectVideoGenerationRebuildsLegacyTryOnShotsFromSelectedActions() {
        UserContextHolder.setUserId(9L);
        CommerceProject project = new CommerceProject();
        project.setId(400L);
        project.setUserId(9L);
        project.setProductId(300L);
        project.setModelId(200L);
        project.setAspectRatio("9:16");
        project.setContentType("try_on");
        project.setModelImageUrl("https://oss.example.com/model.png");
        project.setProductImageUrl("https://oss.example.com/product.png");
        project.setSceneImageUrlsJson("[\"https://oss.example.com/commerce/scenes/street.png\"]");
        project.setStatus("generating");

        CommerceModel model = new CommerceModel();
        model.setId(200L);
        model.setUserId(9L);
        model.setName("小雅");
        model.setBaseImageUrl("https://oss.example.com/model.png");
        model.setStatus("locked");

        CommerceProduct product = new CommerceProduct();
        product.setId(300L);
        product.setUserId(9L);
        product.setModelId(200L);
        product.setName("冰丝防晒衬衫");
        product.setImageUrl("https://oss.example.com/product.png");

        CommerceShot legacyFront = new CommerceShot();
        legacyFront.setId(500L);
        legacyFront.setProjectId(400L);
        legacyFront.setSortOrder(1);
        legacyFront.setTitle("正面展示");
        legacyFront.setVisualDescription("展示要求：展示方式：转身展示、走动展示、拿包通勤。");
        legacyFront.setDurationSeconds(8);
        legacyFront.setResolution("480p");
        legacyFront.setVideoModel("seedance-2.0");
        legacyFront.setGenerationStatus("generating");

        CommerceShot legacyWalk = new CommerceShot();
        legacyWalk.setId(501L);
        legacyWalk.setProjectId(400L);
        legacyWalk.setSortOrder(2);
        legacyWalk.setTitle("走动展示");
        legacyWalk.setVisualDescription("展示要求：展示方式：转身展示、走动展示、拿包通勤。");
        legacyWalk.setDurationSeconds(8);
        legacyWalk.setResolution("480p");
        legacyWalk.setVideoModel("seedance-2.0");
        legacyWalk.setGenerationStatus("generating");

        CommerceShot legacySide = new CommerceShot();
        legacySide.setId(502L);
        legacySide.setProjectId(400L);
        legacySide.setSortOrder(3);
        legacySide.setTitle("侧面展示");
        legacySide.setVisualDescription("展示要求：展示方式：转身展示、走动展示、拿包通勤。");
        legacySide.setDurationSeconds(8);
        legacySide.setResolution("480p");
        legacySide.setVideoModel("seedance-2.0");
        legacySide.setGenerationStatus("generating");

        CommerceShot legacyDetail = new CommerceShot();
        legacyDetail.setId(503L);
        legacyDetail.setProjectId(400L);
        legacyDetail.setSortOrder(4);
        legacyDetail.setTitle("细节特写");
        legacyDetail.setVisualDescription("展示要求：展示方式：转身展示、走动展示、拿包通勤。");
        legacyDetail.setDurationSeconds(8);
        legacyDetail.setResolution("480p");
        legacyDetail.setVideoModel("seedance-2.0");
        legacyDetail.setGenerationStatus("generating");

        CommerceShot legacyEnding = new CommerceShot();
        legacyEnding.setId(504L);
        legacyEnding.setProjectId(400L);
        legacyEnding.setSortOrder(5);
        legacyEnding.setTitle("场景收尾");
        legacyEnding.setVisualDescription("展示要求：展示方式：转身展示、走动展示、拿包通勤。");
        legacyEnding.setDurationSeconds(8);
        legacyEnding.setResolution("480p");
        legacyEnding.setVideoModel("seedance-2.0");
        legacyEnding.setGenerationStatus("generating");

        when(projectMapper.selectById(400L)).thenReturn(project);
        when(productMapper.selectById(300L)).thenReturn(product);
        when(modelMapper.selectById(200L)).thenReturn(model);
        when(shotMapper.selectList(any()))
                .thenReturn(List.of(legacyFront, legacyWalk, legacySide, legacyDetail, legacyEnding))
                .thenReturn(List.of(legacyFront, legacyWalk, legacySide, legacyDetail, legacyEnding))
                .thenReturn(List.of(legacyFront, legacyWalk, legacySide, legacyDetail, legacyEnding));

        CommerceProjectVO queued = service.startProjectVideoGeneration(400L);

        assertThat(queued.getShots())
                .extracting(shot -> shot.getTitle())
                .containsExactly("转身展示", "走动展示", "拿包通勤");
        verify(shotMapper, times(5)).deleteById(anyLong());
        ArgumentCaptor<CommerceShot> shotCaptor = ArgumentCaptor.forClass(CommerceShot.class);
        verify(shotMapper, times(3)).insert(shotCaptor.capture());
        assertThat(shotCaptor.getAllValues())
                .extracting(CommerceShot::getTitle)
                .containsExactly("转身展示", "走动展示", "拿包通勤");
        assertThat(commerceVideoTasks).hasSize(1);
        verify(seedanceService, never()).generateVideo(any());
    }

    private void assignModelIdOnInsert(Long id) {
        doAnswer(invocation -> {
            CommerceModel model = invocation.getArgument(0);
            model.setId(id);
            return 1;
        }).when(modelMapper).insert(any(CommerceModel.class));
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
