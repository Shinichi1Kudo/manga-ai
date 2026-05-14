package com.manga.ai.series.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.manga.ai.series.dto.SeriesDetailVO;
import com.manga.ai.series.entity.Series;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 系列 Mapper
 */
@Mapper
public interface SeriesMapper extends BaseMapper<Series> {

    /**
     * 首页系列卡片列表：只返回卡片需要的轻量字段和角色数量。
     */
    List<SeriesDetailVO> selectSeriesListCards(@Param("userId") Long userId,
                                               @Param("limit") Integer limit,
                                               @Param("offset") Integer offset);

    /**
     * 查询回收站列表（按用户过滤）
     */
    @Select("SELECT * FROM series WHERE is_deleted = 1 AND user_id = #{userId} ORDER BY deleted_at DESC")
    List<Series> selectTrashListByUserId(Long userId);

    /**
     * 查询包含已删除的系列（绕过逻辑删除）
     */
    @Select("SELECT * FROM series WHERE id = #{id}")
    Series selectByIdIncludeDeleted(Long id);

    /**
     * 真实删除（绕过逻辑删除）
     */
    @Delete("DELETE FROM series WHERE id = #{id}")
    void realDeleteById(Long id);

    /**
     * 删除过期的回收站系列
     */
    @Delete("DELETE FROM series WHERE is_deleted = 1 AND deleted_at < #{expireTime}")
    void deleteExpiredSeries(LocalDateTime expireTime);

    /**
     * 软删除系列（移入回收站）
     */
    @Update("UPDATE series SET is_deleted = 1, deleted_at = #{deletedAt}, updated_at = NOW() WHERE id = #{id}")
    void softDeleteById(Long id, LocalDateTime deletedAt);

    /**
     * 恢复已删除的系列
     */
    @Update("UPDATE series SET is_deleted = 0, deleted_at = NULL, updated_at = NOW() WHERE id = #{id}")
    void restoreById(Long id);

    /**
     * 角色解锁后只在系列仍为已锁定时改回待审核，避免读取整条系列长文本。
     */
    @Update("UPDATE series SET status = #{targetStatus}, updated_at = #{updatedAt} "
            + "WHERE id = #{seriesId} AND is_deleted = 0 AND status = #{lockedStatus}")
    int markLockedSeriesPendingReview(@Param("seriesId") Long seriesId,
                                      @Param("targetStatus") Integer targetStatus,
                                      @Param("lockedStatus") Integer lockedStatus,
                                      @Param("updatedAt") LocalDateTime updatedAt);
}
