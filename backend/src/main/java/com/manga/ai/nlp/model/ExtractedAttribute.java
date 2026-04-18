package com.manga.ai.nlp.model;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 提取的属性
 */
@Data
public class ExtractedAttribute implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 属性键
     */
    private String key;

    /**
     * 属性值
     */
    private String value;

    /**
     * 置信度
     */
    private BigDecimal confidence;

    /**
     * 来源位置（原文中的位置）
     */
    private Integer startIndex;
    private Integer endIndex;

    public ExtractedAttribute() {
    }

    public ExtractedAttribute(String key, String value, BigDecimal confidence) {
        this.key = key;
        this.value = value;
        this.confidence = confidence;
    }
}
