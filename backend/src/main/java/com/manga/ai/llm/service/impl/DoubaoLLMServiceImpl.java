package com.manga.ai.llm.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.manga.ai.llm.dto.LLMRequest;
import com.manga.ai.llm.dto.LLMResponse;
import com.manga.ai.llm.service.DoubaoLLMService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM服务实现 (支持智谱GLM)
 */
@Slf4j
@Service
public class DoubaoLLMServiceImpl implements DoubaoLLMService {

    @Value("${volcengine.llm.api-key:}")
    private String apiKey;

    @Value("${volcengine.llm.model:glm-4-flash}")
    private String model;

    @Value("${volcengine.llm.base-url:https://open.bigmodel.cn/api/paas/v4}")
    private String baseUrl;

    @Value("${volcengine.llm.timeout:120000}")
    private int timeout;

    private final RestTemplate restTemplate;

    public DoubaoLLMServiceImpl() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000);
        factory.setReadTimeout(180000); // 3分钟超时，剧本解析可能较慢
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        log.info("调用LLM: model={}, messages={}", model, request.getMessages().size());

        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);

            // 构建消息列表
            JSONArray messagesArray = new JSONArray();

            // 添加系统提示词
            if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
                JSONObject systemMessage = new JSONObject();
                systemMessage.put("role", "system");
                systemMessage.put("content", request.getSystemPrompt());
                messagesArray.add(systemMessage);
            }

            // 添加用户消息
            if (request.getMessages() != null) {
                for (LLMRequest.Message msg : request.getMessages()) {
                    JSONObject message = new JSONObject();
                    message.put("role", msg.getRole());
                    message.put("content", msg.getContent());
                    messagesArray.add(message);
                }
            }

            requestBody.put("messages", messagesArray);
            requestBody.put("temperature", request.getTemperature());
            requestBody.put("max_tokens", request.getMaxTokens());
            requestBody.put("stream", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);
            String url = baseUrl + "/chat/completions";

            log.debug("LLM请求URL: {}", url);
            log.debug("LLM请求体: {}", requestBody.toJSONString());

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            log.debug("LLM响应状态: {}", response.getStatusCode());

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseResponse(response.getBody());
            } else {
                LLMResponse errorResponse = new LLMResponse();
                errorResponse.setStatus("failed");
                errorResponse.setErrorMessage("LLM调用失败: " + response.getStatusCode());
                return errorResponse;
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("LLM API调用失败: statusCode={}, responseBody={}", e.getStatusCode(), e.getResponseBodyAsString());
            LLMResponse errorResponse = new LLMResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage(e.getStatusCode() + ": " + e.getResponseBodyAsString());
            return errorResponse;
        } catch (Exception e) {
            log.error("LLM调用异常", e);
            LLMResponse errorResponse = new LLMResponse();
            errorResponse.setStatus("failed");
            errorResponse.setErrorMessage(e.getMessage());
            return errorResponse;
        }
    }

    @Override
    public LLMResponse chat(String systemPrompt, String userPrompt) {
        LLMRequest request = new LLMRequest();
        request.setSystemPrompt(systemPrompt);
        List<LLMRequest.Message> messages = new ArrayList<>();
        messages.add(new LLMRequest.Message("user", userPrompt));
        request.setMessages(messages);
        return chat(request);
    }

    /**
     * 解析LLM响应
     */
    private LLMResponse parseResponse(String responseBody) {
        JSONObject json = JSON.parseObject(responseBody);
        LLMResponse response = new LLMResponse();
        response.setStatus("success");
        response.setModel(json.getString("model"));

        // 解析choices
        JSONArray choices = json.getJSONArray("choices");
        if (choices != null && !choices.isEmpty()) {
            JSONObject choice = choices.getJSONObject(0);
            JSONObject message = choice.getJSONObject("message");
            if (message != null) {
                response.setContent(message.getString("content"));
            }
        }

        // 解析usage
        JSONObject usage = json.getJSONObject("usage");
        if (usage != null) {
            response.setTotalTokens(usage.getInteger("total_tokens"));
        }

        log.info("LLM响应: tokens={}", response.getTotalTokens());
        return response;
    }
}
