package com.manga.ai.payment.controller;

import com.manga.ai.payment.client.AlipayPaymentClient;
import com.manga.ai.payment.dto.PaymentOrderCreateResponse;
import com.manga.ai.payment.dto.PendingPaymentOrderResponse;
import com.manga.ai.payment.service.PaymentOrderService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class PaymentControllerTest {

    private PaymentOrderService paymentOrderService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        paymentOrderService = mock(PaymentOrderService.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        mockMvc = standaloneSetup(new PaymentController(paymentOrderService, alipayPaymentClient)).build();
        UserContextHolder.setUserId(7L);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void listsPendingOrdersForAuthenticatedUser() throws Exception {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 20, 10, 0);
        PendingPaymentOrderResponse pending = new PendingPaymentOrderResponse(
                "PAY-PENDING", "RECHARGE", "积分充值¥50", 5000, "QR",
                true, "CREATED", createdAt.plusHours(2), createdAt);
        when(paymentOrderService.pendingOrders(7L)).thenReturn(List.of(pending));

        mockMvc.perform(get("/v1/payments/orders/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].orderNo").value("PAY-PENDING"));

        verify(paymentOrderService).pendingOrders(7L);
    }

    @Test
    void resumesPendingOrderForAuthenticatedUser() throws Exception {
        when(paymentOrderService.resumeOrder(7L, "PAY-PENDING"))
                .thenReturn(paymentResponse("QR"));

        mockMvc.perform(post("/v1/payments/orders/PAY-PENDING/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentMethod").value("QR"));

        verify(paymentOrderService).resumeOrder(7L, "PAY-PENDING");
    }

    @Test
    void switchesPendingOrderToPageForAuthenticatedUser() throws Exception {
        when(paymentOrderService.switchToPage(7L, "PAY-PENDING"))
                .thenReturn(paymentResponse("PAGE"));

        mockMvc.perform(post("/v1/payments/orders/PAY-PENDING/switch-to-page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentMethod").value("PAGE"));

        verify(paymentOrderService).switchToPage(7L, "PAY-PENDING");
    }

    @Test
    void refreshesQrOrderForAuthenticatedUser() throws Exception {
        when(paymentOrderService.refreshQrOrder(7L, "PAY-PENDING"))
                .thenReturn(paymentResponse("QR"));

        mockMvc.perform(post("/v1/payments/orders/PAY-PENDING/refresh-qr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentMethod").value("QR"))
                .andExpect(jsonPath("$.data.qrCode").value("https://qr.alipay.com/example"));

        verify(paymentOrderService).refreshQrOrder(7L, "PAY-PENDING");
    }

    private PaymentOrderCreateResponse paymentResponse(String paymentMethod) {
        return new PaymentOrderCreateResponse(
                "PAY-PENDING", "RECHARGE", "积分充值¥50", 5000,
                1000, 0, 1000, "CREATED", paymentMethod,
                "PAGE".equals(paymentMethod) ? "<form></form>" : null,
                "QR".equals(paymentMethod) ? "https://qr.alipay.com/example" : null,
                LocalDateTime.of(2026, 7, 20, 12, 0), true, null);
    }
}
