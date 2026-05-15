package com.manga.ai.subject.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.manga.ai.common.enums.CreditUsageType;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.subject.dto.SubjectReplacementCreateRequest;
import com.manga.ai.subject.dto.SubjectReplacementItemDTO;
import com.manga.ai.subject.entity.SubjectReplacementTask;
import com.manga.ai.subject.mapper.SubjectReplacementTaskMapper;
import com.manga.ai.user.service.UserService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import com.manga.ai.video.dto.SeedanceRequest;
import com.manga.ai.video.dto.SeedanceResponse;
import com.manga.ai.video.service.SeedanceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SubjectReplacementServiceImplTest {

    @Test
    void buildPromptUsesLanguageHintWhenProvided() throws Exception {
        SubjectReplacementServiceImpl service = new SubjectReplacementServiceImpl(null, null, null, null, directExecutor());
        SubjectReplacementItemDTO item = new SubjectReplacementItemDTO();
        item.setReplacementType("person");
        item.setSourceObject("画面中央带眼镜的女主播");
        item.setTargetDescription("欧美人");
        item.setReferenceImageUrl("https://example.com/reference.png");
        item.setAppearanceHint("英语");

        String prompt = invokeBuildPrompt(service, List.of(item));

        assertThat(prompt).contains("语言改成英语");
        assertThat(prompt).contains("如果映射中填写了“语言改成xxx”");
        assertThat(prompt).doesNotContain("语言：英语");
        assertThat(prompt).doesNotContain("外观定位信息：英语");
    }

    @Test
    void deleteTaskTreatsAlreadyDeletedOwnedTaskAsSuccess() {
        SubjectReplacementTaskMapper mapper = mock(SubjectReplacementTaskMapper.class);
        SubjectReplacementServiceImpl service = new SubjectReplacementServiceImpl(mapper, null, null, null, directExecutor());
        UserContextHolder.setUserId(1L);
        try {
            assertThatNoException().isThrownBy(() -> service.deleteTask(5L));
        } finally {
            UserContextHolder.clear();
        }

        verify(mapper).delete(any());
        verify(mapper, never()).selectById(5L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void createTaskDeductsCreditsByDurationAndRecordsSubjectReplacementUsage() {
        SubjectReplacementTaskMapper mapper = mock(SubjectReplacementTaskMapper.class);
        UserService userService = mock(UserService.class);
        SubjectReplacementServiceImpl service = new SubjectReplacementServiceImpl(
                mapper, null, null, userService, command -> {});
        SubjectReplacementCreateRequest request = createRequest(5);
        when(mapper.insert(any(SubjectReplacementTask.class))).thenAnswer(invocation -> {
            invocation.<SubjectReplacementTask>getArgument(0).setId(9L);
            return 1;
        });

        UserContextHolder.setUserId(7L);
        try {
            service.createTask(request);
        } finally {
            UserContextHolder.clear();
        }

        verify(userService).deductCredits(
                eq(7L),
                eq(160),
                eq(CreditUsageType.SUBJECT_REPLACEMENT.getCode()),
                eq("主体替换-任务9"),
                eq(9L),
                eq("SUBJECT_REPLACEMENT")
        );
        verify(mapper).insert(argThat(task -> Integer.valueOf(160).equals(task.getDeductedCredits())
                && Boolean.FALSE.equals(task.getCreditsRefunded())));
    }

    @Test
    void createTaskNormalizesLegacySubjectReplacementModelToToapisVipModel() {
        SubjectReplacementTaskMapper mapper = mock(SubjectReplacementTaskMapper.class);
        UserService userService = mock(UserService.class);
        SubjectReplacementServiceImpl service = new SubjectReplacementServiceImpl(
                mapper, null, null, userService, command -> {});
        ReflectionTestUtils.setField(service, "subjectReplacementModel", "seedance-1.0-pro");
        SubjectReplacementCreateRequest request = createRequest(5);
        when(mapper.insert(any(SubjectReplacementTask.class))).thenAnswer(invocation -> {
            invocation.<SubjectReplacementTask>getArgument(0).setId(9L);
            return 1;
        });

        UserContextHolder.setUserId(7L);
        try {
            service.createTask(request);
        } finally {
            UserContextHolder.clear();
        }

        verify(mapper).insert(argThat(task ->
                "doubao-seedance-2-0-260128".equals(task.getModel())
        ));
    }

    @Test
    void createTaskDeletesInsertedTaskWhenCreditDeductionFails() {
        SubjectReplacementTaskMapper mapper = mock(SubjectReplacementTaskMapper.class);
        UserService userService = mock(UserService.class);
        SubjectReplacementServiceImpl service = new SubjectReplacementServiceImpl(
                mapper, null, null, userService, command -> {});
        SubjectReplacementCreateRequest request = createRequest(5);
        when(mapper.insert(any(SubjectReplacementTask.class))).thenAnswer(invocation -> {
            invocation.<SubjectReplacementTask>getArgument(0).setId(9L);
            return 1;
        });
        doThrow(new BusinessException("积分不足")).when(userService).deductCredits(
                eq(7L),
                eq(160),
                eq(CreditUsageType.SUBJECT_REPLACEMENT.getCode()),
                eq("主体替换-任务9"),
                eq(9L),
                eq("SUBJECT_REPLACEMENT")
        );

        UserContextHolder.setUserId(7L);
        try {
            assertThatThrownBy(() -> service.createTask(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("积分不足");
        } finally {
            UserContextHolder.clear();
        }

        verify(mapper).delete(any(LambdaQueryWrapper.class));
        verify(mapper, never()).updateById(any(SubjectReplacementTask.class));
    }

    @Test
    void executeTaskRefundsDeductedCreditsOnceWhenGenerationFails() {
        SubjectReplacementTaskMapper mapper = mock(SubjectReplacementTaskMapper.class);
        SeedanceService seedanceService = mock(SeedanceService.class);
        UserService userService = mock(UserService.class);
        SubjectReplacementTask task = createTask();
        task.setId(9L);
        task.setUserId(7L);
        task.setDeductedCredits(160);
        task.setCreditsRefunded(false);
        when(mapper.selectById(9L)).thenReturn(task);

        SeedanceResponse response = new SeedanceResponse();
        response.setStatus("failed");
        response.setErrorMessage("InvalidParameter");
        when(seedanceService.generateVideo(any())).thenReturn(response);

        SubjectReplacementServiceImpl service = new SubjectReplacementServiceImpl(
                mapper, null, seedanceService, userService, directExecutor());

        service.executeTask(9L);

        verify(userService).refundCredits(7L, 160, "主体替换失败返还-任务9", 9L, "SUBJECT_REPLACEMENT");
        assertThat(task.getCreditsRefunded()).isTrue();
        assertThat(task.getStatus()).isEqualTo("failed");
    }

    @Test
    void executeTaskNormalizesLegacyStoredModelAndKeepsReferenceVideoForNewApi() {
        SubjectReplacementTaskMapper mapper = mock(SubjectReplacementTaskMapper.class);
        SeedanceService seedanceService = mock(SeedanceService.class);
        UserService userService = mock(UserService.class);
        SubjectReplacementTask task = createTask();
        task.setId(9L);
        task.setUserId(7L);
        task.setModel("seedance-1.0-pro");
        when(mapper.selectById(9L)).thenReturn(task);

        SeedanceResponse response = new SeedanceResponse();
        response.setStatus("completed");
        response.setVideoUrl("https://example.com/out.mp4");
        response.setTaskId("task-new-api");
        when(seedanceService.generateVideo(any())).thenReturn(response);

        SubjectReplacementServiceImpl service = new SubjectReplacementServiceImpl(
                mapper, null, seedanceService, userService, directExecutor());

        service.executeTask(9L);

        ArgumentCaptor<SeedanceRequest> captor = ArgumentCaptor.forClass(SeedanceRequest.class);
        verify(seedanceService).generateVideo(captor.capture());
        SeedanceRequest request = captor.getValue();
        assertThat(request.getModel()).isEqualTo("doubao-seedance-2-0-260128");
        assertThat(request.getContents())
                .anySatisfy(content -> {
                    assertThat(content.getType()).isEqualTo("video_url");
                    assertThat(content.getRole()).isEqualTo("reference_video");
                    assertThat(content.getVideoUrl().getUrl()).isEqualTo("https://example.com/input.mp4");
                })
                .anySatisfy(content -> {
                    assertThat(content.getType()).isEqualTo("image_url");
                    assertThat(content.getRole()).isEqualTo("reference_image");
                    assertThat(content.getImageUrl().getUrl()).isEqualTo("https://example.com/ref.png");
                });
        assertThat(task.getModel()).isEqualTo("doubao-seedance-2-0-260128");
        assertThat(task.getStatus()).isEqualTo("succeeded");
    }

    @Test
    void executeTaskDoesNotRefundAgainWhenCreditsAlreadyRefunded() {
        SubjectReplacementTaskMapper mapper = mock(SubjectReplacementTaskMapper.class);
        SeedanceService seedanceService = mock(SeedanceService.class);
        UserService userService = mock(UserService.class);
        SubjectReplacementTask task = createTask();
        task.setId(9L);
        task.setUserId(7L);
        task.setDeductedCredits(160);
        task.setCreditsRefunded(true);
        when(mapper.selectById(9L)).thenReturn(task);

        SeedanceResponse response = new SeedanceResponse();
        response.setStatus("failed");
        response.setErrorMessage("InvalidParameter");
        when(seedanceService.generateVideo(any())).thenReturn(response);

        SubjectReplacementServiceImpl service = new SubjectReplacementServiceImpl(
                mapper, null, seedanceService, userService, directExecutor());

        service.executeTask(9L);

        verify(userService, never()).refundCredits(any(), eq(160), any(), any(), any());
    }

    private static Executor directExecutor() {
        return Runnable::run;
    }

    private static SubjectReplacementCreateRequest createRequest(int duration) {
        SubjectReplacementCreateRequest request = new SubjectReplacementCreateRequest();
        request.setOriginalVideoUrl("https://example.com/input.mp4");
        request.setAspectRatio("16:9");
        request.setDuration(duration);
        request.setReplacements(List.of(createReplacement()));
        return request;
    }

    private static SubjectReplacementTask createTask() {
        SubjectReplacementTask task = new SubjectReplacementTask();
        task.setOriginalVideoUrl("https://example.com/input.mp4");
        task.setAspectRatio("16:9");
        task.setDuration(5);
        task.setGenerateAudio(true);
        task.setWatermark(false);
        task.setModel("doubao-seedance-2-0-260128");
        task.setReplacementsJson("[{\"replacementType\":\"person\",\"sourceObject\":\"主播\",\"targetDescription\":\"欧美人\",\"referenceImageUrl\":\"https://example.com/ref.png\"}]");
        task.setStatus("pending");
        return task;
    }

    private static SubjectReplacementItemDTO createReplacement() {
        SubjectReplacementItemDTO item = new SubjectReplacementItemDTO();
        item.setReplacementType("person");
        item.setSourceObject("主播");
        item.setTargetDescription("欧美人");
        item.setReferenceImageUrl("https://example.com/ref.png");
        return item;
    }

    @SuppressWarnings("unchecked")
    private static String invokeBuildPrompt(SubjectReplacementServiceImpl service,
                                            List<SubjectReplacementItemDTO> replacements) throws Exception {
        Method method = SubjectReplacementServiceImpl.class.getDeclaredMethod("buildPrompt", List.class);
        method.setAccessible(true);
        return (String) method.invoke(service, replacements);
    }
}
