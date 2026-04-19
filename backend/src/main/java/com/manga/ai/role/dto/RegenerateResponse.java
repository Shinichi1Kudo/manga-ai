package com.manga.ai.role.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 角色重新生成响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegenerateResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 服装ID
     */
    private Integer clothingId;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 资产ID
     */
    private Long assetId;
}
