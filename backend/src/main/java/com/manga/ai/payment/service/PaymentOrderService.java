package com.manga.ai.payment.service;

import com.manga.ai.payment.dto.PaymentOrderCreateResponse;
import com.manga.ai.payment.dto.PaymentOrderStatusResponse;
import com.manga.ai.payment.dto.PaymentProductsResponse;
import com.manga.ai.payment.dto.PaymentSettlement;
import com.manga.ai.payment.dto.PendingPaymentOrderResponse;
import com.manga.ai.payment.enums.PaymentMethod;

import java.math.BigDecimal;
import java.util.List;

public interface PaymentOrderService {

    PaymentProductsResponse products(Long userId);

    default PaymentOrderCreateResponse createRechargeOrder(Long userId, int amountCents) {
        return createRechargeOrder(userId, amountCents, PaymentMethod.PAGE);
    }

    PaymentOrderCreateResponse createRechargeOrder(Long userId, int amountCents, PaymentMethod paymentMethod);

    default PaymentOrderCreateResponse createMembershipOrder(Long userId, String productCode) {
        return createMembershipOrder(userId, productCode, PaymentMethod.PAGE);
    }

    PaymentOrderCreateResponse createMembershipOrder(Long userId, String productCode, PaymentMethod paymentMethod);

    PaymentOrderStatusResponse getOrder(Long userId, String orderNo);

    PaymentOrderStatusResponse syncOrder(Long userId, String orderNo);

    List<PendingPaymentOrderResponse> pendingOrders(Long userId);

    PaymentOrderCreateResponse resumeOrder(Long userId, String orderNo);

    PaymentOrderCreateResponse switchToPage(Long userId, String orderNo);

    PaymentOrderCreateResponse refreshQrOrder(Long userId, String orderNo);

    void processExpiredQrOrders();

    PaymentSettlement settlePaidOrder(String orderNo, String alipayTradeNo, String notifyId, BigDecimal paidAmount,
                                      String tradeStatus, String appId, String sellerId);
}
