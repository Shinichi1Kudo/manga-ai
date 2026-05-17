package com.manga.ai.commerce.controller;

import com.manga.ai.commerce.dto.CommerceModelCreateRequest;
import com.manga.ai.commerce.dto.CommerceModelVO;
import com.manga.ai.commerce.dto.CommerceProductCreateRequest;
import com.manga.ai.commerce.dto.CommerceProductVO;
import com.manga.ai.commerce.dto.CommerceProjectCreateRequest;
import com.manga.ai.commerce.dto.CommerceProjectVO;
import com.manga.ai.commerce.dto.CommerceSeriesCreateRequest;
import com.manga.ai.commerce.dto.CommerceSeriesVO;
import com.manga.ai.commerce.service.CommerceService;
import com.manga.ai.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/commerce")
@RequiredArgsConstructor
public class CommerceController {

    private final CommerceService commerceService;

    @GetMapping("/series")
    public Result<List<CommerceSeriesVO>> listSeries() {
        return Result.success(commerceService.listSeries());
    }

    @PostMapping("/series")
    public Result<CommerceSeriesVO> createSeries(@RequestBody CommerceSeriesCreateRequest request) {
        return Result.success(commerceService.createSeries(request));
    }

    @GetMapping("/series/{seriesId}")
    public Result<CommerceSeriesVO> getSeries(@PathVariable Long seriesId) {
        return Result.success(commerceService.getSeries(seriesId));
    }

    @GetMapping("/series/{seriesId}/models")
    public Result<List<CommerceModelVO>> listModels(@PathVariable Long seriesId) {
        return Result.success(commerceService.listModels(seriesId));
    }

    @PostMapping("/series/{seriesId}/models")
    public Result<CommerceModelVO> createModel(
            @PathVariable Long seriesId,
            @RequestBody CommerceModelCreateRequest request) {
        return Result.success(commerceService.createModel(seriesId, request));
    }

    @PostMapping("/models/{modelId}/confirm")
    public Result<CommerceModelVO> confirmModel(@PathVariable Long modelId) {
        return Result.success(commerceService.confirmModel(modelId));
    }

    @GetMapping("/models/{modelId}/products")
    public Result<List<CommerceProductVO>> listProducts(@PathVariable Long modelId) {
        return Result.success(commerceService.listProducts(modelId));
    }

    @PostMapping("/models/{modelId}/products")
    public Result<CommerceProductVO> createProduct(
            @PathVariable Long modelId,
            @RequestBody CommerceProductCreateRequest request) {
        return Result.success(commerceService.createProduct(modelId, request));
    }

    @PostMapping("/products/{productId}/projects")
    public Result<CommerceProjectVO> createProject(
            @PathVariable Long productId,
            @RequestBody CommerceProjectCreateRequest request) {
        return Result.success(commerceService.createProject(productId, request));
    }

    @GetMapping("/projects/{projectId}")
    public Result<CommerceProjectVO> getProject(@PathVariable Long projectId) {
        return Result.success(commerceService.getProject(projectId));
    }

    @PostMapping("/projects/{projectId}/generate-video")
    public Result<CommerceProjectVO> generateProjectVideo(@PathVariable Long projectId) {
        return Result.success(commerceService.generateProjectVideo(projectId));
    }
}
