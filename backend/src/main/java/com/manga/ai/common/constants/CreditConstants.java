package com.manga.ai.common.constants;

/**
 * 积分计算常量
 */
public class CreditConstants {

    // Seedance 2.0 (doubao-seedance-2-0-260128) VIP 模型
    public static final int CREDITS_PER_SECOND_480P_VIP = 15;
    public static final int CREDITS_PER_SECOND_720P_VIP = 32;
    public static final int CREDITS_PER_SECOND_1080P_VIP = 69;

    // Seedance 2.0 Fast (doubao-seedance-2-0-fast-260128) 模型
    public static final int CREDITS_PER_SECOND_480P_FAST = 12;
    public static final int CREDITS_PER_SECOND_720P_FAST = 25;

    /**
     * 默认视频时长(秒)
     */
    public static final int DEFAULT_DURATION = 5;

    /**
     * 图像生成积分：6积分/张
     */
    public static final int CREDITS_PER_IMAGE = 6;

    /**
     * 剧本解析积分：2积分/次
     */
    public static final int CREDITS_PER_SCRIPT_PARSE = 2;

    /**
     * 主体替换积分：32积分/秒
     */
    public static final int CREDITS_PER_SECOND_SUBJECT_REPLACEMENT = 32;

    // 保留旧常量名兼容（已废弃，使用按模型的方法）
    @Deprecated
    public static final int CREDITS_PER_SECOND_480P = CREDITS_PER_SECOND_480P_FAST;
    @Deprecated
    public static final int CREDITS_PER_SECOND_720P = CREDITS_PER_SECOND_720P_FAST;
    @Deprecated
    public static final int CREDITS_PER_SECOND_1080P = CREDITS_PER_SECOND_1080P_VIP;

    /**
     * 根据分辨率和时长计算所需积分（默认使用 Fast 模型）
     */
    public static int calculateCredits(String resolution, Integer duration) {
        return calculateCredits(resolution, duration, null);
    }

    /**
     * 根据分辨率、时长和模型计算所需积分
     * @param resolution 分辨率 (480p, 720p 或 1080p)
     * @param duration 时长(秒)
     * @param videoModel 视频模型 (seedance-2.0, seedance-2.0-fast, kling-v3-omni 等)，null 时默认 Fast
     * @return 所需积分
     */
    public static int calculateCredits(String resolution, Integer duration, String videoModel) {
        if (duration == null || duration <= 0) {
            duration = DEFAULT_DURATION;
        }
        int rate = getCreditsPerSecond(resolution, videoModel);
        return rate * duration;
    }

    /**
     * 根据时长计算主体替换所需积分
     * @param duration 时长(秒)
     * @return 所需积分
     */
    public static int calculateSubjectReplacementCredits(Integer duration) {
        if (duration == null || duration <= 0) {
            duration = DEFAULT_DURATION;
        }
        return CREDITS_PER_SECOND_SUBJECT_REPLACEMENT * duration;
    }

    /**
     * 获取每秒积分消耗（默认 Fast 模型）
     */
    public static int getCreditsPerSecond(String resolution) {
        return getCreditsPerSecond(resolution, null);
    }

    /**
     * 根据分辨率和模型获取每秒积分消耗
     * @param resolution 分辨率
     * @param videoModel 视频模型，null 时默认 Fast
     * @return 每秒积分
     */
    public static int getCreditsPerSecond(String resolution, String videoModel) {
        boolean isVipModel = "seedance-2.0".equals(videoModel)
                || "doubao-seedance-2-0-260128".equals(videoModel)
                || "kling-v3-omni".equals(videoModel);

        if ("480p".equals(resolution)) {
            return isVipModel ? CREDITS_PER_SECOND_480P_VIP : CREDITS_PER_SECOND_480P_FAST;
        } else if ("1080p".equals(resolution)) {
            return isVipModel ? CREDITS_PER_SECOND_1080P_VIP : CREDITS_PER_SECOND_720P_FAST;
        } else {
            return isVipModel ? CREDITS_PER_SECOND_720P_VIP : CREDITS_PER_SECOND_720P_FAST;
        }
    }
}
