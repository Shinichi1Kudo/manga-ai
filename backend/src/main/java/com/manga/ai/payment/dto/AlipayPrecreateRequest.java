package com.manga.ai.payment.dto;

import java.math.BigDecimal;

public record AlipayPrecreateRequest(
        String orderNo,
        String subject,
        BigDecimal totalAmount,
        String notifyUrl,
        String timeoutExpress
) {
}
