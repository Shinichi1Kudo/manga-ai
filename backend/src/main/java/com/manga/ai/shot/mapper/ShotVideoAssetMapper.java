package com.manga.ai.shot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.manga.ai.shot.entity.ShotVideoAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 分镜视频资产版本 Mapper
 */
@Mapper
public interface ShotVideoAssetMapper extends BaseMapper<ShotVideoAsset> {

    /**
     * 获取分镜的所有视频版本
     */
    @Select("SELECT * FROM shot_video_asset WHERE shot_id = #{shotId} ORDER BY version DESC")
    List<ShotVideoAsset> selectByShotId(@Param("shotId") Long shotId);

    /**
     * 获取分镜当前激活的视频
     */
    @Select("SELECT * FROM shot_video_asset WHERE shot_id = #{shotId} AND is_active = 1")
    ShotVideoAsset selectActiveByShotId(@Param("shotId") Long shotId);

    /**
     * 批量获取分镜当前激活的视频
     */
    @Select({
            "<script>",
            "SELECT * FROM shot_video_asset",
            "WHERE is_active = 1 AND shot_id IN",
            "<foreach collection='shotIds' item='shotId' open='(' separator=',' close=')'>",
            "#{shotId}",
            "</foreach>",
            "</script>"
    })
    List<ShotVideoAsset> selectActiveByShotIds(@Param("shotIds") List<Long> shotIds);

    /**
     * 获取分镜最大版本号
     */
    @Select("SELECT MAX(version) FROM shot_video_asset WHERE shot_id = #{shotId}")
    Integer selectMaxVersion(@Param("shotId") Long shotId);

    /**
     * 将所有版本设为非激活
     */
    @Update("UPDATE shot_video_asset SET is_active = 0 WHERE shot_id = #{shotId}")
    int deactivateAllByShotId(@Param("shotId") Long shotId);
}
