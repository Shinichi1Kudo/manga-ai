package com.manga.ai.payment.service;

import com.manga.ai.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

@Component
public class PaymentRetrySleeper {

    private static final String INTERRUPTED_MESSAGE = "支付宝扫码支付请求已中断，请重新尝试";

    private final Sleeper sleeper;
    private final LongSupplier jitterMillisSupplier;

    public PaymentRetrySleeper() {
        this(Thread::sleep, () -> ThreadLocalRandom.current().nextLong(0L, 251L));
    }

    PaymentRetrySleeper(Sleeper sleeper, LongSupplier jitterMillisSupplier) {
        this.sleeper = sleeper;
        this.jitterMillisSupplier = jitterMillisSupplier;
    }

    public void pauseBeforeAttempt(int nextAttempt) {
        long baseMillis = nextAttempt == 2 ? 1000L : 2000L;
        pause(baseMillis + jitterMillisSupplier.getAsLong());
    }

    public void pauseBeforeQrVerification() {
        pause(5000L);
    }

    public void pauseBeforeQrVerificationRetry() {
        pause(2000L);
    }

    private void pause(long delayMillis) {
        try {
            sleeper.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(INTERRUPTED_MESSAGE);
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long delayMillis) throws InterruptedException;
    }
}
