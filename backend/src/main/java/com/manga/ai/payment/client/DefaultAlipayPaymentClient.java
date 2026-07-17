package com.manga.ai.payment.client;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeCancelModel;
import com.alipay.api.domain.AlipayTradePrecreateModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeCancelRequest;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeCancelResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.payment.config.AlipayProperties;
import com.manga.ai.payment.dto.AlipayPagePayRequest;
import com.manga.ai.payment.dto.AlipayPagePayResult;
import com.manga.ai.payment.dto.AlipayCancelResult;
import com.manga.ai.payment.dto.AlipayPrecreateRequest;
import com.manga.ai.payment.dto.AlipayPrecreateResult;
import com.manga.ai.payment.dto.AlipayQueryResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultAlipayPaymentClient implements AlipayPaymentClient {

    private final AlipayProperties properties;

    @Override
    public AlipayPagePayResult pagePay(AlipayPagePayRequest request) {
        ensurePagePayConfigured();
        AlipayTradePagePayRequest alipayRequest = new AlipayTradePagePayRequest();
        alipayRequest.setNotifyUrl(notBlank(request.notifyUrl()) ? request.notifyUrl() : properties.getNotifyUrl());
        alipayRequest.setReturnUrl(notBlank(request.returnUrl()) ? request.returnUrl() : properties.getReturnUrl());

        AlipayTradePagePayModel model = new AlipayTradePagePayModel();
        model.setOutTradeNo(request.orderNo());
        model.setSubject(request.subject());
        model.setTotalAmount(request.totalAmount().toPlainString());
        model.setProductCode("FAST_INSTANT_TRADE_PAY");
        model.setTimeoutExpress(notBlank(request.timeoutExpress())
                ? request.timeoutExpress()
                : properties.getTimeoutExpress());
        alipayRequest.setBizModel(model);

        try {
            AlipayTradePagePayResponse response = client().pageExecute(alipayRequest, "POST");
            if (!notBlank(response.getBody())) {
                log.warn("支付宝电脑网站支付表单生成失败: orderNo={}", request.orderNo());
                throw new BusinessException("支付宝支付页面生成失败，请稍后重试");
            }
            return new AlipayPagePayResult(response.getBody());
        } catch (AlipayApiException e) {
            log.warn("支付宝电脑网站支付表单生成异常: orderNo={}, message={}",
                    request.orderNo(), e.getMessage(), e);
            throw new BusinessException("支付宝支付页面生成异常，请稍后重试");
        }
    }

    @Override
    public AlipayPrecreateResult precreate(AlipayPrecreateRequest request) {
        ensureQrPayConfigured();
        AlipayTradePrecreateRequest alipayRequest = new AlipayTradePrecreateRequest();
        alipayRequest.setNotifyUrl(notBlank(request.notifyUrl()) ? request.notifyUrl() : properties.getNotifyUrl());

        AlipayTradePrecreateModel model = new AlipayTradePrecreateModel();
        model.setOutTradeNo(request.orderNo());
        model.setSubject(request.subject());
        model.setTotalAmount(request.totalAmount().toPlainString());
        model.setProductCode("QR_CODE_OFFLINE");
        model.setQrCodeTimeoutExpress("2h");
        alipayRequest.setBizModel(model);

        try {
            AlipayTradePrecreateResponse response = client().execute(alipayRequest);
            if (!response.isSuccess()) {
                log.info("支付宝订单码预下单失败: orderNo={}, code={}, subCode={}",
                        request.orderNo(), response.getCode(), response.getSubCode());
                if ("ACQ.ACCESS_FORBIDDEN".equals(response.getSubCode())) {
                    throw new BusinessException("当前支付宝应用未开通订单码支付，请改用支付宝收银台");
                }
                throw new BusinessException("支付宝扫码支付下单失败，请稍后重试或改用支付宝收银台");
            }
            if (!notBlank(response.getQrCode())) {
                log.warn("支付宝订单码响应缺少二维码: orderNo={}", request.orderNo());
                throw new BusinessException("支付宝扫码支付二维码生成失败，请改用支付宝收银台");
            }
            return new AlipayPrecreateResult(response.getQrCode());
        } catch (AlipayApiException e) {
            log.warn("支付宝订单码预下单异常: orderNo={}, message={}", request.orderNo(), e.getErrMsg());
            throw new BusinessException("支付宝扫码支付下单异常，请稍后重试或改用支付宝收银台");
        }
    }

    @Override
    public AlipayQueryResult query(String orderNo) {
        ensureCredentialsConfigured();
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        AlipayTradeQueryModel model = new AlipayTradeQueryModel();
        model.setOutTradeNo(orderNo);
        request.setBizModel(model);

        try {
            AlipayTradeQueryResponse response = client().execute(request);
            if (!response.isSuccess()) {
                log.info("支付宝交易查询未成功: orderNo={}, code={}, subCode={}",
                        orderNo, response.getCode(), response.getSubCode());
                return new AlipayQueryResult(orderNo, response.getTradeNo(), response.getTradeStatus(), null);
            }
            BigDecimal totalAmount = response.getTotalAmount() == null
                    ? null
                    : new BigDecimal(response.getTotalAmount());
            return new AlipayQueryResult(orderNo, response.getTradeNo(), response.getTradeStatus(), totalAmount);
        } catch (AlipayApiException e) {
            log.warn("支付宝交易查询异常: orderNo={}, message={}", orderNo, e.getErrMsg());
            throw new BusinessException("支付宝交易查询异常，请稍后重试");
        }
    }

    @Override
    public AlipayCancelResult cancel(String orderNo) {
        ensureCredentialsConfigured();
        AlipayTradeCancelRequest request = new AlipayTradeCancelRequest();
        AlipayTradeCancelModel model = new AlipayTradeCancelModel();
        model.setOutTradeNo(orderNo);
        request.setBizModel(model);

        try {
            AlipayTradeCancelResponse response = client().execute(request);
            if (!response.isSuccess()) {
                log.info("支付宝交易撤销未成功: orderNo={}, code={}, subCode={}",
                        orderNo, response.getCode(), response.getSubCode());
            }
            return new AlipayCancelResult(
                    response.isSuccess(),
                    "Y".equalsIgnoreCase(response.getRetryFlag()),
                    response.getAction()
            );
        } catch (AlipayApiException e) {
            log.warn("支付宝交易撤销异常: orderNo={}, message={}", orderNo, e.getErrMsg());
            return new AlipayCancelResult(false, true, null);
        }
    }

    @Override
    public boolean verifyNotify(Map<String, String> params) {
        ensureCredentialsConfigured();
        try {
            return AlipaySignature.rsaCheckV1(
                    params,
                    properties.getAlipayPublicKey(),
                    properties.getCharset(),
                    properties.getSignType()
            );
        } catch (AlipayApiException e) {
            log.warn("支付宝异步通知验签异常: {}", e.getErrMsg());
            return false;
        }
    }

    AlipayClient client() {
        return new DefaultAlipayClient(
                properties.getServerUrl(),
                properties.getAppId(),
                properties.getAppPrivateKey(),
                "json",
                properties.getCharset(),
                properties.getAlipayPublicKey(),
                properties.getSignType()
        );
    }

    private void ensurePagePayConfigured() {
        if (!properties.configured()) {
            throw new BusinessException("支付宝电脑网站支付配置未完成，请配置凭证及回调地址后重试");
        }
    }

    private void ensureQrPayConfigured() {
        if (!properties.credentialsConfigured() || !notBlank(properties.getNotifyUrl())) {
            throw new BusinessException("支付宝扫码支付配置未完成，请配置凭证及异步通知地址后重试");
        }
    }

    private void ensureCredentialsConfigured() {
        if (!properties.credentialsConfigured()) {
            throw new BusinessException("支付宝凭证配置未完成，请配置环境变量后重试");
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
