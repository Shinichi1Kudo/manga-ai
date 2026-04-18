package com.manga.ai.role.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 角色重新生成请求
 */
@Data
public class RegenerateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 要重新生成的视图类型列表
     */
    private List<String> viewTypes;

    /**
     * 服装编号
     */
    private Integer clothingId;

    /**
     * 修改的 Prompt
     */
    private String modifiedPrompt;

    /**
     * 是否保持 Seed
     */
    private Boolean keepSeed = true;

    /**
     * 是否生成新服装
     * true: 生成新服装（不改变角色状态）
     * false: 重新生成当前服装（改变角色状态为生成中）
     */
    private Boolean isNewClothing = false;
}
