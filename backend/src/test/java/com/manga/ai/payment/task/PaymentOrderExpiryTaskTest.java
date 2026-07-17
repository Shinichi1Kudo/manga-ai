package com.manga.ai.payment.task;

import com.manga.ai.payment.service.PaymentOrderService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PaymentOrderExpiryTaskTest {

    @Test
    void scansOnlyAfterApplicationIsReady() {
        PaymentOrderService paymentOrderService = mock(PaymentOrderService.class);
        PaymentOrderExpiryTask task = new PaymentOrderExpiryTask(paymentOrderService);

        task.closeExpiredQrOrders();
        verify(paymentOrderService, never()).processExpiredQrOrders();

        task.markApplicationReady();
        task.closeExpiredQrOrders();
        verify(paymentOrderService).processExpiredQrOrders();
    }
}
