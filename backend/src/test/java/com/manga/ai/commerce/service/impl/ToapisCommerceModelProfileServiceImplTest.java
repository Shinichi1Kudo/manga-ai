package com.manga.ai.commerce.service.impl;

import com.manga.ai.commerce.service.dto.CommerceModelProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToapisCommerceModelProfileServiceImplTest {

    @Test
    void generateProfileCallsToapisDeepseekAndParsesJsonProfile() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ToapisCommerceModelProfileServiceImpl service = new ToapisCommerceModelProfileServiceImpl(restTemplate);
        ReflectionTestUtils.setField(service, "apiKey", "test-toapis-key");
        ReflectionTestUtils.setField(service, "baseUrl", "https://toapis.com/v1");
        ReflectionTestUtils.setField(service, "model", "deepseek-v4-pro");

        when(restTemplate.exchange(
                eq("https://toapis.com/v1/chat/completions"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(ResponseEntity.ok("""
                {"choices":[{"message":{"content":"{\\"name\\":\\"小雅\\",\\"prompt\\":\\"年轻东亚女性，五官清爽自然，身形匀称，性格亲和真实，镜头表现像朋友一样自然种草。\\"}"}}]}
                """));

        CommerceModelProfile profile = service.generateProfile("小雅", "女", "年轻美女", "中国", "微胖亲和");

        assertThat(profile.getName()).isEqualTo("小雅");
        assertThat(profile.getPrompt()).contains("性格亲和");

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("https://toapis.com/v1/chat/completions"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(String.class)
        );
        HttpEntity entity = entityCaptor.getValue();
        assertThat(entity.getHeaders().getFirst("Authorization")).isEqualTo("Bearer test-toapis-key");
        assertThat(entity.getBody().toString()).contains("\"model\":\"deepseek-v4-pro\"");
        assertThat(entity.getBody().toString()).contains("年轻美女");
        assertThat(entity.getBody().toString()).contains("中国");
        assertThat(entity.getBody().toString()).contains("微胖亲和");
        assertThat(entity.getBody().toString()).contains("性别、年龄与类型、人种风格、身材是生成模特描述的核心参考");
        assertThat(entity.getBody().toString()).contains("必须严格参考用户选择的性别、年龄与类型、人种风格和身材");
        assertThat(entity.getBody().toString()).contains("用户选择的年龄与类型：年轻美女");
        assertThat(entity.getBody().toString()).contains("用户选择的人种风格：中国");
        assertThat(entity.getBody().toString()).contains("用户选择的身材：微胖亲和");
        assertThat(entity.getBody().toString()).contains("用户选择的身材");
        assertThat(entity.getBody().toString()).contains("不要把身材选项");
        assertThat(entity.getBody().toString()).contains("惊艳");
        assertThat(entity.getBody().toString()).contains("高级审美");
        assertThat(entity.getBody().toString()).contains("蓝调氛围");
        assertThat(entity.getBody().toString()).contains("电影质感");
        assertThat(entity.getBody().toString()).contains("发丝");
        assertThat(entity.getBody().toString()).contains("光泽");
        assertThat(entity.getBody().toString()).contains("细腻皮肤");
        assertThat(entity.getBody().toString()).doesNotContain("眼神");
        assertThat(entity.getBody().toString()).doesNotContain("眼睛");
        assertThat(entity.getBody().toString()).doesNotContain("eyes");
        assertThat(entity.getBody().toString()).contains("不要描述模特当前穿着");
        assertThat(entity.getBody().toString()).contains("不要描述服装");
        assertThat(entity.getBody().toString()).contains("不要写任何衣服");
        assertThat(entity.getBody().toString()).contains("服装由系统内置模板统一控制");
        assertThat(entity.getBody().toString()).doesNotContain("简约优雅基础服装");
        assertThat(entity.getBody().toString()).doesNotContain("小雅");
        assertThat(entity.getBody().toString()).doesNotContain("用户填写的模特名称");
    }
}
