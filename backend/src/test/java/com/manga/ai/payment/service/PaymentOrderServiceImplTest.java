package com.manga.ai.payment.service;

import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.payment.client.AlipayPaymentClient;
import com.manga.ai.payment.config.AlipayProperties;
import com.manga.ai.payment.dto.AlipayPagePayRequest;
import com.manga.ai.payment.dto.AlipayPagePayResult;
import com.manga.ai.payment.dto.PaymentOrderCreateResponse;
import com.manga.ai.payment.dto.PaymentSettlement;
import com.manga.ai.payment.entity.PaymentOrder;
import com.manga.ai.payment.entity.UserMembership;
import com.manga.ai.payment.enums.MembershipTier;
import com.manga.ai.payment.enums.PaymentOrderStatus;
import com.manga.ai.payment.enums.PaymentOrderType;
import com.manga.ai.payment.mapper.PaymentOrderMapper;
import com.manga.ai.payment.mapper.UserMembershipMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentOrderServiceImplTest {

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
        when(userMembershipMapper.selectOne(any())).thenReturn(activeMembership);
        when(alipayPaymentClient.pagePay(any())).thenReturn(new AlipayPagePayResult(
                "<form action=\"https://openapi-sandbox.dl.alipaydev.com/gateway.do\" method=\"post\"></form>"
        ));

        PaymentOrderServiceImpl service = new PaymentOrderServiceImpl(
                paymentOrderMapper,
                userMembershipMapper,
                creditAccountService,
                membershipService,
                new PaymentProductCatalog(),
                alipayProperties(),
                alipayPaymentClient
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
                userMembershipMapper,
                creditAccountService,
                membershipService,
                new PaymentProductCatalog(),
                alipayProperties(),
                alipayPaymentClient
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
                userMembershipMapper,
                creditAccountService,
                membershipService,
                new PaymentProductCatalog(),
                alipayProperties(),
                alipayPaymentClient
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
                userMembershipMapper,
                creditAccountService,
                membershipService,
                new PaymentProductCatalog(),
                alipayProperties(),
                alipayPaymentClient
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
                userMembershipMapper,
                creditAccountService,
                membershipService,
                new PaymentProductCatalog(),
                alipayProperties(),
                alipayPaymentClient
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

    private AlipayProperties alipayProperties() {
        AlipayProperties properties = new AlipayProperties();
        properties.setAppId("app-1");
        properties.setSellerId("seller-1");
        properties.setNotifyUrl("https://www.yzmovie.com/api/v1/payments/alipay/notify");
        properties.setReturnUrl("https://www.yzmovie.com/membership/payment/result/");
        properties.setTimeoutExpress("30m");
        return properties;
    }
}
