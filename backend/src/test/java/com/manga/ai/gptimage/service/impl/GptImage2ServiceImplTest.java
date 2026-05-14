package com.manga.ai.gptimage.service.impl;

import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.service.OssService;
import com.manga.ai.gptimage.dto.GptImage2GenerateRequest;
import com.manga.ai.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class GptImage2ServiceImplTest {

    @Test
    void generateRejectsBlankPrompt() {
        GptImage2ServiceImpl service = new GptImage2ServiceImpl(mock(OssService.class), mock(UserService.class));
        GptImage2GenerateRequest request = new GptImage2GenerateRequest();
        request.setPrompt("   ");

        assertThatThrownBy(() -> service.generate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请输入提示词");
    }

    @Test
    void generateRequiresApiKeyFromConfiguration() {
        GptImage2ServiceImpl service = new GptImage2ServiceImpl(mock(OssService.class), mock(UserService.class));
        ReflectionTestUtils.setField(service, "apiKey", "");

        GptImage2GenerateRequest request = new GptImage2GenerateRequest();
        request.setPrompt("古风少女海报");
        request.setAspectRatio("1:1");

        assertThatThrownBy(() -> service.generate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("GPT-Image2 API Key 未配置");
    }
}
