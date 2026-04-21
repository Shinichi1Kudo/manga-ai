package com.manga.ai.shot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.manga.ai.shot.entity.ShotProp;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分镜-道具关联 Mapper
 */
@Mapper
public interface ShotPropMapper extends BaseMapper<ShotProp> {
}
