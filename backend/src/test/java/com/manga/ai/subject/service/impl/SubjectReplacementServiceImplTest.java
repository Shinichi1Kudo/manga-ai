package com.manga.ai.subject.service.impl;

import com.manga.ai.subject.dto.SubjectReplacementItemDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

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
