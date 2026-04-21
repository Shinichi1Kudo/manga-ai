package com.manga.ai.episode.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.manga.ai.episode.entity.Episode;
import org.apache.ibatis.annotations.Mapper;

/**
 * 剧集 Mapper
 */
@Mapper
public interface EpisodeMapper extends BaseMapper<Episode> {
}
