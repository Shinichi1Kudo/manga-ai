package com.manga.ai.shot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.manga.ai.shot.entity.Shot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
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
            "WHERE episode_id = #{episodeId} AND shot_number > #{deletedNumber} " +
            "AND status <> #{lockedStatus} AND is_deleted = 0")
    int decrementUnlockedShotNumbers(@Param("episodeId") Long episodeId,
                                     @Param("deletedNumber") Integer deletedNumber,
                                     @Param("lockedStatus") Integer lockedStatus);

    /**
     * 将指定编号之后的已锁定分镜编号-1（解锁时使用）。
     */
    @Update("UPDATE shot SET shot_number = shot_number - 1, updated_at = NOW() " +
            "WHERE episode_id = #{episodeId} AND shot_number > #{deletedNumber} " +
            "AND status = #{lockedStatus} AND is_deleted = 0")
    int decrementLockedShotNumbers(@Param("episodeId") Long episodeId,
                                   @Param("deletedNumber") Integer deletedNumber,
                                   @Param("lockedStatus") Integer lockedStatus);

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
    @Select("SELECT id, episode_id, shot_number, status FROM shot WHERE episode_id = #{episodeId} AND is_deleted = 0")
    List<Shot> selectOrderFieldsByEpisodeId(@Param("episodeId") Long episodeId);

    /**
     * 查询剧集详情页分镜列表所需字段，避免列表入口读取生成提示词等大字段。
     */
    @Select("SELECT id, episode_id, shot_number, shot_name, scene_id, scene_name, description, " +
            "camera_angle, camera_movement, shot_type, start_time, end_time, duration, " +
            "resolution, aspect_ratio, sound_effect, video_url, thumbnail_url, " +
            "generation_status, generation_error, generation_duration, generation_start_time, " +
            "status, video_model, description_edited, scene_edited, created_at, updated_at " +
            "FROM shot WHERE episode_id = #{episodeId} AND is_deleted = 0 " +
            "ORDER BY status ASC, shot_number ASC")
    List<Shot> selectEpisodeDetailList(@Param("episodeId") Long episodeId);

    /**
     * 查询剧集下待审核分镜最大的排序号，用于解锁后放到待审核列表末尾。
     */
    @Select("SELECT COALESCE(MAX(shot_number), 0) FROM shot WHERE episode_id = #{episodeId} AND status <> #{lockedStatus} AND is_deleted = 0")
    Integer selectMaxUnlockedShotNumber(@Param("episodeId") Long episodeId, @Param("lockedStatus") Integer lockedStatus);

    /**
     * 查询剧集下已锁定分镜最大的排序号，用于锁定后放到已锁定列表末尾。
     */
    @Select("SELECT COALESCE(MAX(shot_number), 0) FROM shot WHERE episode_id = #{episodeId} AND status = #{lockedStatus} AND is_deleted = 0")
    Integer selectMaxLockedShotNumber(@Param("episodeId") Long episodeId, @Param("lockedStatus") Integer lockedStatus);

    /**
     * 将超过兜底时间仍处于生成中的分镜恢复为待生成，避免异步任务中断后页面永久卡住。
     */
    @Update("UPDATE shot SET generation_status = #{pendingStatus}, generation_error = NULL, " +
            "generation_start_time = NULL, deducted_credits = NULL, updated_at = #{now} " +
            "WHERE generation_status = #{generatingStatus} " +
            "AND generation_start_time IS NOT NULL " +
            "AND generation_start_time < #{timeout} " +
            "AND is_deleted = 0")
    int restoreStuckGeneratingShots(@Param("pendingStatus") Integer pendingStatus,
                                    @Param("generatingStatus") Integer generatingStatus,
                                    @Param("timeout") LocalDateTime timeout,
                                    @Param("now") LocalDateTime now);
}
