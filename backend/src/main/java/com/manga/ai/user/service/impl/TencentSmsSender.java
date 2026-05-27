package com.manga.ai.user.service.impl;

import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.user.service.SmsSender;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 腾讯云短信验证码发送器。
 */
@Slf4j
@Service
public class TencentSmsSender implements SmsSender {

    private static final String HOST = "sms.tencentcloudapi.com";
    private static final String ENDPOINT = "https://" + HOST;
    private static final String SERVICE = "sms";
    private static final String ACTION = "SendSms";
    private static final String VERSION = "2021-01-11";
    private static final String ALGORITHM = "TC3-HMAC-SHA256";

    @Value("${tencent.sms.secret-id:}")
    private String secretId;

    @Value("${tencent.sms.secret-key:}")
    private String secretKey;

    @Value("${tencent.sms.sdk-app-id:}")
    private String sdkAppId;

    @Value("${tencent.sms.sign-name:}")
    private String signName;

    @Value("${tencent.sms.login-template-id:}")
    private String loginTemplateId;

    @Value("${tencent.sms.reset-template-id:}")
    private String resetTemplateId;

    @Value("${tencent.sms.region:ap-guangzhou}")
    private String region;

    @Value("${tencent.sms.code-expire-minutes:10}")
    private String codeExpireMinutes;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public void sendVerificationCode(String phone, String code, String type) {
        if (isBlank(secretId) || isBlank(secretKey) || isBlank(sdkAppId) || isBlank(signName) || isBlank(loginTemplateId)) {
            throw new BusinessException("短信服务未配置，请联系管理员");
        }

        String templateId = "reset".equals(type) ? resetTemplateId : loginTemplateId;
        if (isBlank(templateId)) {
            throw new BusinessException("短信模板未配置，请联系管理员");
        }

        JSONObject payload = new JSONObject();
        payload.put("PhoneNumberSet", JSONArray.of(toE164Phone(phone)));
        payload.put("SmsSdkAppId", sdkAppId);
        payload.put("SignName", signName);
        payload.put("TemplateId", templateId);
        payload.put("TemplateParamSet", "reset".equals(type)
                ? JSONArray.of(code)
                : JSONArray.of(code, codeExpireMinutes));

        try {
            String body = payload.toJSONString();
            long timestamp = Instant.now().getEpochSecond();
            String authorization = buildAuthorization(body, timestamp);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-TC-Action", ACTION)
                    .header("X-TC-Timestamp", String.valueOf(timestamp))
                    .header("X-TC-Version", VERSION)
                    .header("X-TC-Region", region)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("腾讯云短信请求失败: status={}, body={}", response.statusCode(), response.body());
                throw new BusinessException("验证码发送失败，请稍后重试");
            }

            JSONObject root = JSON.parseObject(response.body());
            JSONObject responseNode = root.getJSONObject("Response");
            if (responseNode == null) {
                throw new BusinessException("验证码发送失败，请稍后重试");
            }
            JSONObject error = responseNode.getJSONObject("Error");
            if (error != null) {
                log.warn("腾讯云短信返回错误: code={}, message={}", error.getString("Code"), error.getString("Message"));
                throw new BusinessException(error.getString("Message") != null ? error.getString("Message") : "验证码发送失败，请稍后重试");
            }
            JSONArray statuses = responseNode.getJSONArray("SendStatusSet");
            if (statuses == null || statuses.isEmpty() || !"Ok".equalsIgnoreCase(statuses.getJSONObject(0).getString("Code"))) {
                String message = statuses != null && !statuses.isEmpty()
                        ? statuses.getJSONObject(0).getString("Message")
                        : "验证码发送失败，请稍后重试";
                log.warn("腾讯云短信发送状态异常: phone={}, message={}", maskPhone(phone), message);
                throw new BusinessException(message);
            }
            log.info("腾讯云短信验证码发送成功: phone={}, templateId={}", maskPhone(phone), templateId);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("腾讯云短信发送异常: phone={}", maskPhone(phone), e);
            throw new BusinessException("验证码发送失败，请稍后重试");
        }
    }

    private String buildAuthorization(String payload, long timestamp) throws Exception {
        String hashedRequestPayload = sha256Hex(payload);
        String canonicalRequest = "POST\n/\n\n"
                + "content-type:application/json; charset=utf-8\n"
                + "host:" + HOST + "\n\n"
                + "content-type;host\n"
                + hashedRequestPayload;

        LocalDate date = Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
        String dateText = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String credentialScope = dateText + "/" + SERVICE + "/tc3_request";
        String stringToSign = ALGORITHM + "\n"
                + timestamp + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest);

        byte[] secretDate = hmac256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), dateText);
        byte[] secretService = hmac256(secretDate, SERVICE);
        byte[] secretSigning = hmac256(secretService, "tc3_request");
        String signature = HexFormat.of().formatHex(hmac256(secretSigning, stringToSign));

        return ALGORITHM
                + " Credential=" + secretId + "/" + credentialScope
                + ", SignedHeaders=content-type;host"
                + ", Signature=" + signature;
    }

    private String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] hmac256(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private String toE164Phone(String phone) {
        String normalized = phone == null ? "" : phone.trim();
        if (normalized.startsWith("+")) {
            return normalized;
        }
        if (normalized.startsWith("0086")) {
            return "+" + normalized.substring(2);
        }
        if (normalized.startsWith("86") && normalized.length() == 13) {
            return "+" + normalized;
        }
        return "+86" + normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
