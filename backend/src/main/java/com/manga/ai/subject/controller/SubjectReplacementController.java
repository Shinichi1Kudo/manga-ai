package com.manga.ai.subject.controller;

import com.manga.ai.common.result.Result;
import com.manga.ai.subject.dto.SubjectReplacementCreateRequest;
import com.manga.ai.subject.dto.SubjectReplacementTaskVO;
import com.manga.ai.subject.service.SubjectReplacementService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.Serializable;
import java.util.List;

/**
 * 主体替换控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1/subject-replacements")
@RequiredArgsConstructor
public class SubjectReplacementController {

    private final SubjectReplacementService subjectReplacementService;

    /**
     * 创建主体替换任务
     */
    @PostMapping
    public Result<SubjectReplacementTaskVO> createTask(@RequestBody SubjectReplacementCreateRequest request) {
        log.info("创建主体替换任务: replacements={}", request.getReplacements() != null ? request.getReplacements().size() : 0);
        return Result.success(subjectReplacementService.createTask(request));
    }

    /**
     * 查询当前用户最近主体替换任务
     */
    @GetMapping
    public Result<List<SubjectReplacementTaskVO>> listTasks(
            @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(subjectReplacementService.listMyTasks(limit));
    }

    /**
     * 查询任务详情
     */
    @GetMapping("/{taskId}")
    public Result<SubjectReplacementTaskVO> getTask(@PathVariable Long taskId) {
        return Result.success(subjectReplacementService.getTask(taskId));
    }

    /**
     * 修改任务名称
     */
    @PostMapping("/{taskId}/name")
    public Result<SubjectReplacementTaskVO> renameTask(
            @PathVariable Long taskId,
            @RequestBody RenameTaskRequest request) {
        return Result.success(subjectReplacementService.renameTask(taskId, request != null ? request.getTaskName() : null));
    }

    /**
     * 删除任务
     */
    @DeleteMapping({"/{taskId}", "/{taskId}/delete"})
    public Result<Void> deleteTask(@PathVariable Long taskId) {
        subjectReplacementService.deleteTask(taskId);
        return Result.success();
    }

    /**
     * 上传原视频
     */
    @PostMapping("/upload-video")
    public Result<UploadResult> uploadVideo(@RequestParam("file") MultipartFile file) {
        String url = subjectReplacementService.uploadVideo(file);
        return Result.success(new UploadResult(url));
    }

    /**
     * 上传参考图
     */
    @PostMapping("/upload-reference")
    public Result<UploadResult> uploadReference(@RequestParam("file") MultipartFile file) {
        String url = subjectReplacementService.uploadReferenceImage(file);
        return Result.success(new UploadResult(url));
    }

    @Data
    public static class UploadResult implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String url;
    }

    @Data
    public static class RenameTaskRequest implements Serializable {
        private static final long serialVersionUID = 1L;

        private String taskName;
    }
}
