package com.manga.ai.common.enums;

import lombok.Getter;

/**
 * 视图类型枚举
 */
@Getter
public enum ViewType {

    FRONT("FRONT", "正面", "front"),
    SIDE("SIDE", "侧面", "side"),
    BACK("BACK", "背面", "back"),
    THREE_QUARTER("THREE_QUARTER", "四分之三", "three_quarter");

    private final String code;
    private final String desc;
    private final String shortName;

    ViewType(String code, String desc, String shortName) {
        this.code = code;
        this.desc = desc;
        this.shortName = shortName;
    }

    public static ViewType getByCode(String code) {
        for (ViewType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
