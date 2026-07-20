package com.manga.ai.payment.controller;

import com.manga.ai.common.result.Result;
import com.manga.ai.payment.client.AlipayPaymentClient;
import com.manga.ai.payment.dto.CreateMembershipOrderRequest;
import com.manga.ai.payment.dto.CreateRechargeOrderRequest;
import com.manga.ai.payment.dto.PaymentOrderCreateResponse;
import com.manga.ai.payment.dto.PaymentOrderStatusResponse;
import com.manga.ai.payment.dto.PaymentProductsResponse;
import com.manga.ai.payment.dto.PendingPaymentOrderResponse;
import com.manga.ai.payment.service.PaymentOrderService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentOrderService paymentOrderService;
    private final AlipayPaymentClient alipayPaymentClient;

    @GetMapping("/products")
    public Result<PaymentProductsResponse> products() {
        return Result.success(paymentOrderService.products(UserContextHolder.getUserId()));
    }

    @PostMapping("/membership/orders")
    public Result<PaymentOrderCreateResponse> createMembershipOrder(@Valid @RequestBody CreateMembershipOrderRequest request) {
        return Result.success(paymentOrderService.createMembershipOrder(
                UserContextHolder.getUserId(), request.getProductCode(), request.getPaymentMethod()));
    }

    @PostMapping("/recharge/orders")
    public Result<PaymentOrderCreateResponse> createRechargeOrder(@Valid @RequestBody CreateRechargeOrderRequest request) {
        return Result.success(paymentOrderService.createRechargeOrder(
                UserContextHolder.getUserId(), request.getAmountCents(), request.getPaymentMethod()));
    }

    @GetMapping("/orders/pending")
    public Result<List<PendingPaymentOrderResponse>> pendingOrders() {
        return Result.success(paymentOrderService.pendingOrders(UserContextHolder.getUserId()));
    }

    @PostMapping("/orders/{orderNo}/resume")
    public Result<PaymentOrderCreateResponse> resumeOrder(@PathVariable String orderNo) {
        return Result.success(paymentOrderService.resumeOrder(UserContextHolder.getUserId(), orderNo));
    }

    @PostMapping("/orders/{orderNo}/switch-to-page")
    public Result<PaymentOrderCreateResponse> switchToPage(@PathVariable String orderNo) {
        return Result.success(paymentOrderService.switchToPage(UserContextHolder.getUserId(), orderNo));
    }

    @PostMapping("/orders/{orderNo}/refresh-qr")
    public Result<PaymentOrderCreateResponse> refreshQrOrder(@PathVariable String orderNo) {
        return Result.success(paymentOrderService.refreshQrOrder(UserContextHolder.getUserId(), orderNo));
    }

    @GetMapping("/orders/{orderNo}")
    public Result<PaymentOrderStatusResponse> getOrder(@PathVariable String orderNo) {
        return Result.success(paymentOrderService.getOrder(UserContextHolder.getUserId(), orderNo));
    }

    @PostMapping("/orders/{orderNo}/sync")
    public Result<PaymentOrderStatusResponse> syncOrder(@PathVariable String orderNo) {
        return Result.success(paymentOrderService.syncOrder(UserContextHolder.getUserId(), orderNo));
    }

    @PostMapping("/alipay/notify")
    public String alipayNotify(@RequestParam Map<String, String> params) {
        if (!alipayPaymentClient.verifyNotify(params)) {
            log.warn("支付宝通知验签失败: outTradeNo={}", params.get("out_trade_no"));
            return "fail";
        }
        try {
            paymentOrderService.settlePaidOrder(
                    params.get("out_trade_no"),
                    params.get("trade_no"),
                    params.get("notify_id"),
                    parseAmount(params.get("total_amount")),
                    params.get("trade_status"),
                    params.get("app_id"),
                    params.get("seller_id")
            );
            return "success";
        } catch (Exception e) {
            log.warn("支付宝通知处理失败: outTradeNo={}, message={}", params.get("out_trade_no"), e.getMessage());
            return "fail";
        }
    }

    private BigDecimal parseAmount(String amount) {
        if (amount == null || amount.trim().isEmpty()) {
            return null;
        }
        return new BigDecimal(amount);
    }
}
