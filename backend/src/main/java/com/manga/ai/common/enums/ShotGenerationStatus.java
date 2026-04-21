package com.manga.ai.common.enums;

import lombok.Getter;

/**
 * 分镜视频生成状态枚举
 */
@Getter
public enum ShotGenerationStatus {

    PENDING(0, "待生成"),
    GENERATING(1, "生成中"),
    COMPLETED(2, "已完成"),
    FAILED(3, "生成失败");

    private final Integer code;
    private final String desc;

    ShotGenerationStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ShotGenerationStatus getByCode(Integer code) {
        for (ShotGenerationStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
