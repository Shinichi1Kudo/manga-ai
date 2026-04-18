package com.manga.ai.nlp.service.impl;

import com.manga.ai.nlp.model.CharacterProfile;
import com.manga.ai.nlp.model.ExtractedAttribute;
import com.manga.ai.nlp.service.NLPExtractService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NLP 提取服务实现 - 基于规则的方法
 */
@Slf4j
@Service
public class NLPExtractServiceImpl implements NLPExtractService {

    @Value("${nlp.confidence-threshold:0.6}")
    private BigDecimal confidenceThreshold;

    // 名称提取模式
    private static final Pattern[] NAME_PATTERNS = {
            Pattern.compile("(?:姓名|名字|叫)[：:]*([^，。\\n\\s]+)"),
            Pattern.compile("^([^，。\\n]{2,4})[，,：:]"),  // 开头的名字
            Pattern.compile("【([^】]+)】")  // 方括号包裹
    };

    // 年龄提取模式
    private static final Pattern AGE_PATTERN = Pattern.compile("(\\d{1,3})岁");

    // 性别提取模式
    private static final Pattern GENDER_PATTERN = Pattern.compile("(男|女)(?:性|孩|子|郎|士)?");

    // 外貌关键词
    private static final List<String> APPEARANCE_KEYWORDS = Arrays.asList(
            "身高", "体型", "发型", "发色", "眼睛", "肤色", "五官", "面容", "长相",
            "身材", "脸型", "眉毛", "鼻子", "嘴巴", "耳朵", "皮肤", "瞳孔"
    );

    // 性格关键词
    private static final List<String> PERSONALITY_KEYWORDS = Arrays.asList(
            "性格", "个性", "脾气", "为人", "处事", "待人", "脾气", "性情", "品格"
    );

    // 服装关键词
    private static final List<String> CLOTHING_KEYWORDS = Arrays.asList(
            "穿着", "衣服", "服装", "打扮", "穿着", "身披", "身着", "服饰", "装扮"
    );

    @Override
    public List<CharacterProfile> extractCharacters(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // 分段 - 识别不同角色的描述段落
        List<String> paragraphs = splitCharacterParagraphs(text);

        List<CharacterProfile> profiles = new ArrayList<>();

        for (String paragraph : paragraphs) {
            if (paragraph.trim().isEmpty()) continue;

            CharacterProfile profile = new CharacterProfile();
            profile.setOriginalText(paragraph);

            // 提取基本信息
            profile.setName(extractName(paragraph));
            profile.setNameConfidence(profile.getName() != null ? new BigDecimal("0.8") : BigDecimal.ZERO);

            profile.setAge(extractAge(paragraph));
            profile.setAgeConfidence(profile.getAge() != null ? new BigDecimal("0.9") : BigDecimal.ZERO);

            profile.setGender(extractGender(paragraph));
            profile.setGenderConfidence(profile.getGender() != null ? new BigDecimal("0.9") : BigDecimal.ZERO);

            profile.setAppearance(extractAppearance(paragraph));
            profile.setAppearanceConfidence(profile.getAppearance() != null ? new BigDecimal("0.7") : BigDecimal.ZERO);

            profile.setPersonality(extractPersonality(paragraph));
            profile.setPersonalityConfidence(profile.getPersonality() != null ? new BigDecimal("0.7") : BigDecimal.ZERO);

            profile.setClothing(extractClothing(paragraph));
            profile.setClothingConfidence(profile.getClothing() != null ? new BigDecimal("0.7") : BigDecimal.ZERO);

            // 提取扩展属性
            Map<String, ExtractedAttribute> attributes = extractAttributes(paragraph);
            profile.setAttributes(new ArrayList<>(attributes.values()));

            profiles.add(profile);
        }

        log.info("提取到 {} 个角色", profiles.size());
        return profiles;
    }

    @Override
    public Map<String, ExtractedAttribute> extractAttributes(String characterText) {
        Map<String, ExtractedAttribute> attributes = new HashMap<>();

        // 提取发型
        String hairstyle = extractAttributeAfterKeyword(characterText, "发型");
        if (hairstyle != null) {
            attributes.put("发型", new ExtractedAttribute("发型", hairstyle, new BigDecimal("0.7")));
        }

        // 提取发色
        String hairColor = extractAttributeAfterKeyword(characterText, "发色");
        if (hairColor != null) {
            attributes.put("发色", new ExtractedAttribute("发色", hairColor, new BigDecimal("0.7")));
        }

        // 提取身高
        String height = extractAttributeAfterKeyword(characterText, "身高");
        if (height != null) {
            attributes.put("身高", new ExtractedAttribute("身高", height, new BigDecimal("0.8")));
        }

        // 提取体型
        String bodyType = extractAttributeAfterKeyword(characterText, "体型");
        if (bodyType != null) {
            attributes.put("体型", new ExtractedAttribute("体型", bodyType, new BigDecimal("0.7")));
        }

        return attributes;
    }

    @Override
    public String extractName(String text) {
        for (Pattern pattern : NAME_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }

    @Override
    public String extractAge(String text) {
        Matcher matcher = AGE_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1) + "岁";
        }
        return null;
    }

    @Override
    public String extractGender(String text) {
        Matcher matcher = GENDER_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    @Override
    public String extractAppearance(String text) {
        StringBuilder appearance = new StringBuilder();

        for (String keyword : APPEARANCE_KEYWORDS) {
            String value = extractAttributeAfterKeyword(text, keyword);
            if (value != null) {
                if (appearance.length() > 0) {
                    appearance.append("；");
                }
                appearance.append(keyword).append("：").append(value);
            }
        }

        return appearance.length() > 0 ? appearance.toString() : null;
    }

    @Override
    public String extractPersonality(String text) {
        StringBuilder personality = new StringBuilder();

        for (String keyword : PERSONALITY_KEYWORDS) {
            String value = extractAttributeAfterKeyword(text, keyword);
            if (value != null) {
                if (personality.length() > 0) {
                    personality.append("；");
                }
                personality.append(keyword).append("：").append(value);
            }
        }

        return personality.length() > 0 ? personality.toString() : null;
    }

    @Override
    public String extractClothing(String text) {
        StringBuilder clothing = new StringBuilder();

        for (String keyword : CLOTHING_KEYWORDS) {
            String value = extractAttributeAfterKeyword(text, keyword);
            if (value != null) {
                if (clothing.length() > 0) {
                    clothing.append("；");
                }
                clothing.append(value);
            }
        }

        return clothing.length() > 0 ? clothing.toString() : null;
    }

    /**
     * 分割角色段落
     */
    private List<String> splitCharacterParagraphs(String text) {
        // 按双换行或明显的角色分隔符分割
        String[] parts = text.split("\\n\\n|\\n---\\n|\\n\\*\\*\\*\\n");

        // 如果只有一段，尝试按数字编号分割
        if (parts.length == 1) {
            // 检测数字编号模式 (1. xxx  2. xxx)
            if (text.matches("(?s).*\\d+[.、．].*")) {
                parts = text.split("(?=\\d+[.、．])");
            }
        }

        return Arrays.asList(parts);
    }

    /**
     * 提取关键词后的属性值
     */
    private String extractAttributeAfterKeyword(String text, String keyword) {
        // 匹配 "关键词：值" 或 "关键词为值" 等模式
        Pattern pattern = Pattern.compile(keyword + "[：:是为]?([^，。；\\n]+)");
        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
}
