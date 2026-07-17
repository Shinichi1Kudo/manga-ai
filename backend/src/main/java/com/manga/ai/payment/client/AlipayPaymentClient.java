package com.manga.ai.payment.client;

import com.manga.ai.payment.dto.AlipayPagePayRequest;
import com.manga.ai.payment.dto.AlipayPagePayResult;
import com.manga.ai.payment.dto.AlipayQueryResult;

import java.util.Map;

public interface AlipayPaymentClient {

    AlipayPagePayResult pagePay(AlipayPagePayRequest request);

    AlipayQueryResult query(String orderNo);

    boolean verifyNotify(Map<String, String> params);
}
