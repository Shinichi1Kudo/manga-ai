package com.manga.ai.payment.service;

import com.manga.ai.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentRetrySleeperTest {

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void waitsFiveSecondsBeforeFirstQrVerification() {
        AtomicLong delay = new AtomicLong(-1L);
        PaymentRetrySleeper sleeper = new PaymentRetrySleeper(delay::set, () -> 0L);

        sleeper.pauseBeforeQrVerification();

        assertThat(delay.get()).isEqualTo(5_000L);
    }

    @Test
    void waitsTwoSecondsBeforeRetryingQrVerification() {
        AtomicLong delay = new AtomicLong(-1L);
        PaymentRetrySleeper sleeper = new PaymentRetrySleeper(delay::set, () -> 0L);

        sleeper.pauseBeforeQrVerificationRetry();

        assertThat(delay.get()).isEqualTo(2_000L);
    }

    @Test
    void firstQrVerificationWaitRestoresInterruptFlagAndThrowsSafeException() {
        PaymentRetrySleeper sleeper = interruptedSleeper();

        assertThatThrownBy(sleeper::pauseBeforeQrVerification)
                .isExactlyInstanceOf(BusinessException.class)
                .hasMessage("支付宝扫码支付请求已中断，请重新尝试");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void qrVerificationRetryWaitRestoresInterruptFlagAndThrowsSafeException() {
        PaymentRetrySleeper sleeper = interruptedSleeper();

        assertThatThrownBy(sleeper::pauseBeforeQrVerificationRetry)
                .isExactlyInstanceOf(BusinessException.class)
                .hasMessage("支付宝扫码支付请求已中断，请重新尝试");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    private PaymentRetrySleeper interruptedSleeper() {
        return new PaymentRetrySleeper(delay -> {
            throw new InterruptedException("sensitive test detail");
        }, () -> 0L);
    }
}
