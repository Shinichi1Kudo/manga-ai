package com.manga.ai.payment.dto;

import java.time.LocalDateTime;

public record PaymentOrderStatusResponse(
        String orderNo,
        String orderType,
        String productName,
        int amountCents,
        int totalCredits,
        String status,
        String qrcode,
        LocalDateTime paidAt
) {
}
