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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
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
        request.setAspectRatio("16:9");
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
            assertThat(response.getCreditCost()).isEqualTo(6);
        } finally {
            UserContextHolder.clear();
        }

        verify(mapper).insert(argThat(task -> Long.valueOf(7L).equals(task.getUserId())
                && "pending".equals(task.getStatus())
                && "古风少女海报".equals(task.getPrompt())
                && "4k".equals(task.getResolution())
                && Integer.valueOf(6).equals(task.getCreditCost())));
        verify(userService).deductCredits(
                eq(7L),
                eq(6),
                eq(CreditUsageType.IMAGE_GENERATION.getCode()),
                eq("GPT-Image2生图-任务12"),
                eq(12L),
                eq("GPT_IMAGE2_TASK")
        );
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void generateRejectsUnsupportedResolutionAspectCombinationBeforeDeductingCredits() {
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
        GptImage2GenerateRequest request = new GptImage2GenerateRequest();
        request.setPrompt("电影海报");
        request.setAspectRatio("16:9");
        request.setResolution("1k");

        UserContextHolder.setUserId(7L);
        try {
            assertThatThrownBy(() -> service.generate(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("当前清晰度不支持该图片比例");
        } finally {
            UserContextHolder.clear();
        }

        verify(mapper, never()).insert(any(GptImage2Task.class));
        verify(userService, never()).deductCredits(anyLong(), anyInt(), anyString(), anyString(), anyLong(), anyString());
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void generateRejectsProviderUnsupportedNineToTwentyOneBeforeDeductingCredits() {
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
        GptImage2GenerateRequest request = new GptImage2GenerateRequest();
        request.setPrompt("扫描件修复");
        request.setAspectRatio("9:21");
        request.setResolution("4k");

        UserContextHolder.setUserId(7L);
        try {
            assertThatThrownBy(() -> service.generate(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("当前模型暂不支持 9:21");
        } finally {
            UserContextHolder.clear();
        }

        verify(mapper, never()).insert(any(GptImage2Task.class));
        verify(userService, never()).deductCredits(anyLong(), anyInt(), anyString(), anyString(), anyLong(), anyString());
        verify(executor, never()).execute(any(Runnable.class));
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
                eq(6),
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
    void listMyTasksShowsElapsedProgressForLongRunningTask() {
        GptImage2TaskMapper mapper = mock(GptImage2TaskMapper.class);
        GptImage2Task running = new GptImage2Task();
        running.setId(12L);
        running.setUserId(7L);
        running.setPrompt("灵感");
        running.setAspectRatio("1:1");
        running.setResolution("2k");
        running.setStatus("running");
        running.setModel("gpt-image-2");
        running.setMode("text-to-image");
        running.setCreditCost(12);
        running.setSubmittedAt(LocalDateTime.now().minusMinutes(10));
        running.setUpdatedAt(LocalDateTime.now());
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(running));
        GptImage2ServiceImpl service = new GptImage2ServiceImpl(
                mock(OssService.class),
                mock(UserService.class),
                mapper,
                directExecutor()
        );

        UserContextHolder.setUserId(7L);
        try {
            List<GptImage2GenerateResponse> tasks = service.listMyTasks(50);

            assertThat(tasks).hasSize(1);
            assertThat(tasks.get(0).getProgressPercent()).isGreaterThan(35);
            assertThat(tasks.get(0).getProgressPercent()).isLessThanOrEqualTo(95);
        } finally {
            UserContextHolder.clear();
        }
    }

    @Test
    void getTaskMarksStaleRunningTaskFailedBeforeReturningIt() {
        GptImage2TaskMapper mapper = mock(GptImage2TaskMapper.class);
        UserService userService = mock(UserService.class);
        GptImage2Task stale = new GptImage2Task();
        stale.setId(12L);
        stale.setUserId(7L);
        stale.setPrompt("灵感");
        stale.setAspectRatio("1:1");
        stale.setResolution("2k");
        stale.setStatus("running");
        stale.setModel("gpt-image-2");
        stale.setMode("text-to-image");
        stale.setCreditCost(12);
        stale.setCreditsRefunded(false);
        stale.setSubmittedAt(LocalDateTime.now().minusMinutes(31));
        stale.setUpdatedAt(LocalDateTime.now().minusMinutes(31));
        when(mapper.selectById(12L)).thenReturn(stale);
        GptImage2ServiceImpl service = new GptImage2ServiceImpl(
                mock(OssService.class),
                userService,
                mapper,
                directExecutor()
        );

        UserContextHolder.setUserId(7L);
        try {
            GptImage2GenerateResponse response = service.getTask(12L);

            assertThat(response.getStatus()).isEqualTo("failed");
            assertThat(response.getStatusDesc()).isEqualTo("生成失败");
            assertThat(response.getErrorMessage()).contains("生成任务超时");
            assertThat(response.getProgressPercent()).isEqualTo(100);
        } finally {
            UserContextHolder.clear();
        }

        verify(userService).refundCredits(7L, 12, "GPT-Image2生图失败返还-任务12", 12L, "GPT_IMAGE2_TASK");
        verify(mapper).updateById(stale);
    }

    @Test
    void getTaskKeepsLongRunningTaskAliveBeforeStaleWindow() {
        GptImage2TaskMapper mapper = mock(GptImage2TaskMapper.class);
        UserService userService = mock(UserService.class);
        GptImage2Task running = new GptImage2Task();
        running.setId(12L);
        running.setUserId(7L);
        running.setPrompt("灵感");
        running.setAspectRatio("1:1");
        running.setResolution("2k");
        running.setStatus("running");
        running.setModel("gpt-image-2");
        running.setMode("text-to-image");
        running.setCreditCost(12);
        running.setCreditsRefunded(false);
        running.setSubmittedAt(LocalDateTime.now().minusMinutes(16));
        running.setUpdatedAt(LocalDateTime.now().minusMinutes(16));
        when(mapper.selectById(12L)).thenReturn(running);
        GptImage2ServiceImpl service = new GptImage2ServiceImpl(
                mock(OssService.class),
                userService,
                mapper,
                directExecutor()
        );

        UserContextHolder.setUserId(7L);
        try {
            GptImage2GenerateResponse response = service.getTask(12L);

            assertThat(response.getStatus()).isEqualTo("running");
            assertThat(response.getStatusDesc()).isEqualTo("生成中");
            assertThat(response.getProgressPercent()).isBetween(35, 95);
        } finally {
            UserContextHolder.clear();
        }

        verify(userService, never()).refundCredits(anyLong(), anyInt(), anyString(), anyLong(), anyString());
        verify(mapper, never()).updateById(running);
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
    void persistGeneratedImageReuploadsExternalOssUrlToOwnBucket() {
        OssService ossService = mock(OssService.class);
        String externalOssUrl = "https://airiver-bucket.oss-cn-beijing.aliyuncs.com/chatgpt/a71c3ba1-result.png";
        when(ossService.uploadImageFromUrl(externalOssUrl, "gpt-image2/results"))
                .thenReturn("https://movie-agent.oss-cn-beijing.aliyuncs.com/gpt-image2/results/owned.png");
        GptImage2ServiceImpl service = new GptImage2ServiceImpl(ossService, mock(UserService.class), null, directExecutor());

        String result = service.persistGeneratedImage(externalOssUrl);

        assertThat(result).contains("gpt-image2/results/owned.png");
        verify(ossService).uploadImageFromUrl(externalOssUrl, "gpt-image2/results");
        verify(ossService, never()).refreshUrl(anyString());
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
        task.setResolution("2k");
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
            return body.contains("\"size\":\"3:4\"") && body.contains("\"resolution\":\"2K\"");
        }), eq(String.class));
    }

    @Test
    void executeTaskSubmitsToToapisImageApiAndPollsTaskUntilCompleted() {
        GptImage2TaskMapper mapper = mock(GptImage2TaskMapper.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        OssService ossService = mock(OssService.class);
        GptImage2Task task = new GptImage2Task();
        task.setId(12L);
        task.setUserId(7L);
        task.setPrompt("电商产品海报");
        task.setAspectRatio("16:9");
        task.setResolution("4k");
        task.setReferenceImageUrl("https://movie-agent.oss-cn-beijing.aliyuncs.com/gpt-image2/references/ref.png");
        task.setStatus("pending");
        task.setModel("gpt-image-2");
        task.setMode("image-to-image");
        task.setCreditCost(12);
        AtomicInteger queryCount = new AtomicInteger();

        when(mapper.selectById(12L)).thenReturn(task);
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(invocation -> {
                    HttpMethod method = invocation.getArgument(1);
                    if (HttpMethod.POST.equals(method)) {
                        return ResponseEntity.ok("{\"id\":\"img-task-1\",\"object\":\"generation.task\",\"model\":\"gpt-image-2\",\"status\":\"queued\",\"progress\":0,\"created_at\":1778615200}");
                    }
                    int count = queryCount.incrementAndGet();
                    if (count == 1) {
                        return ResponseEntity.ok("{\"id\":\"img-task-1\",\"object\":\"generation.task\",\"model\":\"gpt-image-2\",\"status\":\"in_progress\",\"progress\":45}");
                    }
                    return ResponseEntity.ok("{\"id\":\"img-task-1\",\"object\":\"generation.task\",\"model\":\"gpt-image-2\",\"status\":\"completed\",\"progress\":100,\"data\":[{\"url\":\"https://toapis.example.com/result.png\"}]}");
                });
        when(ossService.uploadImageFromUrl("https://toapis.example.com/result.png", "gpt-image2/results"))
                .thenReturn("https://movie-agent.oss-cn-beijing.aliyuncs.com/gpt-image2/results/result.png");

        GptImage2ServiceImpl service = new GptImage2ServiceImpl(
                ossService,
                mock(UserService.class),
                mapper,
                directExecutor()
        );
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "model", "gpt-image-2");
        ReflectionTestUtils.setField(service, "baseUrl", "https://toapis.com/v1");
        ReflectionTestUtils.setField(service, "pollIntervalMs", 1);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);

        service.executeTask(12L);

        assertThat(task.getStatus()).isEqualTo("succeeded");
        assertThat(task.getImageUrl()).isEqualTo("https://movie-agent.oss-cn-beijing.aliyuncs.com/gpt-image2/results/result.png");

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("https://toapis.com/v1/images/generations"), eq(HttpMethod.POST), entityCaptor.capture(), eq(String.class));
        String submitBody = String.valueOf(entityCaptor.getValue().getBody());
        assertThat(submitBody).contains("\"model\":\"gpt-image-2\"");
        assertThat(submitBody).contains("\"prompt\":\"电商产品海报\"");
        assertThat(submitBody).contains("\"size\":\"16:9\"");
        assertThat(submitBody).contains("\"resolution\":\"4K\"");
        assertThat(submitBody).contains("\"n\":1");
        assertThat(submitBody).contains("\"response_format\":\"url\"");
        assertThat(submitBody).contains("\"reference_images\":[\"https://movie-agent.oss-cn-beijing.aliyuncs.com/gpt-image2/references/ref.png\"]");
        assertThat(submitBody).doesNotContain("\"image\"");
        assertThat(submitBody).doesNotContain("\"stream\"");
        assertThat(submitBody).doesNotContain("\"watermark\"");
        verify(restTemplate, times(2)).exchange(eq("https://toapis.com/v1/images/generations/img-task-1"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        verify(ossService).uploadImageFromUrl("https://toapis.example.com/result.png", "gpt-image2/results");
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
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        verify(userService).refundCredits(7L, 12, "GPT-Image2生图失败返还-任务12", 12L, "GPT_IMAGE2_TASK");
    }

    @Test
    void executeTaskShowsApiKeyErrorWhenToapisRejectsAuthorization() {
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
                .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        GptImage2ServiceImpl service = new GptImage2ServiceImpl(
                mock(OssService.class),
                userService,
                mapper,
                directExecutor()
        );
        ReflectionTestUtils.setField(service, "apiKey", "wrong-key");
        ReflectionTestUtils.setField(service, "model", "gpt-image-2");
        ReflectionTestUtils.setField(service, "baseUrl", "https://toapis.com/v1");
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);

        service.executeTask(12L);

        assertThat(task.getStatus()).isEqualTo("failed");
        assertThat(task.getErrorMessage()).contains("API Key 无效").contains("ToApis");
        verify(userService).refundCredits(7L, 12, "GPT-Image2生图失败返还-任务12", 12L, "GPT_IMAGE2_TASK");
    }

    @Test
    void executeTaskDoesNotRetryReadTimeoutForMinutes() {
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
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        verify(userService).refundCredits(7L, 12, "GPT-Image2生图失败返还-任务12", 12L, "GPT_IMAGE2_TASK");
    }

    @Test
    void failStaleRunningTasksMarksExpiredTasksFailedAndRefundsCredits() {
        GptImage2TaskMapper mapper = mock(GptImage2TaskMapper.class);
        UserService userService = mock(UserService.class);
        GptImage2Task stale = new GptImage2Task();
        stale.setId(12L);
        stale.setUserId(7L);
        stale.setStatus("running");
        stale.setCreditCost(12);
        stale.setCreditsRefunded(false);
        stale.setSubmittedAt(LocalDateTime.now().minusMinutes(31));
        stale.setCreatedAt(LocalDateTime.now().minusMinutes(31));
        GptImage2Task fresh = new GptImage2Task();
        fresh.setId(13L);
        fresh.setUserId(7L);
        fresh.setStatus("running");
        fresh.setCreditCost(12);
        fresh.setCreditsRefunded(false);
        fresh.setSubmittedAt(LocalDateTime.now().minusMinutes(2));
        fresh.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(stale, fresh));
        GptImage2ServiceImpl service = new GptImage2ServiceImpl(
                mock(OssService.class),
                userService,
                mapper,
                directExecutor()
        );

        int failedCount = service.failStaleRunningTasks();

        assertThat(failedCount).isEqualTo(1);
        assertThat(stale.getStatus()).isEqualTo("failed");
        assertThat(stale.getErrorMessage()).contains("生成任务超时");
        assertThat(stale.getCompletedAt()).isNotNull();
        assertThat(fresh.getStatus()).isEqualTo("running");
        verify(userService).refundCredits(7L, 12, "GPT-Image2生图失败返还-任务12", 12L, "GPT_IMAGE2_TASK");
        verify(mapper).updateById(stale);
        verify(mapper, never()).updateById(fresh);
    }

    private static Executor directExecutor() {
        return Runnable::run;
    }
}
