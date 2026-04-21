package com.manga.ai.common.enums;

import lombok.Getter;

/**
 * 分镜状态枚举
 */
@Getter
public enum ShotStatus {

    PENDING_REVIEW(0, "待审核"),
    APPROVED(1, "已通过"),
    REJECTED(2, "已拒绝");

    private final Integer code;
    private final String desc;

    ShotStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ShotStatus getByCode(Integer code) {
        for (ShotStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
