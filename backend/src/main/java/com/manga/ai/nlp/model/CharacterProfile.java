package com.manga.ai.nlp.model;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 角色档案 - NLP 提取结果
 */
@Data
public class CharacterProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 角色名称
     */
    private String name;
    private BigDecimal nameConfidence;

    /**
     * 年龄
     */
    private String age;
    private BigDecimal ageConfidence;

    /**
     * 性别
     */
    private String gender;
    private BigDecimal genderConfidence;

    /**
     * 外貌描述
     */
    private String appearance;
    private BigDecimal appearanceConfidence;

    /**
     * 性格描述
     */
    private String personality;
    private BigDecimal personalityConfidence;

    /**
     * 服装描述
     */
    private String clothing;
    private BigDecimal clothingConfidence;

    /**
     * 特殊标识
     */
    private String specialMarks;

    /**
     * 原始文本
     */
    private String originalText;

    /**
     * 扩展属性
     */
    private List<ExtractedAttribute> attributes = new ArrayList<>();

    /**
     * 计算综合置信度
     */
    public BigDecimal getOverallConfidence() {
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;

        // 名字权重最高
        if (nameConfidence != null) {
            total = total.add(nameConfidence.multiply(new BigDecimal("2")));
            count += 2;
        }
        if (ageConfidence != null) {
            total = total.add(ageConfidence);
            count++;
        }
        if (genderConfidence != null) {
            total = total.add(genderConfidence);
            count++;
        }
        if (appearanceConfidence != null) {
            total = total.add(appearanceConfidence);
            count++;
        }
        if (personalityConfidence != null) {
            total = total.add(personalityConfidence);
            count++;
        }
        if (clothingConfidence != null) {
            total = total.add(clothingConfidence);
            count++;
        }

        if (count == 0) {
            return BigDecimal.ZERO;
        }

        return total.divide(new BigDecimal(count), 2, BigDecimal.ROUND_HALF_UP);
    }
}
