package com.manga.ai.common.service;

import com.aliyun.oss.OSS;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssServiceTest {

    @Test
    void getPresignedUrlUpgradesOssHttpUrlToHttps() throws Exception {
        OssService service = new OssService();
        OSS ossClient = mock(OSS.class);
        when(ossClient.generatePresignedUrl(eq("movie-agent"), eq("contact/qr.png"), any(Date.class)))
                .thenReturn(new URL("http://movie-agent.oss-cn-beijing.aliyuncs.com/contact/qr.png?Expires=1"));
        setField(service, "ossClient", ossClient);
        setField(service, "bucketName", "movie-agent");
        setField(service, "urlExpirationYears", 10);

        String url = service.getPresignedUrl("contact/qr.png");

        assertThat(url).startsWith("https://movie-agent.oss-cn-beijing.aliyuncs.com/contact/qr.png");
    }

    @Test
    void uploadImageToKeyStoresStableObjectKey() throws Exception {
        OssService service = new OssService();
        OSS ossClient = mock(OSS.class);
        when(ossClient.generatePresignedUrl(eq("movie-agent"), eq("brand/site-logo.png"), any(Date.class)))
                .thenReturn(new URL("http://movie-agent.oss-cn-beijing.aliyuncs.com/brand/site-logo.png?Expires=1"));
        setField(service, "ossClient", ossClient);
        setField(service, "bucketName", "movie-agent");
        setField(service, "urlExpirationYears", 10);

        String url = service.uploadImageToKey(new byte[]{1, 2, 3}, "brand/site-logo.png", "image/png");

        assertThat(url).startsWith("https://movie-agent.oss-cn-beijing.aliyuncs.com/brand/site-logo.png");
        verify(ossClient).putObject(eq("movie-agent"), eq("brand/site-logo.png"), any(ByteArrayInputStream.class), any());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
