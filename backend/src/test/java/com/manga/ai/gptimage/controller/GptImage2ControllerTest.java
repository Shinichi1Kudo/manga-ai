package com.manga.ai.gptimage.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;

class GptImage2ControllerTest {

    @Test
    void controllerExposesGenerateAndUploadEndpoints() throws Exception {
        RequestMapping rootMapping = GptImage2Controller.class.getAnnotation(RequestMapping.class);
        Method generate = GptImage2Controller.class.getDeclaredMethod("generate", com.manga.ai.gptimage.dto.GptImage2GenerateRequest.class);
        Method uploadReference = GptImage2Controller.class.getDeclaredMethod("uploadReference", org.springframework.web.multipart.MultipartFile.class);
        Method getTask = GptImage2Controller.class.getDeclaredMethod("getTask", Long.class);
        Method getLatestTask = GptImage2Controller.class.getDeclaredMethod("getLatestTask");
        Method listTasks = GptImage2Controller.class.getDeclaredMethod("listTasks", Integer.class);

        assertThat(rootMapping).isNotNull();
        assertThat(rootMapping.value()).containsExactly("/v1/gpt-image2");
        assertThat(listTasks.getAnnotation(GetMapping.class).value()).containsExactly("");
        assertThat(generate.getAnnotation(PostMapping.class).value()).containsExactly("/generate");
        assertThat(uploadReference.getAnnotation(PostMapping.class).value()).containsExactly("/upload-reference");
        assertThat(getTask.getAnnotation(GetMapping.class).value()).containsExactly("/{taskId}");
        assertThat(getLatestTask.getAnnotation(GetMapping.class).value()).containsExactly("/latest");

        Parameter limitParameter = listTasks.getParameters()[0];
        assertThat(limitParameter.getAnnotation(RequestParam.class).required()).isFalse();
    }
}
