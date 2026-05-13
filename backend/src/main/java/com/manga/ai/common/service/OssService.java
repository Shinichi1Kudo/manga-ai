package com.manga.ai.common.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;
import java.util.UUID;

/**
 * 阿里云OSS服务
 */
@Slf4j
@Service
public class OssService {

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.access-key-id}")
    private String accessKeyId;

    @Value("${aliyun.oss.access-key-secret}")
    private String accessKeySecret;

    @Value("${aliyun.oss.bucket-name}")
    private String bucketName;

    @Value("${aliyun.oss.url-expiration-years:10}")
    private int urlExpirationYears;

    private OSS ossClient;

    @PostConstruct
    public void init() {
        ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        log.info("阿里云OSS客户端初始化成功: bucket={}", bucketName);
    }

    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
            log.info("阿里云OSS客户端已关闭");
        }
    }

    /**
     * 从URL下载图片并上传到OSS
     * @param imageUrl 图片URL
     * @param folder 存储文件夹（如 "characters"）
     * @return OSS文件访问URL
     */
    public String uploadImageFromUrl(String imageUrl, String folder) {
        try {
            log.info("开始从URL下载并上传图片: {}", imageUrl.substring(0, Math.min(50, imageUrl.length())) + "...");

            // 下载图片
            URL url = new URL(imageUrl);
            InputStream inputStream = url.openStream();

            // 生成文件名
            String fileName = folder + "/" + UUID.randomUUID().toString().replace("-", "") + ".png";

            // 上传到OSS
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType("image/png");
            PutObjectRequest putRequest = new PutObjectRequest(bucketName, fileName, inputStream, metadata);
            ossClient.putObject(putRequest);

            inputStream.close();

            // 生成访问URL（有效期10年）
            Date expiration = new Date(System.currentTimeMillis() + (long) urlExpirationYears * 365 * 24 * 60 * 60 * 1000L);
            URL ossUrl = ossClient.generatePresignedUrl(bucketName, fileName, expiration);

            log.info("图片上传成功: {}", fileName);
            return ossUrl.toString();

        } catch (Exception e) {
            log.error("上传图片到OSS失败", e);
            return null;
        }
    }

    /**
     * 上传字节数组到OSS
     * @param data 图片字节数据
     * @param folder 存储文件夹
     * @return OSS文件访问URL
     */
    public String uploadImage(byte[] data, String folder) {
        try {
            String fileName = folder + "/" + UUID.randomUUID().toString().replace("-", "") + ".png";

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType("image/png");
            metadata.setContentLength(data.length);

            ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
            PutObjectRequest putRequest = new PutObjectRequest(bucketName, fileName, inputStream, metadata);
            ossClient.putObject(putRequest);

            // 生成访问URL
            Date expiration = new Date(System.currentTimeMillis() + (long) urlExpirationYears * 365 * 24 * 60 * 60 * 1000L);
            URL ossUrl = ossClient.generatePresignedUrl(bucketName, fileName, expiration);

            log.info("图片上传成功: {}", fileName);
            return ossUrl.toString();

        } catch (Exception e) {
            log.error("上传图片到OSS失败", e);
            return null;
        }
    }

    /**
     * 从URL下载视频并上传到OSS
     * @param videoUrl 视频URL
     * @param folder 存储文件夹（如 "videos"）
     * @return OSS文件访问URL
     */
    public String uploadVideoFromUrl(String videoUrl, String folder) {
        try {
            log.info("开始从URL下载并上传视频: {}", videoUrl.substring(0, Math.min(50, videoUrl.length())) + "...");

            // 下载视频
            URL url = new URL(videoUrl);
            InputStream inputStream = url.openStream();

            // 生成文件名
            String fileName = folder + "/" + UUID.randomUUID().toString().replace("-", "") + ".mp4";

            // 上传到OSS
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType("video/mp4");
            PutObjectRequest putRequest = new PutObjectRequest(bucketName, fileName, inputStream, metadata);
            ossClient.putObject(putRequest);

            inputStream.close();

            // 生成访问URL（有效期10年）
            Date expiration = new Date(System.currentTimeMillis() + (long) urlExpirationYears * 365 * 24 * 60 * 60 * 1000L);
            URL ossUrl = ossClient.generatePresignedUrl(bucketName, fileName, expiration);

            log.info("视频上传成功: {}", fileName);
            return ossUrl.toString();

        } catch (Exception e) {
            log.error("上传视频到OSS失败", e);
            return null;
        }
    }

    /**
     * 上传视频字节数组到OSS
     * @param data 视频字节数据
     * @param folder 存储文件夹
     * @param contentType 视频 MIME 类型
     * @param extension 文件扩展名（不带点）
     * @return OSS文件访问URL
     */
    public String uploadVideo(byte[] data, String folder, String contentType, String extension) {
        try {
            String safeExtension = (extension == null || extension.isBlank()) ? "mp4" : extension.replace(".", "");
            String safeContentType = (contentType == null || contentType.isBlank()) ? "video/mp4" : contentType;
            String fileName = folder + "/" + UUID.randomUUID().toString().replace("-", "") + "." + safeExtension;

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(safeContentType);
            metadata.setContentLength(data.length);

            ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
            PutObjectRequest putRequest = new PutObjectRequest(bucketName, fileName, inputStream, metadata);
            ossClient.putObject(putRequest);

            // 生成访问URL
            Date expiration = new Date(System.currentTimeMillis() + (long) urlExpirationYears * 365 * 24 * 60 * 60 * 1000L);
            URL ossUrl = ossClient.generatePresignedUrl(bucketName, fileName, expiration);

            log.info("视频上传成功: {}", fileName);
            return ossUrl.toString();

        } catch (Exception e) {
            log.error("上传视频到OSS失败", e);
            return null;
        }
    }

    /**
     * 上传图片字节数组到OSS，并保留合理的MIME类型和扩展名
     * @param data 图片字节数据
     * @param folder 存储文件夹
     * @param contentType 图片 MIME 类型
     * @param extension 文件扩展名（不带点）
     * @return OSS文件访问URL
     */
    public String uploadImage(byte[] data, String folder, String contentType, String extension) {
        try {
            String safeExtension = (extension == null || extension.isBlank()) ? "png" : extension.replace(".", "");
            String safeContentType = (contentType == null || contentType.isBlank()) ? "image/png" : contentType;
            String fileName = folder + "/" + UUID.randomUUID().toString().replace("-", "") + "." + safeExtension;

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(safeContentType);
            metadata.setContentLength(data.length);

            ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
            PutObjectRequest putRequest = new PutObjectRequest(bucketName, fileName, inputStream, metadata);
            ossClient.putObject(putRequest);

            Date expiration = new Date(System.currentTimeMillis() + (long) urlExpirationYears * 365 * 24 * 60 * 60 * 1000L);
            URL ossUrl = ossClient.generatePresignedUrl(bucketName, fileName, expiration);

            log.info("图片上传成功: {}", fileName);
            return ossUrl.toString();

        } catch (Exception e) {
            log.error("上传图片到OSS失败", e);
            return null;
        }
    }

    /**
     * 获取文件的签名访问URL（有效期10年）
     * @param objectKey OSS对象键（如 contact/联系我.jpg）
     * @return 签名URL
     */
    public String getPresignedUrl(String objectKey) {
        try {
            Date expiration = new Date(System.currentTimeMillis() + (long) urlExpirationYears * 365 * 24 * 60 * 60 * 1000L);
            URL url = ossClient.generatePresignedUrl(bucketName, objectKey, expiration);
            log.info("生成签名URL成功: {}", objectKey);
            return url.toString();
        } catch (Exception e) {
            log.error("生成签名URL失败: {}", objectKey, e);
            return null;
        }
    }

    /**
     * 刷新OSS URL（生成新的签名URL）
     * 如果传入的是OSS URL，提取对象键并生成新签名
     * 如果传入的是对象键，直接生成签名URL
     * 如果不是OSS链接，原样返回
     * @param urlOrKey URL或对象键
     * @return 新的签名URL或原URL
     */
    public String refreshUrl(String urlOrKey) {
        if (urlOrKey == null || urlOrKey.isEmpty()) {
            return urlOrKey;
        }

        // 检查是否是OSS URL
        if (urlOrKey.contains("aliyuncs.com")) {
            String objectKey = extractFileNameFromUrl(urlOrKey);
            if (objectKey != null) {
                return getPresignedUrl(objectKey);
            }
        }

        // 不是OSS链接，原样返回
        return urlOrKey;
    }

    /**
     * 删除OSS文件
     * @param fileUrl 文件URL
     */
    public void deleteFile(String fileUrl) {
        try {
            // 从URL中提取文件名
            String fileName = extractFileNameFromUrl(fileUrl);
            if (fileName != null) {
                ossClient.deleteObject(bucketName, fileName);
                log.info("删除OSS文件成功: {}", fileName);
            }
        } catch (Exception e) {
            log.error("删除OSS文件失败", e);
        }
    }

    /**
     * 从URL中提取OSS文件名
     */
    private String extractFileNameFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            // URL格式: https://bucket.endpoint/folder/file.png?...
            int startIdx = url.indexOf(".aliyuncs.com/");
            if (startIdx > 0) {
                startIdx += ".aliyuncs.com/".length();
                int endIdx = url.indexOf("?", startIdx);
                if (endIdx > 0) {
                    return url.substring(startIdx, endIdx);
                } else {
                    return url.substring(startIdx);
                }
            }
        } catch (Exception e) {
            log.warn("解析OSS URL失败: {}", url);
        }
        return null;
    }
}
