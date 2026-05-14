package com.manga.ai.gptimage.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class GptImage2ControllerTest {

    @Test
    void controllerExposesGenerateAndUploadEndpoints() throws Exception {
        RequestMapping rootMapping = GptImage2Controller.class.getAnnotation(RequestMapping.class);
        Method generate = GptImage2Controller.class.getDeclaredMethod("generate", com.manga.ai.gptimage.dto.GptImage2GenerateRequest.class);
        Method uploadReference = GptImage2Controller.class.getDeclaredMethod("uploadReference", org.springframework.web.multipart.MultipartFile.class);

        assertThat(rootMapping).isNotNull();
        assertThat(rootMapping.value()).containsExactly("/v1/gpt-image2");
        assertThat(generate.getAnnotation(PostMapping.class).value()).containsExactly("/generate");
        assertThat(uploadReference.getAnnotation(PostMapping.class).value()).containsExactly("/upload-reference");
    }
}
