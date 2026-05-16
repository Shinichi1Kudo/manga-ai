package com.manga.ai.llm.service.impl;

import com.manga.ai.llm.service.DoubaoLLMService;
import com.manga.ai.role.mapper.RoleMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ScriptParseServiceImplTest {

    @Test
    void shotPromptsRequireNarrativeContinuityAcrossAdjacentShots() throws Exception {
        ScriptParseServiceImpl service = new ScriptParseServiceImpl(
                mock(DoubaoLLMService.class),
                mock(RoleMapper.class)
        );

        String defaultPrompt = invokePrompt(service, "buildShotsOnlyPrompt");
        String detailedPrompt = invokePrompt(service, "buildDetailedShotsPrompt");

        assertContinuityRules(defaultPrompt);
        assertContinuityRules(detailedPrompt);
    }

    private String invokePrompt(ScriptParseServiceImpl service, String methodName) throws Exception {
        Method method = ScriptParseServiceImpl.class.getDeclaredMethod(methodName, List.class, Map.class);
        method.setAccessible(true);
        return (String) method.invoke(service, List.of("沈清欢", "顾言"), Map.of("SC01", 1L, "SC02", 2L));
    }

    private void assertContinuityRules(String prompt) {
        assertThat(prompt).contains("连续叙事");
        assertThat(prompt).contains("承接上一镜");
        assertThat(prompt).contains("推进下一镜");
        assertThat(prompt).contains("禁止孤立分镜");
        assertThat(prompt).contains("上一镜的动作、视线、情绪、道具或声音");
        assertThat(prompt).contains("下一镜的视觉或情绪钩子");
    }
}
