package com.manga.ai.asset.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 系列角色服装资产VO
 */
@Data
public class SeriesRoleAssetsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<RoleClothingInfo> roles;

    @Data
    public static class RoleClothingInfo implements Serializable {
        private Long id;
        private String roleName;
        private List<ClothingAssetInfo> clothings;
    }

    @Data
    public static class ClothingAssetInfo implements Serializable {
        private Integer clothingId;
        private String clothingName;
        private String assetUrl;
        private Long assetId;
        private Integer version;
        private Boolean active;
        private Boolean defaultClothing;
    }
}
