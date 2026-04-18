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
}
