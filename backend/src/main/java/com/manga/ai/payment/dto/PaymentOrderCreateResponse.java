package com.manga.ai.payment.dto;

public record PaymentOrderCreateResponse(
        String orderNo,
        String orderType,
        String productName,
        int amountCents,
        int baseCredits,
        int bonusCredits,
        int totalCredits,
        String status,
        String paymentHtml
) {
}
