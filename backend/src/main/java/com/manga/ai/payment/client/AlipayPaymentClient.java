package com.manga.ai.payment.client;

import com.manga.ai.payment.dto.AlipayPrecreateRequest;
import com.manga.ai.payment.dto.AlipayPrecreateResult;
import com.manga.ai.payment.dto.AlipayQueryResult;

import java.util.Map;

public interface AlipayPaymentClient {

    AlipayPrecreateResult precreate(AlipayPrecreateRequest request);

    AlipayQueryResult query(String orderNo);

    boolean verifyNotify(Map<String, String> params);
}
