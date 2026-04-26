package com.manga.ai.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.manga.ai.user.entity.CreditRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 积分记录Mapper
 */
@Mapper
public interface CreditRecordMapper extends BaseMapper<CreditRecord> {

    /**
     * 查询用户最近的积分记录
     */
    @Select("SELECT * FROM credit_record WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT #{limit}")
    List<CreditRecord> selectRecentByUserId(@Param("userId") Long userId, @Param("limit") Integer limit);
}
