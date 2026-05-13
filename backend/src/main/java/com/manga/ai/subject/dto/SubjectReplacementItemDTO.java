package com.manga.ai.subject.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 单组替换对象
 */
@Data
public class SubjectReplacementItemDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 原视频中要替换的人物、物品或局部对象
     */
    private String sourceObject;

    /**
     * 替换类型：person 人物，object 物品
     */
    private String replacementType = "person";

    /**
     * 替换后的形象描述
     */
    private String targetDescription;

    /**
     * 参考图 OSS URL
     */
    private String referenceImageUrl;

    /**
     * 可选定位信息
     */
    private String appearTime;

    private String screenPosition;

    /**
     * 语言要求。字段名沿用 appearanceHint 以兼容历史任务和前端回显。
     */
    private String appearanceHint;
}
