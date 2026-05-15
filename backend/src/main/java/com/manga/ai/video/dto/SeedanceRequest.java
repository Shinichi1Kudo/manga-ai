package com.manga.ai.video.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Seedance视频生成请求
 */
@Data
public class SeedanceRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 提示词
     */
    private String prompt;

    /**
     * 参考图片URL（可选，用于图生视频）- 旧格式，兼容保留
     */
    private String referenceImageUrl;

    /**
     * 多参考图列表（新API格式）
     */
    private List<ReferenceContent> contents;

    /**
     * 视频时长（秒），最大15秒
     */
    private Integer duration = 5;

    /**
     * 视频宽度
     */
    private Integer width = 1280;

    /**
     * 视频高度
     */
    private Integer height = 720;

    /**
     * 视频比例: 16:9 / 9:16 / 1:1 等
     */
    private String ratio = "16:9";

    /**
     * 是否生成音频
     */
    private Boolean generateAudio;

    /**
     * 是否添加水印
     */
    private Boolean watermark = false;

    /**
     * 种子值
     */
    private Long seed;

    /**
     * 分镜ID（用于关联）
     */
    private Long shotId;

    /**
     * 视频生成模型
     * - doubao-seedance-2-0-fast-260128: Seedance 2.0 Fast VIP
     * - doubao-seedance-2-0-260128: Seedance 2.0 VIP
     * - kling-v3-omni: Kling v3 Omni
     */
    private String model;

    /**
     * 参考内容
     */
    @Data
    public static class ReferenceContent implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * 类型: "text" 或 "image_url"
         */
        private String type;

        /**
         * 文本内容 (type="text"时)
         */
        private String text;

        /**
         * 图片URL (type="image_url"时)
         */
        private ImageUrl imageUrl;

        /**
         * 角色: "reference_image"
         */
        private String role;

        /**
         * 视频URL (type="video_url"时)
         */
        private VideoUrl videoUrl;
    }

    /**
     * 图片URL
     */
    @Data
    public static class ImageUrl implements Serializable {
        private static final long serialVersionUID = 1L;

        private String url;
    }

    /**
     * 视频URL
     */
    @Data
    public static class VideoUrl implements Serializable {
        private static final long serialVersionUID = 1L;

        private String url;
    }
}
