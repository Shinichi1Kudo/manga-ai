package com.manga.ai.common.enums;

import lombok.Getter;

/**
 * 剧集状态枚举
 */
@Getter
public enum EpisodeStatus {

    PENDING_PARSE(0, "待解析"),
    PARSING(1, "解析中"),
    PENDING_REVIEW(2, "待审核"),
    PRODUCING(3, "制作中"),
    COMPLETED(4, "已完成");

    private final Integer code;
    private final String desc;

    EpisodeStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static EpisodeStatus getByCode(Integer code) {
        for (EpisodeStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
