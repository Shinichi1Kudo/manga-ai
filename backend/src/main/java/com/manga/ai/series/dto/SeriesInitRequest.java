package com.manga.ai.series.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 系列初始化请求
 */
@Data
public class SeriesInitRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 系列名称
     */
    @NotBlank(message = "系列名称不能为空")
    @Size(max = 100, message = "系列名称不能超过100字符")
    private String seriesName;

    /**
     * 剧本大纲
     */
    @NotBlank(message = "剧本大纲不能为空")
    @Size(max = 10000, message = "剧本大纲不能超过10000字符")
    private String outline;

    /**
     * 背景设定
     */
    @Size(max = 10000, message = "背景设定不能超过10000字符")
    private String background;

    /**
     * 人物介绍 (旧格式，保留兼容)
     */
    @Size(max = 50000, message = "人物介绍不能超过50000字符")
    private String characterIntro;

    /**
     * 角色列表 JSON (新格式)
     */
    private String charactersJson;

    /**
     * 风格关键词
     */
    @Size(max = 500, message = "风格关键词不能超过500字符")
    private String styleKeywords;

    /**
     * 色调偏好
     */
    @Size(max = 200, message = "色调偏好不能超过200字符")
    private String colorPreference;

    /**
     * 美术风格参考
     */
    @Size(max = 500, message = "美术风格参考不能超过500字符")
    private String artStyleRef;

    /**
     * 图片比例: 16:9 / 3:4
     */
    private String aspectRatio = "3:4";

    /**
     * 清晰度: standard / hd / ultra
     */
    private String quality = "hd";

    /**
     * 系列风格: 3d_anime, realistic, anime_jp, cartoon_us, watercolor, oil_painting, cyberpunk, chinese_style, pixel_art, chibi
     */
    @NotBlank(message = "系列风格不能为空")
    private String seriesStyle;
}
