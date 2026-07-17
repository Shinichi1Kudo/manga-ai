package com.manga.ai.payment.service;

import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.payment.client.AlipayPaymentClient;
import com.manga.ai.payment.config.AlipayProperties;
import com.manga.ai.payment.dto.AlipayCancelResult;
import com.manga.ai.payment.dto.AlipayPagePayRequest;
import com.manga.ai.payment.dto.AlipayPagePayResult;
import com.manga.ai.payment.dto.AlipayPrecreateRequest;
import com.manga.ai.payment.dto.AlipayPrecreateResult;
import com.manga.ai.payment.dto.AlipayQueryResult;
import com.manga.ai.payment.dto.PaymentOrderCreateResponse;
import com.manga.ai.payment.dto.PaymentSettlement;
import com.manga.ai.payment.entity.PaymentOrder;
import com.manga.ai.payment.entity.UserMembership;
import com.manga.ai.payment.enums.MembershipTier;
import com.manga.ai.payment.enums.PaymentMethod;
import com.manga.ai.payment.enums.PaymentOrderStatus;
import com.manga.ai.payment.enums.PaymentOrderType;
import com.manga.ai.payment.mapper.PaymentOrderMapper;
import com.manga.ai.payment.mapper.UserMembershipMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentOrderServiceImplTest {

    @Test
    void createQrRechargeOrderPersistsServerPricedQrSnapshot() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        UserMembershipMapper userMembershipMapper = mock(UserMembershipMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        when(alipayPaymentClient.precreate(any())).thenReturn(new AlipayPrecreateResult(
                "https://qr.alipay.com/example"
        ));
        PaymentOrderServiceImpl service = service(
                paymentOrderMapper,
                userMembershipMapper,
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                alipayPaymentClient
        );

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        ArgumentCaptor<PaymentOrder> orderCaptor = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(paymentOrderMapper).insert(orderCaptor.capture());
        PaymentOrder order = orderCaptor.getValue();
        ArgumentCaptor<AlipayPrecreateRequest> requestCaptor =
                ArgumentCaptor.forClass(AlipayPrecreateRequest.class);
        verify(alipayPaymentClient).precreate(requestCaptor.capture());

        assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.QR.name());
        assertThat(order.getQrcode()).isEqualTo("https://qr.alipay.com/example");
        assertThat(order.getExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(119));
        assertThat(response.paymentMethod()).isEqualTo(PaymentMethod.QR.name());
        assertThat(response.paymentHtml()).isNull();
        assertThat(response.qrCode()).isEqualTo("https://qr.alipay.com/example");
        assertThat(response.expiresAt()).isEqualTo(order.getExpiresAt());
        assertThat(requestCaptor.getValue().totalAmount()).isEqualByComparingTo("50.00");
        assertThat(requestCaptor.getValue().notifyUrl()).endsWith(".cn/api/v1/payments/alipay/notify");
    }

    @Test
    void createQrOrderDoesNotInsertWhenAlipayPrecreateFails() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        when(alipayPaymentClient.precreate(any()))
                .thenThrow(new BusinessException("支付宝扫码支付下单失败，请稍后重试或改用支付宝收银台"));
        PaymentOrderServiceImpl service = service(
                paymentOrderMapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                alipayPaymentClient
        );

        assertThatThrownBy(() -> service.createRechargeOrder(7L, 5000, PaymentMethod.QR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("改用支付宝收银台");

        verify(paymentOrderMapper, never()).insert(any());
    }

    @Test
    void createRechargeOrderReturnsPagePayHtmlFromLocalOrderSnapshot() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        UserMembershipMapper userMembershipMapper = mock(UserMembershipMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        CreditAccountService creditAccountService = mock(CreditAccountService.class);
        MembershipService membershipService = mock(MembershipService.class);

        UserMembership activeMembership = new UserMembership();
        activeMembership.setTier(MembershipTier.SUPER.name());
        activeMembership.setExpiresAt(LocalDateTime.now().plusDays(5));
        when(membershipService.getActiveMembership(7L)).thenReturn(activeMembership);
        UserMembership lowerMembershipWithLaterExpiry = new UserMembership();
        lowerMembershipWithLaterExpiry.setTier(MembershipTier.BASIC.name());
        lowerMembershipWithLaterExpiry.setExpiresAt(LocalDateTime.now().plusDays(30));
        when(userMembershipMapper.selectOne(any())).thenReturn(lowerMembershipWithLaterExpiry);
        when(alipayPaymentClient.pagePay(any())).thenReturn(new AlipayPagePayResult(
                "<form action=\"https://openapi-sandbox.dl.alipaydev.com/gateway.do\" method=\"post\"></form>"
        ));

        PaymentOrderServiceImpl service = new PaymentOrderServiceImpl(
                paymentOrderMapper,
                creditAccountService,
                membershipService,
                new PaymentProductCatalog(),
                alipayProperties(),
                alipayPaymentClient,
                transactionTemplate()
        );

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000);

        ArgumentCaptor<PaymentOrder> orderCaptor = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(paymentOrderMapper).insert(orderCaptor.capture());
        PaymentOrder order = orderCaptor.getValue();
        ArgumentCaptor<AlipayPagePayRequest> pagePayRequestCaptor =
                ArgumentCaptor.forClass(AlipayPagePayRequest.class);
        verify(alipayPaymentClient).pagePay(pagePayRequestCaptor.capture());
        AlipayPagePayRequest pagePayRequest = pagePayRequestCaptor.getValue();

        assertThat(order.getUserId()).isEqualTo(7L);
        assertThat(order.getOrderType()).isEqualTo(PaymentOrderType.RECHARGE.name());
        assertThat(order.getAmountCents()).isEqualTo(5000);
        assertThat(order.getBaseCredits()).isEqualTo(1000);
        assertThat(order.getBonusCredits()).isEqualTo(150);
        assertThat(order.getTotalCredits()).isEqualTo(1150);
        assertThat(order.getQrcode()).isNull();
        assertThat(response.paymentHtml()).contains("openapi-sandbox.dl.alipaydev.com");
        assertThat(response.paymentMethod()).isEqualTo(PaymentMethod.PAGE.name());
        assertThat(response.qrCode()).isNull();
        assertThat(response.expiresAt()).isEqualTo(order.getExpiresAt());
        assertThat(response.totalCredits()).isEqualTo(1150);
        assertThat(pagePayRequest.orderNo()).isEqualTo(order.getOrderNo());
        assertThat(pagePayRequest.subject()).isEqualTo(order.getProductName());
        assertThat(pagePayRequest.totalAmount()).isEqualByComparingTo("50.00");
        assertThat(pagePayRequest.notifyUrl()).isEqualTo(alipayProperties().getNotifyUrl());
        assertThat(pagePayRequest.returnUrl()).isEqualTo(alipayProperties().getReturnUrl());
        assertThat(pagePayRequest.timeoutExpress()).isEqualTo(alipayProperties().getTimeoutExpress());
    }

    @Test
    void settlePaidRechargeOrderGrantsCreditsOnlyOnce() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        UserMembershipMapper userMembershipMapper = mock(UserMembershipMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        CreditAccountService creditAccountService = mock(CreditAccountService.class);
        MembershipService membershipService = mock(MembershipService.class);

        PaymentOrder createdOrder = new PaymentOrder();
        createdOrder.setId(21L);
        createdOrder.setUserId(7L);
        createdOrder.setOrderNo("PAY202606290001");
        createdOrder.setOrderType(PaymentOrderType.RECHARGE.name());
        createdOrder.setAmountCents(5000);
        createdOrder.setBaseCredits(1000);
        createdOrder.setBonusCredits(150);
        createdOrder.setTotalCredits(1150);
        createdOrder.setStatus(PaymentOrderStatus.CREATED.name());

        PaymentOrderServiceImpl service = new PaymentOrderServiceImpl(
                paymentOrderMapper,
                creditAccountService,
                membershipService,
                new PaymentProductCatalog(),
                alipayProperties(),
                alipayPaymentClient,
                transactionTemplate()
        );

        when(paymentOrderMapper.selectOne(any()))
                .thenReturn(createdOrder)
                .thenReturn(paidCopy(createdOrder));
        when(paymentOrderMapper.update(any(), any())).thenReturn(1);

        PaymentSettlement firstSettlement = service.settlePaidOrder(
                "PAY202606290001",
                "202606292200145",
                "notify-1",
                new BigDecimal("50.00"),
                "TRADE_SUCCESS",
                "app-1",
                "seller-1"
        );
        PaymentSettlement repeatedSettlement = service.settlePaidOrder(
                "PAY202606290001",
                "202606292200145",
                "notify-1",
                new BigDecimal("50.00"),
                "TRADE_SUCCESS",
                "app-1",
                "seller-1"
        );

        assertThat(firstSettlement.settled()).isTrue();
        assertThat(repeatedSettlement.settled()).isFalse();
        verify(creditAccountService).grantRechargeCredits(createdOrder);
        verify(membershipService, never()).activateMembership(any());
    }

    @Test
    void settlePaidOrderRejectsMismatchedSellerBeforeGrantingCredits() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        UserMembershipMapper userMembershipMapper = mock(UserMembershipMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        CreditAccountService creditAccountService = mock(CreditAccountService.class);
        MembershipService membershipService = mock(MembershipService.class);

        PaymentOrder order = new PaymentOrder();
        order.setId(22L);
        order.setUserId(7L);
        order.setOrderNo("PAY202606290002");
        order.setOrderType(PaymentOrderType.RECHARGE.name());
        order.setAmountCents(5000);
        order.setBaseCredits(1000);
        order.setBonusCredits(150);
        order.setTotalCredits(1150);
        order.setStatus(PaymentOrderStatus.CREATED.name());
        order.setAppId("app-1");
        order.setSellerId("seller-1");
        when(paymentOrderMapper.selectOne(any())).thenReturn(order);

        PaymentOrderServiceImpl service = new PaymentOrderServiceImpl(
                paymentOrderMapper,
                creditAccountService,
                membershipService,
                new PaymentProductCatalog(),
                alipayProperties(),
                alipayPaymentClient,
                transactionTemplate()
        );

        assertThatThrownBy(() -> service.settlePaidOrder(
                "PAY202606290002",
                "202606292200146",
                "notify-2",
                new BigDecimal("50.00"),
                "TRADE_SUCCESS",
                "app-1",
                "seller-other"
        )).isInstanceOf(BusinessException.class)
                .hasMessage("支付商户不匹配");

        verify(paymentOrderMapper, never()).update(any(), any());
        verify(creditAccountService, never()).grantRechargeCredits(any());
    }

    @Test
    void settlePaidOrderRejectsMissingAppIdBeforeGrantingCredits() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        UserMembershipMapper userMembershipMapper = mock(UserMembershipMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        CreditAccountService creditAccountService = mock(CreditAccountService.class);
        MembershipService membershipService = mock(MembershipService.class);

        PaymentOrder order = pendingRechargeOrder("PAY202606290003");
        order.setAppId("app-1");
        order.setSellerId("seller-1");
        when(paymentOrderMapper.selectOne(any())).thenReturn(order);

        PaymentOrderServiceImpl service = new PaymentOrderServiceImpl(
                paymentOrderMapper,
                creditAccountService,
                membershipService,
                new PaymentProductCatalog(),
                alipayProperties(),
                alipayPaymentClient,
                transactionTemplate()
        );

        assertThatThrownBy(() -> service.settlePaidOrder(
                "PAY202606290003",
                "202606292200147",
                "notify-3",
                new BigDecimal("50.00"),
                "TRADE_SUCCESS",
                null,
                "seller-1"
        )).isInstanceOf(BusinessException.class)
                .hasMessage("支付应用不匹配");

        verify(paymentOrderMapper, never()).update(any(), any());
        verify(creditAccountService, never()).grantRechargeCredits(any());
    }

    @Test
    void settlePaidOrderRejectsMissingAmountBeforeGrantingCredits() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        UserMembershipMapper userMembershipMapper = mock(UserMembershipMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        CreditAccountService creditAccountService = mock(CreditAccountService.class);
        MembershipService membershipService = mock(MembershipService.class);

        PaymentOrder order = pendingRechargeOrder("PAY202606290004");
        order.setAppId("app-1");
        order.setSellerId("seller-1");
        when(paymentOrderMapper.selectOne(any())).thenReturn(order);

        PaymentOrderServiceImpl service = new PaymentOrderServiceImpl(
                paymentOrderMapper,
                creditAccountService,
                membershipService,
                new PaymentProductCatalog(),
                alipayProperties(),
                alipayPaymentClient,
                transactionTemplate()
        );

        assertThatThrownBy(() -> service.settlePaidOrder(
                "PAY202606290004",
                "202606292200148",
                "notify-4",
                null,
                "TRADE_SUCCESS",
                "app-1",
                "seller-1"
        )).isInstanceOf(BusinessException.class)
                .hasMessage("支付金额缺失");

        verify(paymentOrderMapper, never()).update(any(), any());
        verify(creditAccountService, never()).grantRechargeCredits(any());
    }

    @Test
    void settlePaidOrderRejectsMissingSellerBeforeGrantingCredits() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        CreditAccountService creditAccountService = mock(CreditAccountService.class);
        PaymentOrder order = pendingRechargeOrder("PAY202607170019");
        order.setAppId("app-1");
        order.setSellerId("seller-1");
        when(paymentOrderMapper.selectOne(any())).thenReturn(order);
        PaymentOrderServiceImpl service = service(
                paymentOrderMapper,
                mock(UserMembershipMapper.class),
                creditAccountService,
                mock(MembershipService.class),
                mock(AlipayPaymentClient.class)
        );

        assertThatThrownBy(() -> service.settlePaidOrder(
                order.getOrderNo(), "trade-19", "notify-19", new BigDecimal("50.00"),
                "TRADE_SUCCESS", "app-1", null
        )).isInstanceOf(BusinessException.class)
                .hasMessage("支付商户不匹配");

        verify(paymentOrderMapper, never()).update(any(), any());
        verify(creditAccountService, never()).grantRechargeCredits(any());
    }

    @Test
    void settlementRollsBackWhenCreditGrantFails() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        CreditAccountService creditAccountService = mock(CreditAccountService.class);
        PaymentOrder order = pendingRechargeOrder("PAY202607170018");
        order.setAppId("app-1");
        order.setSellerId("seller-1");
        when(paymentOrderMapper.selectOne(any())).thenReturn(order);
        when(paymentOrderMapper.update(any(), any())).thenReturn(1);
        doThrow(new BusinessException("积分入账失败"))
                .when(creditAccountService).grantRechargeCredits(any());
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        PaymentOrderServiceImpl service = new PaymentOrderServiceImpl(
                paymentOrderMapper,
                creditAccountService,
                mock(MembershipService.class),
                new PaymentProductCatalog(),
                alipayProperties(),
                mock(AlipayPaymentClient.class),
                new TransactionTemplate(transactionManager)
        );

        assertThatThrownBy(() -> service.settlePaidOrder(
                order.getOrderNo(), "trade-18", "notify-18", new BigDecimal("50.00"),
                "TRADE_SUCCESS", "app-1", "seller-1"
        )).isInstanceOf(BusinessException.class)
                .hasMessage("积分入账失败");

        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void settlePaidOrderNeverCreditsClosedOrder() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        CreditAccountService creditAccountService = mock(CreditAccountService.class);
        MembershipService membershipService = mock(MembershipService.class);
        PaymentOrder closed = pendingRechargeOrder("PAY202607170020");
        closed.setStatus(PaymentOrderStatus.CLOSED.name());
        closed.setAppId("app-1");
        closed.setSellerId("seller-1");
        when(paymentOrderMapper.selectOne(any())).thenReturn(closed);
        PaymentOrderServiceImpl service = service(
                paymentOrderMapper,
                mock(UserMembershipMapper.class),
                creditAccountService,
                membershipService,
                alipayPaymentClient
        );

        PaymentSettlement settlement = service.settlePaidOrder(
                closed.getOrderNo(), "trade-closed", "notify-closed", new BigDecimal("50.00"),
                "TRADE_SUCCESS", "app-1", "seller-1"
        );

        assertThat(settlement.settled()).isFalse();
        assertThat(settlement.status()).isEqualTo(PaymentOrderStatus.CLOSED.name());
        verify(paymentOrderMapper, never()).update(any(), any());
        verifyNoInteractions(creditAccountService, membershipService);
    }

    @Test
    void expiredQrOrderIsCancelledAndClosedWhenStillUnpaid() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        PaymentOrder expired = pendingRechargeOrder("PAY202607170021");
        expired.setPaymentMethod(PaymentMethod.QR.name());
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(paymentOrderMapper.selectList(any())).thenReturn(List.of(expired));
        when(alipayPaymentClient.query(expired.getOrderNo())).thenReturn(new AlipayQueryResult(
                expired.getOrderNo(), null, "WAIT_BUYER_PAY", null
        ));
        when(alipayPaymentClient.cancel(expired.getOrderNo()))
                .thenReturn(new AlipayCancelResult(true, false, "close"));
        when(paymentOrderMapper.update(any(), any())).thenReturn(1);
        PaymentOrderServiceImpl service = service(
                paymentOrderMapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                alipayPaymentClient
        );

        service.processExpiredQrOrders();

        ArgumentCaptor<PaymentOrder> updateCaptor = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(paymentOrderMapper).update(updateCaptor.capture(), any());
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo(PaymentOrderStatus.CLOSED.name());
    }

    @Test
    void expiredQrOrderRemainsCreatedWhenAlipayRequestsCancelRetry() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        PaymentOrder expired = pendingRechargeOrder("PAY202607170022");
        expired.setPaymentMethod(PaymentMethod.QR.name());
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(paymentOrderMapper.selectList(any())).thenReturn(List.of(expired));
        when(alipayPaymentClient.query(expired.getOrderNo())).thenReturn(new AlipayQueryResult(
                expired.getOrderNo(), null, "WAIT_BUYER_PAY", null
        ));
        when(alipayPaymentClient.cancel(expired.getOrderNo()))
                .thenReturn(new AlipayCancelResult(true, true, "close"));
        PaymentOrderServiceImpl service = service(
                paymentOrderMapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                alipayPaymentClient
        );

        service.processExpiredQrOrders();

        verify(paymentOrderMapper, never()).update(any(), any());
    }

    @Test
    void expiredQrOrderSettlesWhenFinalQueryFindsPayment() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        CreditAccountService creditAccountService = mock(CreditAccountService.class);
        PaymentOrder expired = pendingRechargeOrder("PAY202607170023");
        expired.setPaymentMethod(PaymentMethod.QR.name());
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(paymentOrderMapper.selectList(any())).thenReturn(List.of(expired));
        when(paymentOrderMapper.selectOne(any())).thenReturn(expired);
        when(paymentOrderMapper.update(any(), any())).thenReturn(1);
        when(alipayPaymentClient.query(expired.getOrderNo())).thenReturn(new AlipayQueryResult(
                expired.getOrderNo(), "202607170023", "TRADE_SUCCESS", new BigDecimal("50.00")
        ));
        PaymentOrderServiceImpl service = service(
                paymentOrderMapper,
                mock(UserMembershipMapper.class),
                creditAccountService,
                mock(MembershipService.class),
                alipayPaymentClient
        );

        service.processExpiredQrOrders();

        verify(creditAccountService).grantRechargeCredits(expired);
        verify(alipayPaymentClient, never()).cancel(any());
    }

    private PaymentOrder pendingRechargeOrder(String orderNo) {
        PaymentOrder order = new PaymentOrder();
        order.setId(22L);
        order.setUserId(7L);
        order.setOrderNo(orderNo);
        order.setOrderType(PaymentOrderType.RECHARGE.name());
        order.setAmountCents(5000);
        order.setBaseCredits(1000);
        order.setBonusCredits(150);
        order.setTotalCredits(1150);
        order.setStatus(PaymentOrderStatus.CREATED.name());
        return order;
    }

    private PaymentOrder paidCopy(PaymentOrder order) {
        PaymentOrder paid = new PaymentOrder();
        paid.setId(order.getId());
        paid.setUserId(order.getUserId());
        paid.setOrderNo(order.getOrderNo());
        paid.setOrderType(order.getOrderType());
        paid.setAmountCents(order.getAmountCents());
        paid.setBaseCredits(order.getBaseCredits());
        paid.setBonusCredits(order.getBonusCredits());
        paid.setTotalCredits(order.getTotalCredits());
        paid.setStatus(PaymentOrderStatus.PAID.name());
        return paid;
    }

    private PaymentOrderServiceImpl service(PaymentOrderMapper paymentOrderMapper,
                                            UserMembershipMapper userMembershipMapper,
                                            CreditAccountService creditAccountService,
                                            MembershipService membershipService,
                                            AlipayPaymentClient alipayPaymentClient) {
        return new PaymentOrderServiceImpl(
                paymentOrderMapper,
                creditAccountService,
                membershipService,
                new PaymentProductCatalog(),
                alipayProperties(),
                alipayPaymentClient,
                transactionTemplate()
        );
    }

    private TransactionTemplate transactionTemplate() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return new TransactionTemplate(transactionManager);
    }

    private AlipayProperties alipayProperties() {
        AlipayProperties properties = new AlipayProperties();
        properties.setAppId("app-1");
        properties.setSellerId("seller-1");
        properties.setNotifyUrl("https://www.yzmovie.cn/api/v1/payments/alipay/notify");
        properties.setReturnUrl("https://www.yzmovie.cn/membership/payment/result/");
        properties.setTimeoutExpress("30m");
        return properties;
    }
}
