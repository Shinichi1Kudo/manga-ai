package com.manga.ai.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.manga.ai.asset.entity.AssetMetadata;
import org.apache.ibatis.annotations.Mapper;

/**
 * 资产元数据 Mapper
 */
@Mapper
public interface AssetMetadataMapper extends BaseMapper<AssetMetadata> {
}
