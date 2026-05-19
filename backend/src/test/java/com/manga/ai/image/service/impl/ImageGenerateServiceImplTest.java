package com.manga.ai.image.service.impl;

import com.manga.ai.asset.mapper.AssetMetadataMapper;
import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.asset.service.AssetService;
import com.manga.ai.common.service.OssService;
import com.manga.ai.image.dto.ImageGenerateRequest;
import com.manga.ai.image.dto.ImageGenerateResponse;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.series.mapper.SeriesMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageGenerateServiceImplTest {

    @Test
    void characterSheetPromptKeepsHandsEmptyAndBansHeldObjects() throws Exception {
        ImageGenerateServiceImpl service = createService();
        ImageGenerateRequest defaultRequest = ImageGenerateRequest.builder()
                .roleName("沈清欢")
                .characterDescription("古风少女，红衣，长发")
                .styleKeywords("cinematic anime")
                .build();
        ImageGenerateRequest customRequest = ImageGenerateRequest.builder()
                .roleName("沈清欢")
                .customPrompt("古风少女三视图，红衣，长发")
                .styleKeywords("cinematic anime")
                .faceCloseupView(true)
                .build();

        assertEmptyHandsRules(invokePrompt(service, "buildCharacterSheetPrompt", defaultRequest));
        assertEmptyHandsRules(invokePrompt(service, "buildCharacterSheetPrompt", customRequest));
    }

    @Test
    void clothingChangePromptKeepsHandsEmptyAndBansHeldObjects() throws Exception {
        ImageGenerateServiceImpl service = createService();
        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .roleName("沈清欢")
                .referenceImageUrl("https://example.com/ref.png")
                .clothingPrompt("月白色长裙")
                .styleKeywords("cinematic anime")
                .build();

        assertEmptyHandsRules(invokePrompt(service, "buildClothingChangePrompt", request));
    }

    @Test
    void commerceModelPromptUsesProductModelSheetRules() throws Exception {
        ImageGenerateServiceImpl service = createService();
        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .roleName("小雅")
                .customPrompt("年轻东亚女性，五官清爽自然，性格亲和真实，适合女装种草。")
                .styleKeywords("真人写实")
                .build();

        String prompt = invokePrompt(service, "buildCommerceModelPrompt", request);

        assertThat(prompt)
                .contains("真人写实")
                .contains("电商服装带货模特")
                .contains("人物设定板")
                .contains("三视图")
                .contains("左侧约1/3为大头特写")
                .contains("右侧约2/3为正面、侧面、背面完整全身三视图")
                .contains("同一个真人模特")
                .contains("惊艳但真实")
                .contains("白色")
                .contains("白色微透蕾丝内衣")
                .contains("white semi-sheer lace underwear")
                .contains("完整全身")
                .contains("从头顶到脚趾完整入画")
                .contains("脚和脚趾")
                .contains("脚下保留清晰白色留白")
                .doesNotContain("STRICT LAYOUT in ONE image")
                .doesNotContain("HUGE face close-up portrait")
                .doesNotContain("Full body three-view")
                .doesNotContain("FULL BODY FRAMING")
                .contains("纯白背景")
                .doesNotContain("画面包含同一位真人模特的正面、侧面、背面三视图")
                .contains("NO text")
                .contains("NO watermark")
                .doesNotContain("Style:")
                .doesNotContain("Style: 真人写实");
        assertEmptyHandsRules(prompt);
    }

    @Test
    void commerceModelPromptKeepsChildClothingSeparateFromAdultUnderwear() throws Exception {
        ImageGenerateServiceImpl service = createService();
        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .roleName("童童")
                .customPrompt("中国小女孩，五官清爽自然，活泼亲和，适合童装展示。")
                .styleKeywords("真人写实, 电商服装带货, 大头特写, 右侧三视图, 纯白背景, 浅色儿童基础打底服")
                .build();

        String prompt = invokePrompt(service, "buildCommerceModelPrompt", request);

        assertThat(prompt)
                .contains("左侧约1/3为大头特写")
                .contains("右侧约2/3为正面、侧面、背面完整全身三视图")
                .contains("儿童模特只穿浅色")
                .contains("儿童基础打底")
                .doesNotContain("白色微透蕾丝内衣")
                .doesNotContain("white semi-sheer lace underwear");
    }

    @Test
    void commerceModelPromptUsesPlainBoxerBriefsForMaleModels() throws Exception {
        ImageGenerateServiceImpl service = createService();
        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .roleName("阿泽")
                .customPrompt("亲和自然，适合服装带货展示。")
                .styleKeywords("真人写实, 电商服装带货, 大头特写, 右侧三视图, 纯白背景, 标准匀称, 男模特, 普通浅色四角内裤")
                .build();

        String prompt = invokePrompt(service, "buildCommerceModelPrompt", request);

        assertThat(prompt)
                .contains("成人男模特上半身不穿")
                .contains("上半身裸露")
                .contains("普通浅色四角内裤")
                .contains("简洁纯色")
                .contains("基础男士款")
                .contains("仅保留这一条四角内裤")
                .contains("不添加其他服装层次")
                .contains("复杂花纹或装饰材质")
                .doesNotContain("蕾丝")
                .doesNotContain("网纱")
                .doesNotContain("内衣")
                .doesNotContain("背心")
                .doesNotContain("长袖")
                .doesNotContain("长裤")
                .doesNotContain("lace")
                .doesNotMatch("(?is).*\\bbra\\b.*")
                .doesNotContain("白色微透蕾丝内衣")
                .doesNotContain("white semi-sheer lace underwear")
                .doesNotContain("打底服不能抢占人物识别");
    }

    @Test
    void commerceModelPromptTreatsOlderMaleDescriptionsAsMaleModels() throws Exception {
        ImageGenerateServiceImpl service = createService();
        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .roleName("老周")
                .customPrompt("中国老头，精神矍铄，亲和自然，适合服装带货展示。")
                .styleKeywords("真人写实, 电商服装带货, 大头特写, 右侧三视图, 纯白背景, 标准匀称")
                .build();

        String prompt = invokePrompt(service, "buildCommerceModelPrompt", request);

        assertThat(prompt)
                .contains("成人男模特上半身不穿")
                .contains("普通浅色四角内裤")
                .doesNotContain("蕾丝")
                .doesNotContain("网纱")
                .doesNotContain("内衣")
                .doesNotContain("lace")
                .doesNotMatch("(?is).*\\bbra\\b.*")
                .doesNotContain("白色微透蕾丝内衣")
                .doesNotContain("white semi-sheer lace underwear");
    }

    @Test
    void commerceModelPromptDoesNotMentionEyesInRightThreeView() throws Exception {
        ImageGenerateServiceImpl service = createService();
        ImageGenerateRequest request = ImageGenerateRequest.builder()
                .roleName("小雅")
                .customPrompt("年轻东亚女性，五官清爽自然，性格亲和真实，适合女装种草。")
                .styleKeywords("真人写实")
                .build();

        String prompt = invokePrompt(service, "buildCommerceModelPrompt", request);

        assertThat(prompt)
                .contains("右侧正面五官尽量与左侧大头特写一致")
                .contains("侧面为标准侧身侧脸")
                .contains("背面不露正脸五官")
                .doesNotContain("眼睛")
                .doesNotContain("眼神")
                .doesNotContain("eyes")
                .doesNotContain("eye")
                .doesNotContain("Eye")
                .doesNotContain("EYES")
                .doesNotContain("RIGHT THREE-VIEW FACE QUALITY")
                .doesNotContain("front view eyes must be symmetrical")
                .doesNotContain("NO crossed eyes")
                .doesNotContain("NO extra eyes")
                .doesNotContain("NO distorted pupils")
                .doesNotContain("斗鸡眼")
                .doesNotContain("多余眼睛")
                .doesNotContain("side view shows only one visible profile eye")
                .doesNotContain("侧面视图只显示一只侧脸眼睛");
    }

    @Test
    void commerceModelImageUsesVolcengineArkSeedreamModelWithFixedSixteenNineFourKSize() {
        ImageGenerateServiceImpl service = createService();
        RestTemplate restTemplate = mock(RestTemplate.class);
        OssService ossService = (OssService) ReflectionTestUtils.getField(service, "ossService");
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "commerceModelImageApiKey", "test-ark-key");
        ReflectionTestUtils.setField(service, "commerceModelImageBaseUrl", "https://ark.cn-beijing.volces.com/api/v3");
        ReflectionTestUtils.setField(service, "commerceModelImageModel", "doubao-seedream-5-0-260128");
        when(ossService.uploadImageFromUrl("https://example.com/model.png", "commerce-models"))
                .thenReturn("https://oss.example.com/commerce-models/model.png");
        when(restTemplate.exchange(
                eq("https://ark.cn-beijing.volces.com/api/v3/images/generations"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(ResponseEntity.ok("""
                {"data":[{"url":"https://example.com/model.png"}]}
                """));

        ImageGenerateResponse response = service.generateCommerceModelImage(ImageGenerateRequest.builder()
                .roleName("小雅")
                .customPrompt("年轻东亚女性，五官清爽自然。")
                .aspectRatio("9:16")
                .quality("4k")
                .build());

        assertThat(response.getStatus()).isEqualTo("success");
        assertThat(response.getImageUrl()).isEqualTo("https://oss.example.com/commerce-models/model.png");
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("https://ark.cn-beijing.volces.com/api/v3/images/generations"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(String.class)
        );
        assertThat(entityCaptor.getValue().getHeaders().getFirst("Authorization")).isEqualTo("Bearer test-ark-key");
        assertThat(entityCaptor.getValue().getBody().toString())
                .contains("\"model\":\"doubao-seedream-5-0-260128\"")
                .contains("\"size\":\"4096x2304\"")
                .doesNotContain("\"size\":\"4K\"")
                .contains("\"sequential_image_generation\":\"disabled\"")
                .contains("\"output_format\":\"png\"")
                .contains("\"response_format\":\"url\"")
                .contains("\"stream\":false")
                .contains("\"watermark\":false")
                .doesNotContain("1600x2848")
                .doesNotContain("2304x4096");
        verify(ossService).uploadImageFromUrl("https://example.com/model.png", "commerce-models");
    }

    @Test
    void commerceModelImageReturnsProviderErrorWhenToapisResponseHasErrorJson() {
        ImageGenerateServiceImpl service = createService();
        RestTemplate restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "commerceModelImageApiKey", "test-toapis-key");
        ReflectionTestUtils.setField(service, "commerceModelImageBaseUrl", "https://toapis.com/v1");
        ReflectionTestUtils.setField(service, "commerceModelImageModel", "doubao-seedream-5-0-260128");
        when(restTemplate.exchange(
                eq("https://toapis.com/v1/images/generations"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(ResponseEntity.ok("""
                {"error":{"code":"ContentPolicyViolation","message":"图片内容可能触发安全策略，请调整提示词"}}
                """));

        ImageGenerateResponse response = service.generateCommerceModelImage(ImageGenerateRequest.builder()
                .roleName("阿泽")
                .customPrompt("成熟中国男性，亲和自然。")
                .quality("4k")
                .build());

        assertThat(response.getStatus()).isEqualTo("failed");
        assertThat(response.getErrorMessage()).contains("ContentPolicyViolation");
        assertThat(response.getErrorMessage()).contains("图片内容可能触发安全策略");
    }

    @Test
    void commerceModelImageReturnsProviderErrorWhenToapisResponseHasNoImageUrl() {
        ImageGenerateServiceImpl service = createService();
        RestTemplate restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "commerceModelImageApiKey", "test-toapis-key");
        ReflectionTestUtils.setField(service, "commerceModelImageBaseUrl", "https://toapis.com/v1");
        ReflectionTestUtils.setField(service, "commerceModelImageModel", "doubao-seedream-5-0-260128");
        when(restTemplate.exchange(
                eq("https://toapis.com/v1/images/generations"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(ResponseEntity.ok("""
                {"data":[],"message":"No image generated"}
                """));

        ImageGenerateResponse response = service.generateCommerceModelImage(ImageGenerateRequest.builder()
                .roleName("阿泽")
                .customPrompt("成熟中国男性，亲和自然。")
                .quality("4k")
                .build());

        assertThat(response.getStatus()).isEqualTo("failed");
        assertThat(response.getErrorMessage()).contains("No image generated");
    }

    private ImageGenerateServiceImpl createService() {
        return new ImageGenerateServiceImpl(
                mock(RoleMapper.class),
                mock(SeriesMapper.class),
                mock(RoleAssetMapper.class),
                mock(AssetMetadataMapper.class),
                mock(AssetService.class),
                mock(OssService.class),
                mock(TransactionTemplate.class)
        );
    }

    private String invokePrompt(ImageGenerateServiceImpl service, String methodName,
                                ImageGenerateRequest request) throws Exception {
        Method method = ImageGenerateServiceImpl.class.getDeclaredMethod(methodName, ImageGenerateRequest.class);
        method.setAccessible(true);
        return (String) method.invoke(service, request);
    }

    private void assertEmptyHandsRules(String prompt) {
        assertThat(prompt)
                .contains("empty hands")
                .contains("hands relaxed")
                .contains("NO objects in hands")
                .contains("NO holding anything")
                .contains("no computer")
                .contains("no laptop")
                .contains("no documents")
                .contains("no papers")
                .contains("no folders")
                .contains("no books")
                .contains("no phone")
                .contains("no bag")
                .contains("no weapon")
                .contains("no prop held");
    }
}
