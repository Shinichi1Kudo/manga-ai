package com.manga.ai.common.utils;

import com.manga.ai.common.enums.ViewType;
import com.manga.ai.role.entity.Role;

/**
 * Prompt 构建工具
 */
public class PromptBuilder {

    private static final String BASE_TEMPLATE = "A character design sheet of %s, %s years old, %s";
    private static final String QUALITY_TAGS = ", high quality, detailed, consistent style, character reference sheet, white background, full body";

    /**
     * 构建多视图 Prompt
     */
    public static String buildMultiViewPrompt(Role role, ViewType viewType) {
        StringBuilder prompt = new StringBuilder();

        // 基础描述
        prompt.append(String.format(BASE_TEMPLATE,
                role.getRoleName(),
                role.getAge() != null ? role.getAge() : "unknown age",
                role.getGender() != null ? role.getGender() : "person"
        ));

        // 外貌描述
        if (role.getAppearance() != null && !role.getAppearance().isEmpty()) {
            prompt.append(", ").append(role.getAppearance());
        }

        // 服装描述
        if (role.getClothing() != null && !role.getClothing().isEmpty()) {
            prompt.append(", wearing ").append(role.getClothing());
        }

        // 视图特定描述
        prompt.append(", ").append(getViewDescription(viewType));

        // 质量标签
        prompt.append(QUALITY_TAGS);

        return prompt.toString();
    }

    /**
     * 获取视图描述
     */
    private static String getViewDescription(ViewType viewType) {
        switch (viewType) {
            case FRONT:
                return "front view, facing camera, A-pose standing, arms slightly raised, symmetrical";
            case SIDE:
                return "side view, profile, standing, 90 degree angle, full body";
            case BACK:
                return "back view, from behind, standing, full body";
            case THREE_QUARTER:
                return "three-quarter view, 45 degree angle, slight turn, full body";
            default:
                return "";
        }
    }

    /**
     * 获取负向 Prompt
     */
    public static String getNegativePrompt() {
        return "low quality, bad anatomy, blurry, cropped, watermark, text, signature, " +
               "complex background, environment, shadows on background, multiple people, " +
               "distorted, deformed, ugly, bad proportions";
    }
}
