package com.manga.ai.shot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.manga.ai.shot.entity.VideoMetadata;
import org.apache.ibatis.annotations.Mapper;

/**
 * 视频资产元数据 Mapper
 */
@Mapper
public interface VideoMetadataMapper extends BaseMapper<VideoMetadata> {
}
