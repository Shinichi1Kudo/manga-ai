package com.manga.ai.common.enums;

import lombok.Getter;

/**
 * 系列状态枚举
 */
@Getter
public enum SeriesStatus {

    INITIALIZING(0, "初始化中"),
    PENDING_REVIEW(1, "待审核"),
    LOCKED(2, "已锁定");

    private final Integer code;
    private final String desc;

    SeriesStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static SeriesStatus getByCode(Integer code) {
        for (SeriesStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
