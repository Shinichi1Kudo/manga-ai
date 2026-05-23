package com.manga.ai.commerce.controller;

import com.manga.ai.commerce.service.CommerceService;
import com.manga.ai.commerce.dto.CommerceProjectVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommerceControllerTest {

    private CommerceService commerceService;
    private CommerceController controller;

    @BeforeEach
    void setUp() {
        commerceService = mock(CommerceService.class);
        controller = new CommerceController(commerceService);
    }

    @Test
    void controllerExposesProductImageUploadEndpoint() throws Exception {
        RequestMapping rootMapping = CommerceController.class.getAnnotation(RequestMapping.class);
        Method uploadProductImage = CommerceController.class.getDeclaredMethod("uploadProductImage", MultipartFile.class);

        assertThat(rootMapping).isNotNull();
        assertThat(rootMapping.value()).containsExactly("/v1/commerce");
        assertThat(uploadProductImage.getAnnotation(PostMapping.class).value()).containsExactly("/products/upload-image");
        Parameter fileParameter = uploadProductImage.getParameters()[0];
        assertThat(fileParameter.getAnnotation(RequestParam.class).value()).isEqualTo("file");
        assertThat(fileParameter.getAnnotation(RequestParam.class).required()).isFalse();
    }

    @Test
    void uploadProductImageReturnsOssUrlWrapper() {
        MockMultipartFile file = new MockMultipartFile("file", "product.png", "image/png", new byte[]{1, 2, 3});
        when(commerceService.uploadProductImage(file)).thenReturn("https://oss.example.com/commerce/products/product.png");

        var result = controller.uploadProductImage(file);

        assertThat(result.getData().getUrl()).isEqualTo("https://oss.example.com/commerce/products/product.png");
    }

    @Test
    void controllerExposesCommerceSceneLibraryEndpoints() throws Exception {
        Method listScenes = CommerceController.class.getDeclaredMethod("listScenes", Long.class);
        Method prepareScenes = CommerceController.class.getDeclaredMethod(
                "prepareScenes", Long.class, com.manga.ai.commerce.dto.CommerceSceneBatchCreateRequest.class);
        Method startScenes = CommerceController.class.getDeclaredMethod(
                "startScenes", Long.class, com.manga.ai.commerce.dto.CommerceSceneGenerationStartRequest.class);

        assertThat(listScenes.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).value())
                .containsExactly("/series/{seriesId}/scenes");
        assertThat(prepareScenes.getAnnotation(PostMapping.class).value())
                .containsExactly("/series/{seriesId}/scenes/batch/prepare");
        assertThat(startScenes.getAnnotation(PostMapping.class).value())
                .containsExactly("/series/{seriesId}/scenes/batch/start");
    }

    @Test
    void controllerDoesNotExposeTryOnFirstFrameEndpoints() {
        assertThat(CommerceController.class.getDeclaredMethods())
                .extracting(Method::getName)
                .doesNotContain(
                        "prepareTryOnFirstFrame",
                        "startTryOnFirstFrame",
                        "confirmTryOnFirstFrame");
    }

    @Test
    void controllerExposesProjectGenerationStartEndpoint() throws Exception {
        Method getProject = CommerceController.class.getDeclaredMethod("getProject", Long.class);
        Method startProjectVideoGeneration = CommerceController.class.getDeclaredMethod("startProjectVideoGeneration", Long.class);

        assertThat(getProject.getAnnotation(GetMapping.class).value()).containsExactly("/projects/{projectId}");
        assertThat(startProjectVideoGeneration.getAnnotation(PostMapping.class).value())
                .containsExactly("/projects/{projectId}/generation/start");
    }

    @Test
    void projectGenerationStartReturnsServiceResult() {
        CommerceProjectVO project = new CommerceProjectVO();
        project.setId(90L);
        project.setStatus("generating");
        when(commerceService.startProjectVideoGeneration(90L)).thenReturn(project);

        var result = controller.startProjectVideoGeneration(90L);

        assertThat(result.getData().getId()).isEqualTo(90L);
        assertThat(result.getData().getStatus()).isEqualTo("generating");
    }
}
