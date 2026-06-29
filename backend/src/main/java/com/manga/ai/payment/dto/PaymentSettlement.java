package com.manga.ai.payment.dto;

public record PaymentSettlement(
        boolean settled,
        String orderNo,
        String status
) {
}
