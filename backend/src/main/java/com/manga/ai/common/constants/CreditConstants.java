package com.manga.ai.common.constants;

/**
 * 积分计算常量
 */
public class CreditConstants {

    /**
     * 高清(480p)每秒积分消耗
     */
    public static final int CREDITS_PER_SECOND_480P = 12;

    /**
     * 超清(720p)每秒积分消耗
     */
    public static final int CREDITS_PER_SECOND_720P = 23;

    /**
     * 4K(1080p)每秒积分消耗 - 仅 Seedance 2.0 VIP 支持
     */
    public static final int CREDITS_PER_SECOND_1080P = 50;

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
     * 根据分辨率和时长计算所需积分
     * @param resolution 分辨率 (480p, 720p 或 1080p)
     * @param duration 时长(秒)
     * @return 所需积分
     */
    public static int calculateCredits(String resolution, Integer duration) {
        if (duration == null || duration <= 0) {
            duration = DEFAULT_DURATION;
        }
        int rate = getCreditsPerSecond(resolution);
        return rate * duration;
    }

    /**
     * 获取每秒积分消耗
     * @param resolution 分辨率
     * @return 每秒积分
     */
    public static int getCreditsPerSecond(String resolution) {
        if ("480p".equals(resolution)) {
            return CREDITS_PER_SECOND_480P;
        } else if ("1080p".equals(resolution)) {
            return CREDITS_PER_SECOND_1080P;
        } else {
            return CREDITS_PER_SECOND_720P;
        }
    }
}
