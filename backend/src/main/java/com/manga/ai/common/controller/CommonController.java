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
}
