package com.manga.ai.payment.task;

import com.manga.ai.payment.service.PaymentOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentOrderExpiryTask {

    private final PaymentOrderService paymentOrderService;
    private volatile boolean applicationReady;

    @EventListener(ApplicationReadyEvent.class)
    public void markApplicationReady() {
        applicationReady = true;
    }

    @Scheduled(
            fixedDelayString = "${payment.qr-expiry-scan-ms:60000}",
            initialDelayString = "${payment.qr-expiry-initial-delay-ms:60000}"
    )
    public void closeExpiredQrOrders() {
        if (!applicationReady) {
            return;
        }
        paymentOrderService.processExpiredQrOrders();
    }
}
