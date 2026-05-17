package com.manga.ai.commerce.service.impl;

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
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.user.service.UserService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import com.manga.ai.video.dto.SeedanceRequest;
import com.manga.ai.video.dto.SeedanceResponse;
import com.manga.ai.video.service.SeedanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceServiceImplTest {

    private final CommerceSeriesMapper seriesMapper = mock(CommerceSeriesMapper.class);
    private final CommerceModelMapper modelMapper = mock(CommerceModelMapper.class);
    private final CommerceProductMapper productMapper = mock(CommerceProductMapper.class);
    private final CommerceProjectMapper projectMapper = mock(CommerceProjectMapper.class);
    private final CommerceShotMapper shotMapper = mock(CommerceShotMapper.class);
    private final SeedanceService seedanceService = mock(SeedanceService.class);
    private final UserService userService = mock(UserService.class);
    private final CommerceServiceImpl service = new CommerceServiceImpl(
            seriesMapper,
            modelMapper,
            productMapper,
            projectMapper,
            shotMapper,
            seedanceService,
            userService
    );

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
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
    void createModelRequiresNameAndKeepsBaseImageOnly() {
        UserContextHolder.setUserId(9L);
        CommerceSeries series = new CommerceSeries();
        series.setId(100L);
        series.setUserId(9L);
        series.setDefaultAspectRatio("3:4");
        when(seriesMapper.selectById(100L)).thenReturn(series);

        CommerceModelCreateRequest request = new CommerceModelCreateRequest();
        request.setName("小雅");
        request.setSourceType("uploaded");
        request.setBaseImageUrl("https://oss.example.com/model.png");
        request.setBodyProfile("身高168，M码");

        CommerceModelVO result = service.createModel(100L, request);

        assertThat(result.getName()).isEqualTo("小雅");
        assertThat(result.getBaseImageUrl()).isEqualTo("https://oss.example.com/model.png");
        assertThat(result.getStatus()).isEqualTo("pending_review");
    }

    @Test
    void createProductRequiresImageAndBelongsToModel() {
        UserContextHolder.setUserId(9L);
        CommerceModel model = new CommerceModel();
        model.setId(200L);
        model.setSeriesId(100L);
        model.setUserId(9L);
        when(modelMapper.selectById(200L)).thenReturn(model);

        CommerceProductCreateRequest request = new CommerceProductCreateRequest();
        request.setName("冰丝防晒衬衫");
        request.setImageUrl("https://oss.example.com/product.png");
        request.setProductType("上衣");
        request.setSellingPoints("凉感, 显瘦, 通勤");

        CommerceProductVO result = service.createProduct(200L, request);

        assertThat(result.getName()).isEqualTo("冰丝防晒衬衫");
        assertThat(result.getImageUrl()).isEqualTo("https://oss.example.com/product.png");
        assertThat(result.getSeriesId()).isEqualTo(100L);
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
}
