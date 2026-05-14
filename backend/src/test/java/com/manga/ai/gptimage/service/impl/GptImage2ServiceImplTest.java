package com.manga.ai.gptimage.service.impl;

import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.service.OssService;
import com.manga.ai.gptimage.dto.GptImage2GenerateRequest;
import com.manga.ai.gptimage.dto.GptImage2GenerateResponse;
import com.manga.ai.gptimage.entity.GptImage2Task;
import com.manga.ai.gptimage.mapper.GptImage2TaskMapper;
import com.manga.ai.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

class GptImage2ServiceImplTest {

    @Test
    void generateRejectsBlankPrompt() {
        GptImage2ServiceImpl service = new GptImage2ServiceImpl(mock(OssService.class), mock(UserService.class), null, directExecutor());
        GptImage2GenerateRequest request = new GptImage2GenerateRequest();
        request.setPrompt("   ");

        assertThatThrownBy(() -> service.generate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请输入提示词");
    }

    @Test
    void generateRequiresApiKeyFromConfiguration() {
        GptImage2ServiceImpl service = new GptImage2ServiceImpl(mock(OssService.class), mock(UserService.class), null, directExecutor());
        ReflectionTestUtils.setField(service, "apiKey", "");

        GptImage2GenerateRequest request = new GptImage2GenerateRequest();
        request.setPrompt("古风少女海报");
        request.setAspectRatio("1:1");

        assertThatThrownBy(() -> service.generate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("GPT-Image2 API Key 未配置");
    }

    @Test
    void generatePersistsPendingTaskAndReturnsTaskState() {
        GptImage2TaskMapper mapper = mock(GptImage2TaskMapper.class);
        Executor executor = mock(Executor.class);
        GptImage2ServiceImpl service = new GptImage2ServiceImpl(
                mock(OssService.class),
                mock(UserService.class),
                mapper,
                executor
        );
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "model", "gpt-image-2");
        when(mapper.insert(any(GptImage2Task.class))).thenAnswer(invocation -> {
            invocation.<GptImage2Task>getArgument(0).setId(12L);
            return 1;
        });
        GptImage2GenerateRequest request = new GptImage2GenerateRequest();
        request.setPrompt("古风少女海报");
        request.setAspectRatio("1:1");

        UserContextHolder.setUserId(7L);
        try {
            GptImage2GenerateResponse response = service.generate(request);

            assertThat(response.getId()).isEqualTo(12L);
            assertThat(response.getStatus()).isEqualTo("pending");
            assertThat(response.getProgressPercent()).isEqualTo(5);
            assertThat(response.getPrompt()).isEqualTo("古风少女海报");
            assertThat(response.getMode()).isEqualTo("text-to-image");
        } finally {
            UserContextHolder.clear();
        }

        verify(mapper).insert(argThat(task -> Long.valueOf(7L).equals(task.getUserId())
                && "pending".equals(task.getStatus())
                && "古风少女海报".equals(task.getPrompt())));
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void persistGeneratedImageUploadsDataUrlDirectlyToOss() {
        OssService ossService = mock(OssService.class);
        when(ossService.uploadImage(any(byte[].class), eq("gpt-image2/results"), eq("image/png"), eq("png")))
                .thenReturn("https://oss.example.com/generated.png");
        GptImage2ServiceImpl service = new GptImage2ServiceImpl(ossService, mock(UserService.class), null, directExecutor());
        String dataUrl = "data:image/png;base64," + java.util.Base64.getEncoder()
                .encodeToString("png-bytes".getBytes(StandardCharsets.UTF_8));

        String result = service.persistGeneratedImage(dataUrl);

        assertThat(result).isEqualTo("https://oss.example.com/generated.png");
        verify(ossService).uploadImage(
                eq("png-bytes".getBytes(StandardCharsets.UTF_8)),
                eq("gpt-image2/results"),
                eq("image/png"),
                eq("png")
        );
        verify(ossService, never()).uploadImageFromUrl(any(), any());
    }

    @Test
    void executeTaskRetriesPrematureEofBeforeMarkingTaskFailed() {
        GptImage2TaskMapper mapper = mock(GptImage2TaskMapper.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        OssService ossService = mock(OssService.class);
        GptImage2Task task = new GptImage2Task();
        task.setId(12L);
        task.setUserId(7L);
        task.setPrompt("淘宝手机广告图");
        task.setAspectRatio("3:4");
        task.setStatus("pending");
        task.setModel("gpt-image-2");
        task.setMode("text-to-image");
        String dataUrl = "data:image/png;base64," + java.util.Base64.getEncoder()
                .encodeToString("png-bytes".getBytes(StandardCharsets.UTF_8));
        List<String> persistedStatuses = new ArrayList<>();

        when(mapper.selectById(12L)).thenReturn(task);
        when(mapper.updateById(any(GptImage2Task.class))).thenAnswer(invocation -> {
            persistedStatuses.add(invocation.<GptImage2Task>getArgument(0).getStatus());
            return 1;
        });
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RestClientException("Error while extracting response", new IOException("Premature EOF")))
                .thenThrow(new RestClientException("Error while extracting response", new IOException("Premature EOF")))
                .thenReturn(ResponseEntity.ok("{\"data\":[{\"url\":\"" + dataUrl + "\"}]}"));
        when(ossService.uploadImage(any(byte[].class), eq("gpt-image2/results"), eq("image/png"), eq("png")))
                .thenReturn("https://oss.example.com/generated.png");

        GptImage2ServiceImpl service = new GptImage2ServiceImpl(
                ossService,
                mock(UserService.class),
                mapper,
                directExecutor()
        );
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "model", "gpt-image-2");
        ReflectionTestUtils.setField(service, "baseUrl", "https://api.airiver.cn/v1");
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);

        service.executeTask(12L);

        assertThat(task.getStatus()).isEqualTo("succeeded");
        assertThat(task.getImageUrl()).isEqualTo("https://oss.example.com/generated.png");
        assertThat(persistedStatuses).containsExactly("running", "succeeded");
        verify(restTemplate, times(3)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    private static Executor directExecutor() {
        return Runnable::run;
    }
}
