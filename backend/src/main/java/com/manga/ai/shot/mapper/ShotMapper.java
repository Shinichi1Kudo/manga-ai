package com.manga.ai.shot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.manga.ai.shot.entity.Shot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 分镜 Mapper
 */
@Mapper
public interface ShotMapper extends BaseMapper<Shot> {

    /**
     * 将指定编号及之后的分镜编号+1（插入新分镜时使用）
     */
    @Update("UPDATE shot SET shot_number = shot_number + 1, updated_at = NOW() " +
            "WHERE episode_id = #{episodeId} AND shot_number >= #{fromNumber} AND is_deleted = 0")
    int incrementShotNumbers(@Param("episodeId") Long episodeId, @Param("fromNumber") Integer fromNumber);

    /**
     * 将指定编号之后的分镜编号-1（删除分镜时使用）
     */
    @Update("UPDATE shot SET shot_number = shot_number - 1, updated_at = NOW() " +
            "WHERE episode_id = #{episodeId} AND shot_number > #{deletedNumber} AND is_deleted = 0")
    int decrementShotNumbers(@Param("episodeId") Long episodeId, @Param("deletedNumber") Integer deletedNumber);

    /**
     * 批量更新分镜编号，避免拖拽排序时逐条更新导致接口变慢。
     */
    @Update({
            "<script>",
            "UPDATE shot",
            "SET shot_number = CASE id",
            "<foreach collection='shots' item='shot'>",
            "  WHEN #{shot.id} THEN #{shot.shotNumber}",
            "</foreach>",
            "END, updated_at = NOW()",
            "WHERE episode_id = #{episodeId}",
            "AND is_deleted = 0",
            "AND id IN",
            "<foreach collection='shots' item='shot' open='(' separator=',' close=')'>",
            "  #{shot.id}",
            "</foreach>",
            "</script>"
    })
    int batchUpdateShotNumbers(@Param("episodeId") Long episodeId, @Param("shots") List<Shot> shots);

    /**
     * 只更新分镜名称。前端传空名时需要显式写入 NULL。
     */
    @Update("UPDATE shot SET shot_name = #{shotName}, updated_at = NOW() WHERE id = #{shotId} AND is_deleted = 0")
    int updateShotName(@Param("shotId") Long shotId, @Param("shotName") String shotName);

    /**
     * 查询剧集下分镜的最小字段，用于快速校验排序请求。
     */
    @Select("SELECT id, episode_id, shot_number FROM shot WHERE episode_id = #{episodeId} AND is_deleted = 0")
    List<Shot> selectOrderFieldsByEpisodeId(@Param("episodeId") Long episodeId);
}
