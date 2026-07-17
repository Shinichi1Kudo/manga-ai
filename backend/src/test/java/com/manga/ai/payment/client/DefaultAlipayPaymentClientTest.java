package com.manga.ai.payment.client;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.payment.config.AlipayProperties;
import com.manga.ai.payment.dto.AlipayPagePayRequest;
import com.manga.ai.payment.dto.AlipayPagePayResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAlipayPaymentClientTest {

    @Test
    void pagePayBuildsPcCashierPostForm() throws AlipayApiException {
        AlipayClient sdkClient = mock(AlipayClient.class);
        AlipayTradePagePayResponse response = new AlipayTradePagePayResponse();
        response.setBody("<form method=\"post\"></form>");
        when(sdkClient.pageExecute(any(AlipayTradePagePayRequest.class), eq("POST")))
                .thenReturn(response);
        DefaultAlipayPaymentClient paymentClient = paymentClient(sdkClient);

        AlipayPagePayResult result = paymentClient.pagePay(new AlipayPagePayRequest(
                "PAY202607170001",
                "积分充值¥50",
                new BigDecimal("50.00"),
                null,
                "https://www.yzmovie.cn/membership/payment/result/",
                "30m"
        ));

        ArgumentCaptor<AlipayTradePagePayRequest> requestCaptor =
                ArgumentCaptor.forClass(AlipayTradePagePayRequest.class);
        verify(sdkClient, times(1)).pageExecute(requestCaptor.capture(), eq("POST"));
        AlipayTradePagePayRequest captured = requestCaptor.getValue();
        AlipayTradePagePayModel model = (AlipayTradePagePayModel) captured.getBizModel();

        assertThat(result.paymentHtml()).contains("<form");
        assertThat(captured.getNotifyUrl())
                .isEqualTo("https://www.yzmovie.cn/api/v1/payments/alipay/notify");
        assertThat(captured.getReturnUrl())
                .isEqualTo("https://www.yzmovie.cn/membership/payment/result/");
        assertThat(model.getOutTradeNo()).isEqualTo("PAY202607170001");
        assertThat(model.getTotalAmount()).isEqualTo("50.00");
        assertThat(model.getSubject()).isEqualTo("积分充值¥50");
        assertThat(model.getProductCode()).isEqualTo("FAST_INSTANT_TRADE_PAY");
        assertThat(model.getTimeoutExpress()).isEqualTo("30m");
    }

    @Test
    void pagePayUsesConfiguredDefaultsForBlankRequestValues() throws AlipayApiException {
        AlipayClient sdkClient = mock(AlipayClient.class);
        AlipayTradePagePayResponse response = new AlipayTradePagePayResponse();
        response.setBody("<form method=\"post\"></form>");
        when(sdkClient.pageExecute(any(AlipayTradePagePayRequest.class), eq("POST")))
                .thenReturn(response);
        DefaultAlipayPaymentClient paymentClient = paymentClient(sdkClient);

        paymentClient.pagePay(new AlipayPagePayRequest(
                "PAY202607170001",
                "积分充值¥50",
                new BigDecimal("50.00"),
                " ",
                null,
                " "
        ));

        ArgumentCaptor<AlipayTradePagePayRequest> requestCaptor =
                ArgumentCaptor.forClass(AlipayTradePagePayRequest.class);
        verify(sdkClient, times(1)).pageExecute(requestCaptor.capture(), eq("POST"));
        AlipayTradePagePayRequest captured = requestCaptor.getValue();
        AlipayTradePagePayModel model = (AlipayTradePagePayModel) captured.getBizModel();

        assertThat(captured.getNotifyUrl())
                .isEqualTo("https://www.yzmovie.cn/api/v1/payments/alipay/notify");
        assertThat(captured.getReturnUrl())
                .isEqualTo("https://www.yzmovie.cn/membership/payment/result/");
        assertThat(model.getTimeoutExpress()).isEqualTo("2h");
    }

    @Test
    void pagePayRejectsBlankResponseBody() throws AlipayApiException {
        AlipayClient sdkClient = mock(AlipayClient.class);
        AlipayTradePagePayResponse response = new AlipayTradePagePayResponse();
        response.setBody("  ");
        when(sdkClient.pageExecute(any(AlipayTradePagePayRequest.class), eq("POST")))
                .thenReturn(response);
        DefaultAlipayPaymentClient paymentClient = paymentClient(sdkClient);

        assertThatThrownBy(() -> paymentClient.pagePay(pagePayRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("支付宝支付页面生成失败，请稍后重试");

        verify(sdkClient, times(1)).pageExecute(any(AlipayTradePagePayRequest.class), eq("POST"));
    }

    @Test
    void pagePayWrapsSdkExceptionWithUserSafeMessage() throws AlipayApiException {
        AlipayClient sdkClient = mock(AlipayClient.class);
        when(sdkClient.pageExecute(any(AlipayTradePagePayRequest.class), eq("POST")))
                .thenThrow(new AlipayApiException("SDK unavailable"));
        DefaultAlipayPaymentClient paymentClient = paymentClient(sdkClient);

        assertThatThrownBy(() -> paymentClient.pagePay(pagePayRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("支付宝支付页面生成异常，请稍后重试");

        verify(sdkClient, times(1)).pageExecute(any(AlipayTradePagePayRequest.class), eq("POST"));
    }

    private DefaultAlipayPaymentClient paymentClient(AlipayClient sdkClient) {
        return new TestDefaultAlipayPaymentClient(properties(), sdkClient);
    }

    private AlipayPagePayRequest pagePayRequest() {
        return new AlipayPagePayRequest(
                "PAY202607170001",
                "积分充值¥50",
                new BigDecimal("50.00"),
                null,
                "https://www.yzmovie.cn/membership/payment/result/",
                "30m"
        );
    }

    private AlipayProperties properties() {
        AlipayProperties properties = new AlipayProperties();
        properties.setAppId("test");
        properties.setAppPrivateKey("test");
        properties.setAlipayPublicKey("test");
        properties.setNotifyUrl("https://www.yzmovie.cn/api/v1/payments/alipay/notify");
        properties.setReturnUrl("https://www.yzmovie.cn/membership/payment/result/");
        properties.setTimeoutExpress("2h");
        return properties;
    }

    private static final class TestDefaultAlipayPaymentClient extends DefaultAlipayPaymentClient {

        private final AlipayClient sdkClient;

        private TestDefaultAlipayPaymentClient(AlipayProperties properties, AlipayClient sdkClient) {
            super(properties);
            this.sdkClient = sdkClient;
        }

        @Override
        AlipayClient client() {
            return sdkClient;
        }
    }
}
