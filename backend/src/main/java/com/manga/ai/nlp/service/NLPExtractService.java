package com.manga.ai.nlp.service;

import com.manga.ai.nlp.model.CharacterProfile;
import com.manga.ai.nlp.model.ExtractedAttribute;

import java.util.List;
import java.util.Map;

/**
 * NLP 提取服务接口
 */
public interface NLPExtractService {

    /**
     * 从文本中提取角色档案
     */
    List<CharacterProfile> extractCharacters(String text);

    /**
     * 提取单个角色的属性
     */
    Map<String, ExtractedAttribute> extractAttributes(String characterText);

    /**
     * 提取角色名称
     */
    String extractName(String text);

    /**
     * 提取年龄
     */
    String extractAge(String text);

    /**
     * 提取性别
     */
    String extractGender(String text);

    /**
     * 提取外貌描述
     */
    String extractAppearance(String text);

    /**
     * 提取性格描述
     */
    String extractPersonality(String text);

    /**
     * 提取服装描述
     */
    String extractClothing(String text);
}
