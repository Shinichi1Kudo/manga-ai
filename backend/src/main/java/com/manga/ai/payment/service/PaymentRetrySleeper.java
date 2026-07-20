package com.manga.ai.payment.service;

import com.manga.ai.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class PaymentRetrySleeper {

    public void pauseBeforeAttempt(int nextAttempt) {
        long baseMillis = nextAttempt == 2 ? 1000L : 2000L;
        long delayMillis = baseMillis + ThreadLocalRandom.current().nextLong(0L, 251L);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("支付宝扫码支付请求已中断，请重新尝试");
        }
    }
}
