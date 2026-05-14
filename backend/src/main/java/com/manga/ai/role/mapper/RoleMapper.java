package com.manga.ai.role.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.manga.ai.role.entity.Role;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 角色 Mapper
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {

    /**
     * 角色解锁只需要状态和系列ID，避免读取角色长文本字段和资产信息。
     */
    @Select("SELECT id, series_id, status FROM role WHERE id = #{roleId} AND is_deleted = 0")
    Role selectUnlockStateById(@Param("roleId") Long roleId);

    /**
     * 仅当角色仍处于可解锁状态时更新，避免并发下误改状态。
     */
    @Update("UPDATE role SET status = #{targetStatus}, updated_at = #{updatedAt} "
            + "WHERE id = #{roleId} AND is_deleted = 0 AND status IN (#{confirmedStatus}, #{lockedStatus})")
    int updateStatusIfUnlockable(@Param("roleId") Long roleId,
                                 @Param("targetStatus") Integer targetStatus,
                                 @Param("updatedAt") LocalDateTime updatedAt,
                                 @Param("confirmedStatus") Integer confirmedStatus,
                                 @Param("lockedStatus") Integer lockedStatus);
}
