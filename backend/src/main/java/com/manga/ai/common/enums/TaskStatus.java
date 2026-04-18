package com.manga.ai.common.enums;

import lombok.Getter;

/**
 * 任务状态枚举
 */
@Getter
public enum TaskStatus {

    PENDING(0, "待处理"),
    PROCESSING(1, "处理中"),
    SUCCESS(2, "成功"),
    FAILED(3, "失败");

    private final Integer code;
    private final String desc;

    TaskStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static TaskStatus getByCode(Integer code) {
        for (TaskStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
