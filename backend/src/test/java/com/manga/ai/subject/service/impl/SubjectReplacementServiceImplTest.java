package com.manga.ai.subject.service.impl;

import com.manga.ai.subject.dto.SubjectReplacementItemDTO;
import com.manga.ai.subject.mapper.SubjectReplacementTaskMapper;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class SubjectReplacementServiceImplTest {

    @Test
    void buildPromptUsesLanguageHintWhenProvided() throws Exception {
        SubjectReplacementServiceImpl service = new SubjectReplacementServiceImpl(null, null, null, directExecutor());
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
        SubjectReplacementServiceImpl service = new SubjectReplacementServiceImpl(mapper, null, null, directExecutor());
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

    private static Executor directExecutor() {
        return Runnable::run;
    }

    @SuppressWarnings("unchecked")
    private static String invokeBuildPrompt(SubjectReplacementServiceImpl service,
                                            List<SubjectReplacementItemDTO> replacements) throws Exception {
        Method method = SubjectReplacementServiceImpl.class.getDeclaredMethod("buildPrompt", List.class);
        method.setAccessible(true);
        return (String) method.invoke(service, replacements);
    }
}
