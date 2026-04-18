package com.manga.ai.common.enums;

import lombok.Getter;

/**
 * 资产状态枚举
 */
@Getter
public enum AssetStatus {

    GENERATING(0, "生成中"),
    PENDING_REVIEW(1, "待审核"),
    CONFIRMED(2, "已确认"),
    LOCKED(3, "已锁定");

    private final Integer code;
    private final String desc;

    AssetStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static AssetStatus getByCode(Integer code) {
        for (AssetStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
