package com.manga.ai.payment.client;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradePrecreateModel;
import com.alipay.api.request.AlipayTradeCancelRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradeCancelResponse;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.payment.config.AlipayProperties;
import com.manga.ai.payment.dto.AlipayCancelResult;
import com.manga.ai.payment.dto.AlipayPagePayRequest;
import com.manga.ai.payment.dto.AlipayPagePayResult;
import com.manga.ai.payment.dto.AlipayPrecreateRequest;
import com.manga.ai.payment.dto.AlipayPrecreateResult;
import com.manga.ai.payment.dto.AlipayQueryResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultAlipayPaymentClientTest {

    @Test
    void precreateBuildsOrderCodeRequestWithTwoHourExpiry() throws AlipayApiException {
        AlipayClient sdkClient = mock(AlipayClient.class);
        AlipayTradePrecreateResponse response = new AlipayTradePrecreateResponse();
        response.setCode("10000");
        response.setOutTradeNo("PAY202607170010");
        response.setQrCode("https://qr.alipay.com/example");
        when(sdkClient.execute(any(AlipayTradePrecreateRequest.class))).thenReturn(response);
        DefaultAlipayPaymentClient paymentClient = paymentClient(sdkClient);

        AlipayPrecreateResult result = paymentClient.precreate(new AlipayPrecreateRequest(
                "PAY202607170010",
                "高级会员",
                new BigDecimal("50.00"),
                "https://www.yzmovie.cn/api/v1/payments/alipay/notify",
                "2h"
        ));

        ArgumentCaptor<AlipayTradePrecreateRequest> requestCaptor =
                ArgumentCaptor.forClass(AlipayTradePrecreateRequest.class);
        verify(sdkClient).execute(requestCaptor.capture());
        AlipayTradePrecreateRequest captured = requestCaptor.getValue();
        AlipayTradePrecreateModel model = (AlipayTradePrecreateModel) captured.getBizModel();

        assertThat(result.qrCode()).isEqualTo("https://qr.alipay.com/example");
        assertThat(captured.getNotifyUrl())
                .isEqualTo("https://www.yzmovie.cn/api/v1/payments/alipay/notify");
        assertThat(model.getOutTradeNo()).isEqualTo("PAY202607170010");
        assertThat(model.getSubject()).isEqualTo("高级会员");
        assertThat(model.getTotalAmount()).isEqualTo("50.00");
        assertThat(model.getProductCode()).isEqualTo("QR_CODE_OFFLINE");
        assertThat(model.getQrCodeTimeoutExpress()).isEqualTo("2h");
    }

    @Test
    void precreateRejectsBlankQrCodeWithUserSafeMessage() throws AlipayApiException {
        AlipayClient sdkClient = mock(AlipayClient.class);
        AlipayTradePrecreateResponse response = new AlipayTradePrecreateResponse();
        response.setCode("10000");
        response.setQrCode(" ");
        when(sdkClient.execute(any(AlipayTradePrecreateRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> paymentClient(sdkClient).precreate(precreateRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("支付宝扫码支付二维码生成失败，请改用支付宝收银台");
    }

    @Test
    void precreateMapsAccessForbiddenToActionableMessage() throws AlipayApiException {
        AlipayClient sdkClient = mock(AlipayClient.class);
        AlipayTradePrecreateResponse response = new AlipayTradePrecreateResponse();
        response.setCode("40004");
        response.setSubCode("ACQ.ACCESS_FORBIDDEN");
        when(sdkClient.execute(any(AlipayTradePrecreateRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> paymentClient(sdkClient).precreate(precreateRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前支付宝应用未开通订单码支付，请改用支付宝收银台");
    }

    @Test
    void cancelReturnsRetryInstructionWithoutClosingLocally() throws AlipayApiException {
        AlipayClient sdkClient = mock(AlipayClient.class);
        AlipayTradeCancelResponse response = new AlipayTradeCancelResponse();
        response.setCode("10000");
        response.setRetryFlag("Y");
        response.setAction("close");
        when(sdkClient.execute(any(AlipayTradeCancelRequest.class))).thenReturn(response);

        AlipayCancelResult result = paymentClient(sdkClient).cancel("PAY202607170010");

        assertThat(result.success()).isTrue();
        assertThat(result.retryRequired()).isTrue();
        assertThat(result.action()).isEqualTo("close");
    }

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
    void pagePayRequiresReturnUrlBeforeCallingSdk() {
        AlipayClient sdkClient = mock(AlipayClient.class);
        AlipayProperties properties = properties();
        properties.setReturnUrl(" ");
        DefaultAlipayPaymentClient paymentClient = new TestDefaultAlipayPaymentClient(properties, sdkClient);

        assertThatThrownBy(() -> paymentClient.pagePay(pagePayRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("支付宝电脑网站支付配置未完成，请配置凭证及回调地址后重试");

        verifyNoInteractions(sdkClient);
    }

    @Test
    void queryOnlyRequiresSdkCredentials() throws AlipayApiException {
        AlipayClient sdkClient = mock(AlipayClient.class);
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse();
        response.setCode("10000");
        response.setTradeNo("2026071700001");
        response.setTradeStatus("TRADE_SUCCESS");
        response.setTotalAmount("50.00");
        when(sdkClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);
        AlipayProperties properties = properties();
        properties.setReturnUrl(null);
        DefaultAlipayPaymentClient paymentClient = new TestDefaultAlipayPaymentClient(properties, sdkClient);

        AlipayQueryResult result = paymentClient.query("PAY202607170001");

        assertThat(result.orderNo()).isEqualTo("PAY202607170001");
        assertThat(result.alipayTradeNo()).isEqualTo("2026071700001");
        assertThat(result.tradeStatus()).isEqualTo("TRADE_SUCCESS");
        assertThat(result.totalAmount()).isEqualByComparingTo("50.00");
        verify(sdkClient, times(1)).execute(any(AlipayTradeQueryRequest.class));
    }

    @Test
    void verifyNotifyOnlyRequiresSdkCredentials() {
        AlipayProperties properties = properties();
        properties.setNotifyUrl(null);
        properties.setReturnUrl(null);
        DefaultAlipayPaymentClient paymentClient = new DefaultAlipayPaymentClient(properties);

        assertThat(paymentClient.verifyNotify(new HashMap<>())).isFalse();
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

    private AlipayPrecreateRequest precreateRequest() {
        return new AlipayPrecreateRequest(
                "PAY202607170010",
                "高级会员",
                new BigDecimal("50.00"),
                "https://www.yzmovie.cn/api/v1/payments/alipay/notify",
                "2h"
        );
    }

    private AlipayProperties properties() {
        AlipayProperties properties = new AlipayProperties();
        properties.setAppId("test");
        properties.setAppPrivateKey("test");
        properties.setAlipayPublicKey("test");
        properties.setSellerId("seller-id");
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
