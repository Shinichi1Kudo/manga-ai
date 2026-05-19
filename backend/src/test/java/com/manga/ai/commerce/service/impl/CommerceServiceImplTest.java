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
import com.manga.ai.commerce.entity.CommerceModel;
import com.manga.ai.commerce.entity.CommerceProduct;
import com.manga.ai.commerce.entity.CommerceProject;
import com.manga.ai.commerce.entity.CommerceSeries;
import com.manga.ai.commerce.entity.CommerceShot;
import com.manga.ai.commerce.mapper.CommerceModelMapper;
import com.manga.ai.commerce.mapper.CommerceProductMapper;
import com.manga.ai.commerce.mapper.CommerceProjectMapper;
import com.manga.ai.commerce.mapper.CommerceSeriesMapper;
import com.manga.ai.commerce.mapper.CommerceShotMapper;
import com.manga.ai.commerce.service.CommerceModelProfileService;
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
    private final CommerceShotMapper shotMapper = mock(CommerceShotMapper.class);
    private final SeedanceService seedanceService = mock(SeedanceService.class);
    private final UserService userService = mock(UserService.class);
    private final CommerceModelProfileService modelProfileService = mock(CommerceModelProfileService.class);
    private final ImageGenerateService imageGenerateService = mock(ImageGenerateService.class);
    private final OssService ossService = mock(OssService.class);
    private final List<Runnable> commerceImageTasks = new ArrayList<>();
    private final Executor commerceImageExecutor = commerceImageTasks::add;
    private final CommerceServiceImpl service = new CommerceServiceImpl(
            seriesMapper,
            modelMapper,
            productMapper,
            projectMapper,
            shotMapper,
            seedanceService,
            userService,
            modelProfileService,
            imageGenerateService,
            ossService,
            commerceImageExecutor
    );

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        commerceImageTasks.clear();
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
        request.setImageUrl("https://oss.example.com/product.png");
        request.setProductType("上衣");
        request.setSellingPoints("凉感, 显瘦, 通勤");
        request.setTargetAudience("通勤人群");
        request.setPriceOffer("限时优惠");
        request.setTryOnRequirements("街景试穿");

        CommerceProductVO result = service.createProduct(200L, request);

        assertThat(result.getName()).isEqualTo("冰丝防晒衬衫");
        assertThat(result.getImageUrl()).isEqualTo("https://oss.example.com/product.png");
        assertThat(result.getSeriesId()).isEqualTo(100L);
        assertThat(result.getProductType()).isEqualTo("上衣");
        assertThat(result.getSellingPoints()).isNull();
        assertThat(result.getTargetAudience()).isNull();
        assertThat(result.getPriceOffer()).isNull();
        assertThat(result.getTryOnRequirements()).isNull();
        ArgumentCaptor<CommerceProduct> productCaptor = ArgumentCaptor.forClass(CommerceProduct.class);
        verify(productMapper).insert(productCaptor.capture());
        assertThat(productCaptor.getValue().getSellingPoints()).isNull();
        assertThat(productCaptor.getValue().getTryOnRequirements()).isNull();
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
        product.setSellingPoints("凉感, 显瘦, 通勤");

        when(productMapper.selectById(300L)).thenReturn(product);
        when(modelMapper.selectById(200L)).thenReturn(model);

        CommerceProjectCreateRequest request = new CommerceProjectCreateRequest();
        request.setContentType("try_on");
        request.setAspectRatio("3:4");

        CommerceProjectVO result = service.createProject(300L, request);

        assertThat(result.getContentType()).isEqualTo("try_on");
        assertThat(result.getAspectRatio()).isEqualTo("3:4");
        assertThat(result.getModelImageUrl()).isEqualTo("https://oss.example.com/model.png");
        assertThat(result.getProductImageUrl()).isEqualTo("https://oss.example.com/product.png");
        assertThat(result.getShots()).hasSize(5);
        assertThat(result.getShots().get(0).getVisualDescription()).contains("模特").contains("商品");
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

        when(productMapper.selectById(300L)).thenReturn(product);
        when(modelMapper.selectById(200L)).thenReturn(model);

        CommerceProjectCreateRequest request = new CommerceProjectCreateRequest();
        request.setContentType("try_on");
        request.setAspectRatio("9:16");
        request.setSellingPoints("试穿不应该用卖点");
        request.setTryOnRequirements("通勤街景、室内全身镜、户外自然光");

        CommerceProjectVO result = service.createProject(300L, request);

        String mergedVisuals = result.getShots().stream()
                .map(shot -> defaultString(shot.getVisualDescription()))
                .reduce("", String::concat);
        assertThat(mergedVisuals).contains("通勤街景、室内全身镜、户外自然光");
        assertThat(mergedVisuals).doesNotContain("商品表旧试穿要求不应使用");
        assertThat(mergedVisuals).doesNotContain("试穿不应该用卖点");
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
    void generateProjectVideoSubmitsModelAndProductReferenceImages() {
        UserContextHolder.setUserId(9L);
        CommerceProject project = new CommerceProject();
        project.setId(400L);
        project.setUserId(9L);
        project.setAspectRatio("3:4");
        project.setContentType("try_on");
        project.setModelImageUrl("https://oss.example.com/model.png");
        project.setProductImageUrl("https://oss.example.com/product.png");
        project.setStatus("draft");
        when(projectMapper.selectById(400L)).thenReturn(project);

        CommerceShot shot = new CommerceShot();
        shot.setId(500L);
        shot.setProjectId(400L);
        shot.setSortOrder(1);
        shot.setTitle("正面展示");
        shot.setVisualDescription("模特穿上商品正面展示");
        shot.setVoiceoverText("先看正面");
        shot.setDurationSeconds(5);
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
        assertThat(request.getContents()).hasSize(2);
        assertThat(request.getContents().get(0).getImageUrl().getUrl()).isEqualTo("https://oss.example.com/model.png");
        assertThat(request.getContents().get(1).getImageUrl().getUrl()).isEqualTo("https://oss.example.com/product.png");
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
