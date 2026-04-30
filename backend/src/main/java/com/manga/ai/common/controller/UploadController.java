package com.manga.ai.common.controller;

import com.manga.ai.common.dto.Result;
import com.manga.ai.common.service.OssService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 文件上传控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class UploadController {

    private final OssService ossService;

    // 允许的图片类型
    private static final List<String> ALLOWED_IMAGE_TYPES = Arrays.asList(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );

    // 最大文件大小 5MB
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    /**
     * 上传文件
     */
    @PostMapping("/upload")
    public Result<UploadResult> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "type", defaultValue = "avatar") String type) {

        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            return Result.error("未登录");
        }

        // 验证文件
        if (file.isEmpty()) {
            return Result.error("文件不能为空");
        }

        // 验证文件类型
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            return Result.error("只支持 JPG、PNG、GIF、WEBP 格式的图片");
        }

        // 验证文件大小
        if (file.getSize() > MAX_FILE_SIZE) {
            return Result.error("文件大小不能超过5MB");
        }

        try {
            // 上传到OSS
            byte[] data = file.getBytes();
            String folder = "avatars";
            if ("scene".equals(type)) {
                folder = "scenes";
            } else if ("prop".equals(type)) {
                folder = "props";
            }

            String url = ossService.uploadImage(data, folder);
            if (url == null) {
                return Result.error("上传失败");
            }

            log.info("文件上传成功: userId={}, type={}, url={}", userId, type, url);

            UploadResult result = new UploadResult();
            result.setUrl(url);
            return Result.success(result);

        } catch (IOException e) {
            log.error("读取文件失败", e);
            return Result.error("读取文件失败");
        }
    }

    /**
     * 上传结果
     */
    @lombok.Data
    public static class UploadResult {
        private String url;
    }
}
