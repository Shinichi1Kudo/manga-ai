package com.manga.ai.common.enums;

import lombok.Getter;

/**
 * 角色状态枚举
 */
@Getter
public enum RoleStatus {

    EXTRACTING(0, "提取中"),
    PENDING_REVIEW(1, "待审核"),
    CONFIRMED(2, "已确认"),
    LOCKED(3, "已锁定");

    private final Integer code;
    private final String desc;

    RoleStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static RoleStatus getByCode(Integer code) {
        for (RoleStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
