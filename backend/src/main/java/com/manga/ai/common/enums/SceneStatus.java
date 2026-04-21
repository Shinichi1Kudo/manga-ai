package com.manga.ai.common.enums;

import lombok.Getter;

/**
 * 场景状态枚举
 */
@Getter
public enum SceneStatus {

    GENERATING(0, "生成中"),
    PENDING_REVIEW(1, "待审核"),
    CONFIRMED(2, "已确认"),
    LOCKED(3, "已锁定");

    private final Integer code;
    private final String desc;

    SceneStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static SceneStatus getByCode(Integer code) {
        for (SceneStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
