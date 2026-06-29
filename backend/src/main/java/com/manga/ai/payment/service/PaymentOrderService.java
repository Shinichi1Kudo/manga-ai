package com.manga.ai.payment.service;

import com.manga.ai.payment.dto.PaymentOrderCreateResponse;
import com.manga.ai.payment.dto.PaymentOrderStatusResponse;
import com.manga.ai.payment.dto.PaymentProductsResponse;
import com.manga.ai.payment.dto.PaymentSettlement;

import java.math.BigDecimal;

public interface PaymentOrderService {

    PaymentProductsResponse products(Long userId);

    PaymentOrderCreateResponse createRechargeOrder(Long userId, int amountCents);

    PaymentOrderCreateResponse createMembershipOrder(Long userId, String productCode);

    PaymentOrderStatusResponse getOrder(Long userId, String orderNo);

    PaymentOrderStatusResponse syncOrder(Long userId, String orderNo);

    PaymentSettlement settlePaidOrder(String orderNo, String alipayTradeNo, String notifyId, BigDecimal paidAmount,
                                      String tradeStatus, String appId, String sellerId);
}
