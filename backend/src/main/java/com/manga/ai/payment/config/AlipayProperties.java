package com.manga.ai.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "alipay")
public class AlipayProperties {

    private String serverUrl = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    private String appId;
    private String appPrivateKey;
    private String alipayPublicKey;
    private String sellerId;
    private String notifyUrl = "https://www.yzmovie.cn/api/v1/payments/alipay/notify";
    private String returnUrl = "https://www.yzmovie.cn/membership/payment/result/";
    private String charset = "UTF-8";
    private String signType = "RSA2";
    private String timeoutExpress = "2h";

    public boolean credentialsConfigured() {
        return notBlank(serverUrl)
                && notBlank(appId)
                && notBlank(appPrivateKey)
                && notBlank(alipayPublicKey);
    }

    public boolean configured() {
        return credentialsConfigured() && notBlank(notifyUrl) && notBlank(returnUrl);
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppPrivateKey() {
        return appPrivateKey;
    }

    public void setAppPrivateKey(String appPrivateKey) {
        this.appPrivateKey = appPrivateKey;
    }

    public String getAlipayPublicKey() {
        return alipayPublicKey;
    }

    public void setAlipayPublicKey(String alipayPublicKey) {
        this.alipayPublicKey = alipayPublicKey;
    }

    public String getSellerId() {
        return sellerId;
    }

    public void setSellerId(String sellerId) {
        this.sellerId = sellerId;
    }

    public String getNotifyUrl() {
        return notifyUrl;
    }

    public void setNotifyUrl(String notifyUrl) {
        this.notifyUrl = notifyUrl;
    }

    public String getReturnUrl() {
        return returnUrl;
    }

    public void setReturnUrl(String returnUrl) {
        this.returnUrl = returnUrl;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public String getSignType() {
        return signType;
    }

    public void setSignType(String signType) {
        this.signType = signType;
    }

    public String getTimeoutExpress() {
        return timeoutExpress;
    }

    public void setTimeoutExpress(String timeoutExpress) {
        this.timeoutExpress = timeoutExpress;
    }
}
