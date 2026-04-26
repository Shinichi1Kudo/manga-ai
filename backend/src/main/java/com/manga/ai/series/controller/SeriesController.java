package com.manga.ai.series.controller;

import com.manga.ai.common.result.Result;
import com.manga.ai.series.dto.SeriesDetailVO;
import com.manga.ai.series.dto.SeriesInitRequest;
import com.manga.ai.series.dto.SeriesProgressVO;
import com.manga.ai.series.dto.SeriesVideoAssetsVO;
import com.manga.ai.series.service.SeriesService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系列控制器
 */
@RestController
@RequestMapping("/v1/series")
@RequiredArgsConstructor
public class SeriesController {

    private final SeriesService seriesService;

    /**
     * 初始化系列
     */
    @PostMapping("/init")
    public Result<SeriesDetailVO> initSeries(@Valid @RequestBody SeriesInitRequest request) {
        SeriesDetailVO result = seriesService.initSeries(request);
        return Result.success(result);
    }

    /**
     * 获取系列详情
     */
    @GetMapping("/{id}")
    public Result<SeriesDetailVO> getSeriesDetail(@PathVariable Long id) {
        SeriesDetailVO result = seriesService.getSeriesDetail(id);
        return Result.success(result);
    }

    /**
     * 获取系列列表（分页）
     */
    @GetMapping("/list")
    public Result<Map<String, Object>> getSeriesList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "9") Integer pageSize) {
        List<SeriesDetailVO> list = seriesService.getSeriesList(page, pageSize);
        Integer total = seriesService.getSeriesCount();

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", pageSize);

        return Result.success(result);
    }

    /**
     * 获取系列进度
     */
    @GetMapping("/{id}/progress")
    public Result<SeriesProgressVO> getSeriesProgress(@PathVariable Long id) {
        SeriesProgressVO result = seriesService.getSeriesProgress(id);
        return Result.success(result);
    }

    /**
     * 锁定系列
     */
    @PostMapping("/{id}/lock")
    public Result<Void> lockSeries(@PathVariable Long id) {
        seriesService.lockSeries(id);
        return Result.success();
    }

    /**
     * 更新系列信息
     */
    @PutMapping("/{id}")
    public Result<Void> updateSeries(@PathVariable Long id, @RequestBody Map<String, String> request) {
        String seriesName = request.get("seriesName");
        String outline = request.get("outline");
        String background = request.get("background");
        String styleKeywords = request.get("styleKeywords");
        seriesService.updateSeries(id, seriesName, outline, background, styleKeywords);
        return Result.success();
    }

    /**
     * 获取已锁定的系列列表
     */
    @GetMapping("/locked")
    public Result<List<SeriesDetailVO>> getLockedSeries() {
        List<SeriesDetailVO> result = seriesService.getLockedSeries();
        return Result.success(result);
    }

    /**
     * 软删除系列（移入回收站）
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteSeries(@PathVariable Long id) {
        seriesService.deleteSeries(id);
        return Result.success();
    }

    /**
     * 获取回收站列表
     */
    @GetMapping("/trash")
    public Result<List<SeriesDetailVO>> getTrashList() {
        List<SeriesDetailVO> result = seriesService.getTrashList();
        return Result.success(result);
    }

    /**
     * 恢复已删除的系列
     */
    @PostMapping("/{id}/restore")
    public Result<Void> restoreSeries(@PathVariable Long id) {
        seriesService.restoreSeries(id);
        return Result.success();
    }

    /**
     * 彻底删除系列
     */
    @DeleteMapping("/{id}/permanent")
    public Result<Void> permanentDeleteSeries(@PathVariable Long id) {
        seriesService.permanentDeleteSeries(id);
        return Result.success();
    }

    /**
     * 获取系列影视资产
     */
    @GetMapping("/{id}/video-assets")
    public Result<SeriesVideoAssetsVO> getSeriesVideoAssets(@PathVariable Long id) {
        SeriesVideoAssetsVO result = seriesService.getSeriesVideoAssets(id);
        return Result.success(result);
    }
}
