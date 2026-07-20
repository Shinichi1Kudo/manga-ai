package com.manga.ai.payment.dto;

import java.time.LocalDateTime;

public record PendingPaymentOrderResponse(
        String orderNo,
        String orderType,
        String productName,
        int amountCents,
        String paymentMethod,
        boolean paymentReady,
        String status,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {
}
