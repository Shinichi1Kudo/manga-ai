package com.manga.ai.llm.service;

import com.manga.ai.llm.dto.LLMRequest;
import com.manga.ai.llm.dto.LLMResponse;

/**
 * LLM服务接口
 */
public interface DoubaoLLMService {

    /**
     * 调用LLM生成回复
     * @param request LLM请求
     * @return LLM响应
     */
    LLMResponse chat(LLMRequest request);

    /**
     * 调用LLM生成回复（使用系统提示词）
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户提示词
     * @return LLM响应
     */
    LLMResponse chat(String systemPrompt, String userPrompt);
}
