package com.manga.ai.common.enums;

/**
 * 积分用途类型
 */
public enum CreditUsageType {

    VIDEO_GENERATION("video_generation", "视频生成"),
    IMAGE_GENERATION("image_generation", "图像生成"),
    SCRIPT_PARSE("script_parse", "剧本解析"),
    ROLE_CREATE("role_create", "角色创建"),
    SCENE_CREATE("scene_create", "场景创建"),
    PROP_CREATE("prop_create", "道具创建"),
    REDEEM_CODE("redeem_code", "兑换码兑换");

    private final String code;
    private final String desc;

    CreditUsageType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
