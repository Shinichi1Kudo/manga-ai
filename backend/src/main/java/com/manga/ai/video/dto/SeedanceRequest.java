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
     * 种子值
     */
    private Long seed;

    /**
     * 分镜ID（用于关联）
     */
    private Long shotId;

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
    }

    /**
     * 图片URL
     */
    @Data
    public static class ImageUrl implements Serializable {
        private static final long serialVersionUID = 1L;

        private String url;
    }
}
