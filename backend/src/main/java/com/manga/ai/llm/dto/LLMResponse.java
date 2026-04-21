package com.manga.ai.llm.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * LLM响应DTO
 */
@Data
public class LLMResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 响应状态
     */
    private String status;

    /**
     * 响应内容
     */
    private String content;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 使用的token数
     */
    private Integer totalTokens;

    /**
     * 模型
     */
    private String model;
}
