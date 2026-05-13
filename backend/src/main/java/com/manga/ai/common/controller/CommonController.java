package com.manga.ai.common.controller;

import com.manga.ai.common.dto.Result;
import com.manga.ai.common.service.OssService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 通用控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1/common")
@RequiredArgsConstructor
public class CommonController {

    private final OssService ossService;

    /**
     * 获取联系我们二维码图片URL
     */
    @GetMapping("/contact-image")
    public Result<Map<String, String>> getContactImage() {
        String objectKey = "contact/联系我.jpg";
        String url = ossService.getPresignedUrl(objectKey);
        if (url != null) {
            return Result.success(Map.of("url", url));
        } else {
            return Result.error("获取图片失败");
        }
    }

    /**
     * 获取首页主体替换演示素材URL
     */
    @GetMapping("/showcase-assets")
    public Result<Map<String, String>> getShowcaseAssets() {
        String beforeVideoUrl = ossService.getPresignedUrl("showcase/subject-replacement/before.mp4");
        String referenceImageUrl = ossService.getPresignedUrl("showcase/subject-replacement/reference-model.png");
        String afterVideoUrl = ossService.getPresignedUrl("showcase/subject-replacement/after.mp4");

        if (beforeVideoUrl != null && referenceImageUrl != null && afterVideoUrl != null) {
            return Result.success(Map.of(
                    "beforeVideoUrl", beforeVideoUrl,
                    "referenceImageUrl", referenceImageUrl,
                    "afterVideoUrl", afterVideoUrl
            ));
        }
        return Result.error("获取展示素材失败");
    }
}
