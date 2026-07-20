package com.manga.ai.payment.dto;

import java.math.BigDecimal;

public record AlipayQueryResult(
        String orderNo,
        String alipayTradeNo,
        String tradeStatus,
        BigDecimal totalAmount,
        String code,
        String subCode
) {

    public AlipayQueryResult(String orderNo, String alipayTradeNo,
                             String tradeStatus, BigDecimal totalAmount) {
        this(orderNo, alipayTradeNo, tradeStatus, totalAmount, null, null);
    }

    public boolean paid() {
        return "TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus);
    }

    public boolean notFound() {
        return "ACQ.TRADE_NOT_EXIST".equals(subCode);
    }
}
