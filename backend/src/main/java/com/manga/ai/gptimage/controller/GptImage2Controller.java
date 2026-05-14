package com.manga.ai.gptimage.controller;

import com.manga.ai.common.result.Result;
import com.manga.ai.gptimage.dto.GptImage2GenerateRequest;
import com.manga.ai.gptimage.dto.GptImage2GenerateResponse;
import com.manga.ai.gptimage.dto.GptImage2UploadResult;
import com.manga.ai.gptimage.service.GptImage2Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * GPT-Image2 图片生成控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1/gpt-image2")
@RequiredArgsConstructor
public class GptImage2Controller {

    private final GptImage2Service gptImage2Service;

    @PostMapping("/generate")
    public Result<GptImage2GenerateResponse> generate(@RequestBody GptImage2GenerateRequest request) {
        log.info("GPT-Image2生成请求: hasReference={}", request != null && hasText(request.getReferenceImageUrl()));
        return Result.success(gptImage2Service.generate(request));
    }

    @GetMapping("")
    public Result<List<GptImage2GenerateResponse>> listTasks(@RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(gptImage2Service.listMyTasks(limit));
    }

    @GetMapping("/{taskId}")
    public Result<GptImage2GenerateResponse> getTask(@PathVariable Long taskId) {
        return Result.success(gptImage2Service.getTask(taskId));
    }

    @GetMapping("/latest")
    public Result<GptImage2GenerateResponse> getLatestTask() {
        return Result.success(gptImage2Service.getLatestTask());
    }

    @PostMapping("/upload-reference")
    public Result<GptImage2UploadResult> uploadReference(@RequestParam("file") MultipartFile file) {
        return Result.success(new GptImage2UploadResult(gptImage2Service.uploadReference(file)));
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
