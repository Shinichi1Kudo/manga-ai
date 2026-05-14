package com.manga.ai.gptimage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.manga.ai.gptimage.entity.GptImage2Task;
import org.apache.ibatis.annotations.Mapper;

/**
 * GPT-Image2 图片生成任务 Mapper
 */
@Mapper
public interface GptImage2TaskMapper extends BaseMapper<GptImage2Task> {
}
