package com.manga.ai.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.manga.ai.asset.entity.RoleAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 角色资产 Mapper
 */
@Mapper
public interface RoleAssetMapper extends BaseMapper<RoleAsset> {

    /**
     * 获取最大版本号
     */
    @Select("SELECT MAX(version) FROM role_asset WHERE role_id = #{roleId} AND view_type = #{viewType} AND clothing_id = #{clothingId}")
    Integer getMaxVersion(@Param("roleId") Long roleId, @Param("viewType") String viewType, @Param("clothingId") Integer clothingId);

    /**
     * 取消激活旧版本
     */
    @Update("UPDATE role_asset SET is_active = 0 WHERE role_id = #{roleId} AND view_type = #{viewType} AND clothing_id = #{clothingId} AND id != #{excludeId}")
    int deactivateOtherVersions(@Param("roleId") Long roleId, @Param("viewType") String viewType, @Param("clothingId") Integer clothingId, @Param("excludeId") Long excludeId);

    /**
     * 统计角色当前可用于审核确认的激活图片数量。
     */
    @Select("SELECT COUNT(*) FROM role_asset "
            + "WHERE role_id = #{roleId} AND is_active = 1 "
            + "AND status IN (1, 2, 3) "
            + "AND file_path IS NOT NULL AND file_path <> ''")
    Long countUsableActiveAssetsByRoleId(@Param("roleId") Long roleId);

    /**
     * 统计系列中缺少可用激活图片的角色数量。
     */
    @Select("SELECT COUNT(*) FROM role r "
            + "WHERE r.series_id = #{seriesId} AND r.is_deleted = 0 "
            + "AND NOT EXISTS ("
            + "  SELECT 1 FROM role_asset ra "
            + "  WHERE ra.role_id = r.id AND ra.is_active = 1 "
            + "  AND ra.status IN (1, 2, 3) "
            + "  AND ra.file_path IS NOT NULL AND ra.file_path <> ''"
            + ")")
    Long countRolesWithoutUsableActiveAssetBySeriesId(@Param("seriesId") Long seriesId);
}
