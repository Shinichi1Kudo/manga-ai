package com.manga.ai.shot.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 更新分镜参考图请求
 */
@Data
public class ShotReferenceUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 参考图列表
     */
    private List<ReferenceImageDTO> referenceImages;
}
