package com.manga.ai.commerce.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.manga.ai.commerce.service.CommerceModelProfileService;
import com.manga.ai.commerce.service.dto.CommerceModelProfile;
import com.manga.ai.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
public class ToapisCommerceModelProfileServiceImpl implements CommerceModelProfileService {

    @Value("${commerce.model-profile.api-key:${TOAPIS_API_KEY:${gpt-image2.api-key:}}}")
    private String apiKey;

    @Value("${commerce.model-profile.base-url:https://toapis.com/v1}")
    private String baseUrl;

    @Value("${commerce.model-profile.model:deepseek-v4-pro}")
    private String model;

    private final RestTemplate restTemplate;

    public ToapisCommerceModelProfileServiceImpl() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000);
        factory.setReadTimeout(120000);
        this.restTemplate = new RestTemplate(factory);
    }

    ToapisCommerceModelProfileServiceImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public CommerceModelProfile generateProfile(String preferredName, String gender, String modelType, String ethnicity, String bodyProfile) {
        if (isBlank(apiKey)) {
            throw new BusinessException("模特描述生成服务未配置");
        }

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        JSONArray messages = new JSONArray();
        messages.add(message("system", systemPrompt()));
        messages.add(message("user", userPrompt(gender, modelType, ethnicity, bodyProfile)));
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 1000);
        requestBody.put("stream", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        try {
            String url = trimTrailingSlash(baseUrl) + "/chat/completions";
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody.toJSONString(), headers),
                    String.class);
            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                throw new BusinessException("模特描述生成失败，请稍后重试");
            }
            String content = parseContent(response.getBody());
            CommerceModelProfile profile = parseProfile(content);
            validateProfile(profile);
            return profile;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("电商模特描述生成异常", e);
            throw new BusinessException("模特描述生成失败，请稍后重试");
        }
    }

    private JSONObject message(String role, String content) {
        JSONObject message = new JSONObject();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String parseContent(String responseBody) {
        JSONObject json = JSON.parseObject(responseBody);
        JSONArray choices = json.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new BusinessException("模特描述生成结果为空，请稍后重试");
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        String content = message == null ? null : message.getString("content");
        if (isBlank(content)) {
            throw new BusinessException("模特描述生成结果为空，请稍后重试");
        }
        return content;
    }

    private CommerceModelProfile parseProfile(String content) {
        String json = extractJson(content);
        try {
            JSONObject object = JSON.parseObject(json);
            CommerceModelProfile profile = new CommerceModelProfile();
            profile.setName(object.getString("name"));
            profile.setPrompt(object.getString("prompt"));
            profile.setGenderAge(object.getString("genderAge"));
            profile.setBodyProfile(object.getString("bodyProfile"));
            profile.setStyleTags(object.getString("styleTags"));
            profile.setSpeakingPersona(object.getString("speakingPersona"));
            profile.setAppearancePrompt(object.getString("appearancePrompt"));
            return profile;
        } catch (Exception e) {
            log.warn("解析电商模特描述失败: content={}", content, e);
            throw new BusinessException("模特描述生成结果解析失败，请稍后重试");
        }
    }

    private String extractJson(String content) {
        String text = content.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private void validateProfile(CommerceModelProfile profile) {
        if (profile == null
                || isBlank(profile.getName())
                || isBlank(profile.getPrompt())) {
            throw new BusinessException("模特描述生成结果不完整，请稍后重试");
        }
    }

    private String systemPrompt() {
        return """
                你是电商服装带货短视频的真人写实 AI 模特设定专家。
                只输出 JSON，不要 Markdown，不要解释。
                JSON 字段固定为 name, prompt。
                name 生成 2 到 4 个中文字符的自然昵称，不能使用用户输入名。
                prompt 用中文，写成用户可编辑的一段高画面感模特描述，控制在 120 到 220 字。
                性别、年龄与类型、人种风格、身材是生成模特描述的核心参考，必须影响人物年龄感、气质、五官骨相、肤色/地域特征和身形体态。
                prompt 必须包含：人物主体、面容/骨相/五官辨识度、细腻皮肤、发丝自然与光泽、身形比例、气质情绪、口播亲和感、镜头吸引力、光影层次、电影质感、服装带货适配性。
                参考这种表达密度：人物、发丝自然飘动、有光泽、面容宁静美丽、细腻皮肤、蓝调氛围、宁静高级的神态、电影质感。不要照抄成固定背景或固定服装。
                美女和帅哥要按高级审美写出惊艳但真实的长相：骨相优越、五官精致有辨识度、面部表现有记忆点、镜头吸引力强、气质高级，避免普通路人感。
                用户选择的身材只作为内部设定来影响身形比例和体态，不要把身材选项、身材标签或“身材设定”原样写进 prompt。
                成熟/中年/老年类型也要有高级质感和镜头魅力，不要写成疲态或廉价感，老人要写精神状态和亲和可信赖感。
                儿童类型只写清爽可爱、自然灵动、童装适配，不要成人化，不要性感化。
                不要写具体背景、三视图、设定板、纯白背景、镜头规格、32K、生成图片、AI 绘图等系统提示词，这些会由系统内置模板追加。
                不要描述模特当前穿着，不要描述服装，不要写任何衣服、内衣、白衬衫、长裤、裙子、背心、配饰等具体服装款式；服装由系统内置模板统一控制，用户提示词只负责人物外貌、身形比例、气质和镜头表现。
                避免明星、真实人物、品牌名、过度性感、暴露描写和夸张幻想元素。
                """;
    }

    private String userPrompt(String gender, String modelType, String ethnicity, String bodyProfile) {
        return "必须严格参考用户选择的性别、年龄与类型、人种风格和身材来生成模特描述。"
                + "\n用户选择的性别：" + gender
                + "\n用户选择的年龄与类型：" + modelType
                + "\n用户选择的人种风格：" + ethnicity
                + "\n用户选择的身材：" + bodyProfile
                + "\n请生成适合服装带货的真人写实模特昵称和高质感描述。";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
