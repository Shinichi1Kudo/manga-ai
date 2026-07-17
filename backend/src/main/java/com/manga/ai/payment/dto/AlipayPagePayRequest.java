package com.manga.ai.payment.dto;

import java.math.BigDecimal;

public record AlipayPagePayRequest(
        String orderNo,
        String subject,
        BigDecimal totalAmount,
        String notifyUrl,
        String returnUrl,
        String timeoutExpress
) {
}
