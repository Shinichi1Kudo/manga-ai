package com.manga.ai.common.enums;

import lombok.Getter;

/**
 * 道具状态枚举
 */
@Getter
public enum PropStatus {

    GENERATING(0, "生成中"),
    PENDING_REVIEW(1, "待审核"),
    LOCKED(3, "已锁定");

    private final Integer code;
    private final String desc;

    PropStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static PropStatus getByCode(Integer code) {
        for (PropStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
