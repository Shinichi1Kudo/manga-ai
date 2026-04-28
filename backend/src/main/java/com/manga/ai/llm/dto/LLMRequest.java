package com.manga.ai.llm.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * LLM请求DTO
 */
@Data
public class LLMRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 系统提示词
     */
    private String systemPrompt;

    /**
     * 用户消息
     */
    private List<Message> messages;

    /**
     * 温度参数 (0-1)
     */
    private Double temperature = 0.7;

    /**
     * 模型名称（可选，为空则使用默认配置的模型）
     */
    private String model;

    /**
     * 最大输出token数（详细版分镜需要更大输出空间）
     */
    private Integer maxTokens = 20000;

    @Data
    public static class Message implements Serializable {
        private String role;  // system, user, assistant
        private String content;

        public Message() {}

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
