package com.manga.ai.common.enums;

import lombok.Getter;

/**
 * 任务类型枚举
 */
@Getter
public enum TaskType {

    ROLE_EXTRACT("ROLE_EXTRACT", "角色提取"),
    IMAGE_GENERATE("IMAGE_GENERATE", "图片生成"),
    BG_REMOVE("BG_REMOVE", "背景移除"),
    THUMBNAIL("THUMBNAIL", "缩略图生成");

    private final String code;
    private final String desc;

    TaskType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static TaskType getByCode(String code) {
        for (TaskType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
