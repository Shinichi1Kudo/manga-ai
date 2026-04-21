package com.manga.ai.shot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.manga.ai.shot.entity.ShotCharacter;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分镜-角色关联 Mapper
 */
@Mapper
public interface ShotCharacterMapper extends BaseMapper<ShotCharacter> {
}
