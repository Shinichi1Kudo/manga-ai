package com.manga.ai.series.service;

import com.manga.ai.series.dto.SeriesDetailVO;
import com.manga.ai.series.dto.SeriesInitRequest;
import com.manga.ai.series.dto.SeriesProgressVO;

import java.util.List;

/**
 * 系列服务接口
 */
public interface SeriesService {

    /**
     * 初始化系列
     */
    SeriesDetailVO initSeries(SeriesInitRequest request);

    /**
     * 获取系列详情
     */
    SeriesDetailVO getSeriesDetail(Long seriesId);

    /**
     * 获取系列列表（分页）
     */
    List<SeriesDetailVO> getSeriesList(Integer page, Integer pageSize);

    /**
     * 获取系列总数
     */
    Integer getSeriesCount();

    /**
     * 获取所有系列列表（不分页）
     */
    List<SeriesDetailVO> getAllSeries();

    /**
     * 获取系列进度
     */
    SeriesProgressVO getSeriesProgress(Long seriesId);

    /**
     * 更新系列状态
     */
    void updateSeriesStatus(Long seriesId, Integer status);

    /**
     * 锁定系列
     */
    void lockSeries(Long seriesId);

    /**
     * 异步处理角色数据（并行生成图片）
     */
    void asyncProcessCharacters(Long seriesId, String charactersJson);

    /**
     * 异步处理角色提取（NLP方式）
     */
    void asyncProcessRoleExtract(Long seriesId, String characterIntro);
}
