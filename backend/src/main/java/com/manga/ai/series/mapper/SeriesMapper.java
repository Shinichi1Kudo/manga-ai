package com.manga.ai.series.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.manga.ai.series.entity.Series;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系列 Mapper
 */
@Mapper
public interface SeriesMapper extends BaseMapper<Series> {
}
