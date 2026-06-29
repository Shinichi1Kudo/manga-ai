package com.manga.ai.payment.dto;

public record AlipayPrecreateResult(
        String qrcode,
        String alipayTraceId
) {
}
