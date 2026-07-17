package com.manga.ai.payment.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlipayPropertiesTest {

    private static final String NOTIFY_URL =
            "https://www.yzmovie.cn/api/v1/payments/alipay/notify";
    private static final String RETURN_URL =
            "https://www.yzmovie.cn/membership/payment/result/";

    @Test
    void providesProductionCallbackDefaults() {
        AlipayProperties properties = new AlipayProperties();

        assertThat(properties.getNotifyUrl()).isEqualTo(NOTIFY_URL);
        assertThat(properties.getReturnUrl()).isEqualTo(RETURN_URL);
    }

    @Test
    void configuredRequiresNotifyAndReturnUrls() {
        AlipayProperties properties = configuredProperties();

        assertThat(properties.credentialsConfigured()).isTrue();
        assertThat(properties.configured()).isTrue();

        properties.setNotifyUrl(" ");
        assertThat(properties.credentialsConfigured()).isTrue();
        assertThat(properties.configured()).isFalse();

        properties.setNotifyUrl(NOTIFY_URL);
        properties.setReturnUrl(null);
        assertThat(properties.credentialsConfigured()).isTrue();
        assertThat(properties.configured()).isFalse();
    }

    private AlipayProperties configuredProperties() {
        AlipayProperties properties = new AlipayProperties();
        properties.setServerUrl("https://openapi.alipay.com/gateway.do");
        properties.setAppId("app-id");
        properties.setAppPrivateKey("app-private-key");
        properties.setAlipayPublicKey("alipay-public-key");
        properties.setSellerId("seller-id");
        properties.setNotifyUrl(NOTIFY_URL);
        properties.setReturnUrl(RETURN_URL);
        return properties;
    }
}
