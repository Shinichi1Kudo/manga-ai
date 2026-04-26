package com.manga.ai.shot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.manga.ai.shot.entity.Shot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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
}
