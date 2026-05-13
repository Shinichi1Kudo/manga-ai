package com.manga.ai.subject.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 创建主体替换任务请求
 */
@Data
public class SubjectReplacementCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskName;

    private String originalVideoUrl;

    private String aspectRatio = "16:9";

    private Integer duration = 5;

    private Boolean generateAudio = true;

    private Boolean watermark = false;

    private List<SubjectReplacementItemDTO> replacements;
}
