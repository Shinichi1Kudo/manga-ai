package com.manga.ai.user.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TencentSmsSenderTest {

    @Test
    void sendVerificationCodeDoesNotSetRestrictedHostHeader() throws Exception {
        TencentSmsSender sender = new TencentSmsSender();
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"Response":{"SendStatusSet":[{"Code":"Ok","Message":"send success"}],"RequestId":"req-1"}}
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        ReflectionTestUtils.setField(sender, "httpClient", httpClient);
        ReflectionTestUtils.setField(sender, "secretId", "AKID_TEST");
        ReflectionTestUtils.setField(sender, "secretKey", "SECRET_TEST");
        ReflectionTestUtils.setField(sender, "sdkAppId", "1400632889");
        ReflectionTestUtils.setField(sender, "signName", "北京乙帧科技有限公司");
        ReflectionTestUtils.setField(sender, "loginTemplateId", "2645843");
        ReflectionTestUtils.setField(sender, "resetTemplateId", "2647581");
        ReflectionTestUtils.setField(sender, "region", "ap-guangzhou");
        ReflectionTestUtils.setField(sender, "codeExpireMinutes", "10");

        assertThatCode(() -> sender.sendVerificationCode("17700008623", "123456", "login"))
                .doesNotThrowAnyException();

        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        HttpRequest request = ((HttpRequest) org.mockito.Mockito.mockingDetails(httpClient)
                .getInvocations()
                .iterator()
                .next()
                .getArgument(0));
        assertThat(request.headers().firstValue("Host")).isEmpty();
        assertThat(request.headers().firstValue("Authorization")).hasValueSatisfying(value ->
                assertThat(value).contains("SignedHeaders=content-type;host"));
    }
}
