package com.manga.ai.gptimage.service.impl;

import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.enums.CreditUsageType;
import com.manga.ai.common.service.OssService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import static org.mockito.Mockito.doThrow;
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
        UserService userService = mock(UserService.class);
        Executor executor = mock(Executor.class);
        GptImage2ServiceImpl service = new GptImage2ServiceImpl(
                mock(OssService.class),
                userService,
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
        request.setResolution("4k");

        UserContextHolder.setUserId(7L);
        try {
            GptImage2GenerateResponse response = service.generate(request);

            assertThat(response.getId()).isEqualTo(12L);
            assertThat(response.getStatus()).isEqualTo("pending");
            assertThat(response.getProgressPercent()).isEqualTo(5);
            assertThat(response.getPrompt()).isEqualTo("古风少女海报");
            assertThat(response.getMode()).isEqualTo("text-to-image");
            assertThat(response.getResolution()).isEqualTo("4k");
            assertThat(response.getCreditCost()).isEqualTo(12);
        } finally {
            UserContextHolder.clear();
        }

        verify(mapper).insert(argThat(task -> Long.valueOf(7L).equals(task.getUserId())
                && "pending".equals(task.getStatus())
                && "古风少女海报".equals(task.getPrompt())
                && "4k".equals(task.getResolution())
                && Integer.valueOf(12).equals(task.getCreditCost())));
        verify(userService).deductCredits(
                eq(7L),
                eq(12),
                eq(CreditUsageType.IMAGE_GENERATION.getCode()),
                eq("GPT-Image2生图-任务12"),
                eq(12L),
                eq("GPT_IMAGE2_TASK")
        );
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void generateDoesNotStartBackgroundTaskWhenCreditDeductionFails() {
        GptImage2TaskMapper mapper = mock(GptImage2TaskMapper.class);
        UserService userService = mock(UserService.class);
        Executor executor = mock(Executor.class);
        GptImage2ServiceImpl service = new GptImage2ServiceImpl(
                mock(OssService.class),
                userService,
                mapper,
                executor
        );
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "model", "gpt-image-2");
        when(mapper.insert(any(GptImage2Task.class))).thenAnswer(invocation -> {
            invocation.<GptImage2Task>getArgument(0).setId(12L);
            return 1;
        });
        doThrow(new BusinessException("积分不足")).when(userService).deductCredits(
                eq(7L),
                eq(12),
                eq(CreditUsageType.IMAGE_GENERATION.getCode()),
                eq("GPT-Image2生图-任务12"),
                eq(12L),
                eq("GPT_IMAGE2_TASK")
        );
        GptImage2GenerateRequest request = new GptImage2GenerateRequest();
        request.setPrompt("古风少女海报");
        request.setAspectRatio("1:1");

        UserContextHolder.setUserId(7L);
        try {
            assertThatThrownBy(() -> service.generate(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("积分不足");
        } finally {
            UserContextHolder.clear();
        }

        verify(executor, never()).execute(any(Runnable.class));
        verify(mapper).updateById(argThat(task -> Long.valueOf(12L).equals(task.getId())
                && "failed".equals(task.getStatus())
                && "积分不足".equals(task.getErrorMessage())));
    }

    @Test
    void listMyTasksReturnsCurrentUsersRecentTasks() {
        GptImage2TaskMapper mapper = mock(GptImage2TaskMapper.class);
        GptImage2Task running = new GptImage2Task();
        running.setId(12L);
        running.setUserId(7L);
        running.setPrompt("古风少女海报");
        running.setAspectRatio("1:1");
        running.setResolution("2k");
        running.setStatus("running");
        running.setModel("gpt-image-2");
        running.setMode("text-to-image");
        running.setCreditCost(12);

        GptImage2Task succeeded = new GptImage2Task();
        succeeded.setId(11L);
        succeeded.setUserId(7L);
        succeeded.setPrompt("参考图改图");
        succeeded.setAspectRatio("3:4");
        succeeded.setResolution("4k");
        succeeded.setStatus("succeeded");
        succeeded.setImageUrl("https://oss.example.com/generated.png");
        succeeded.setReferenceImageUrl("https://oss.example.com/ref.png");
        succeeded.setModel("gpt-image-2");
        succeeded.setMode("image-to-image");
        succeeded.setCreditCost(12);

        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(running, succeeded));
        GptImage2ServiceImpl service = new GptImage2ServiceImpl(
                mock(OssService.class),
                mock(UserService.class),
                mapper,
                directExecutor()
        );

        UserContextHolder.setUserId(7L);
        try {
            List<GptImage2GenerateResponse> tasks = service.listMyTasks(80);

            assertThat(tasks).hasSize(2);
            assertThat(tasks.get(0).getId()).isEqualTo(12L);
            assertThat(tasks.get(0).getProgressPercent()).isEqualTo(35);
            assertThat(tasks.get(0).getResolution()).isEqualTo("2k");
            assertThat(tasks.get(0).getCreditCost()).isEqualTo(12);
            assertThat(tasks.get(1).getImageUrl()).isEqualTo("https://oss.example.com/generated.png");
            assertThat(tasks.get(1).getResolution()).isEqualTo("4k");
            assertThat(tasks.get(1).getCreditCost()).isEqualTo(12);
        } finally {
            UserContextHolder.clear();
        }

        verify(mapper).selectList(any(Wrapper.class));
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
        task.setResolution("4k");
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
        verify(restTemplate, times(3)).exchange(anyString(), eq(HttpMethod.POST), argThat(entity -> {
            String body = String.valueOf(entity.getBody());
            return body.contains("\"size\":\"2016x2688\"");
        }), eq(String.class));
    }

    @Test
    void executeTaskRefundsCreditsWhenGenerationFails() {
        GptImage2TaskMapper mapper = mock(GptImage2TaskMapper.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        UserService userService = mock(UserService.class);
        GptImage2Task task = new GptImage2Task();
        task.setId(12L);
        task.setUserId(7L);
        task.setPrompt("古风少女海报");
        task.setAspectRatio("1:1");
        task.setResolution("2k");
        task.setStatus("pending");
        task.setModel("gpt-image-2");
        task.setMode("text-to-image");
        task.setCreditCost(12);
        when(mapper.selectById(12L)).thenReturn(task);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RestClientException("Read timed out"));

        GptImage2ServiceImpl service = new GptImage2ServiceImpl(
                mock(OssService.class),
                userService,
                mapper,
                directExecutor()
        );
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "model", "gpt-image-2");
        ReflectionTestUtils.setField(service, "baseUrl", "https://api.airiver.cn/v1");
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);

        service.executeTask(12L);

        assertThat(task.getStatus()).isEqualTo("failed");
        assertThat(task.getErrorMessage()).contains("超时");
        verify(userService).refundCredits(7L, 12, "GPT-Image2生图失败返还-任务12", 12L, "GPT_IMAGE2_TASK");
    }

    private static Executor directExecutor() {
        return Runnable::run;
    }
}
