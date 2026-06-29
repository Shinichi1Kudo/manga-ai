package com.manga.ai.payment.dto;

import java.math.BigDecimal;

public record AlipayQueryResult(
        String orderNo,
        String alipayTradeNo,
        String tradeStatus,
        BigDecimal totalAmount
) {

    public boolean paid() {
        return "TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus);
    }
}
