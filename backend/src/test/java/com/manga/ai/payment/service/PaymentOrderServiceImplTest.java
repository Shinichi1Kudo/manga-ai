package com.manga.ai.payment.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.manga.ai.payment.exception.RetryablePaymentGatewayException;
import com.manga.ai.payment.mapper.PaymentOrderMapper;
import com.manga.ai.payment.mapper.UserMembershipMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentOrderServiceImplTest {

    static {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PaymentOrder.class);
    }

    @Test
    void qrPrecreateRetriesSamePersistedOrderNumberAfterTransientFailure() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper paymentRetrySleeper = mock(PaymentRetrySleeper.class);
        AtomicReference<PaymentOrder> persisted = stubOrderPersistence(paymentOrderMapper);
        when(alipayPaymentClient.precreate(any()))
                .thenThrow(new RetryablePaymentGatewayException("temporary", new RuntimeException("504")))
                .thenReturn(new AlipayPrecreateResult("https://qr.alipay.com/example"));
        PaymentOrderServiceImpl service = service(
                paymentOrderMapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                alipayPaymentClient,
                paymentRetrySleeper
        );

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        ArgumentCaptor<PaymentOrder> orderCaptor = ArgumentCaptor.forClass(PaymentOrder.class);
        ArgumentCaptor<AlipayPrecreateRequest> requestCaptor =
                ArgumentCaptor.forClass(AlipayPrecreateRequest.class);
        InOrder calls = inOrder(paymentOrderMapper, alipayPaymentClient);
        calls.verify(paymentOrderMapper).insert(orderCaptor.capture());
        calls.verify(alipayPaymentClient, times(2)).precreate(requestCaptor.capture());
        PaymentOrder order = orderCaptor.getValue();
        List<AlipayPrecreateRequest> requests = requestCaptor.getAllValues();

        assertThat(order.getUserId()).isEqualTo(7L);
        assertThat(order.getOrderType()).isEqualTo(PaymentOrderType.RECHARGE.name());
        assertThat(order.getAmountCents()).isEqualTo(5000);
        assertThat(order.getProductName()).isEqualTo("积分充值¥50");
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.QR.name());
        assertThat(order.getQrcode()).isEqualTo("https://qr.alipay.com/example");
        assertThat(order.getExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(119));
        assertThat(order.getCreatedAt()).isNotNull();
        assertThat(order.getUpdatedAt()).isNotNull();
        assertThat(requests).allMatch(request -> request.equals(requests.get(0)));
        assertThat(requests.get(0).orderNo()).isEqualTo(order.getOrderNo());
        assertThat(requests.get(0).subject()).isEqualTo(order.getProductName());
        assertThat(requests.get(0).totalAmount()).isEqualByComparingTo("50.00");
        assertThat(requests.get(0).notifyUrl()).isEqualTo(alipayProperties().getNotifyUrl());
        assertThat(requests.get(0).timeoutExpress()).isEqualTo("2h");
        assertThat(persisted.get()).isSameAs(order);
        assertThat(response.orderNo()).isEqualTo(order.getOrderNo());
        assertThat(response.paymentMethod()).isEqualTo(PaymentMethod.QR.name());
        assertThat(response.paymentHtml()).isNull();
        assertThat(response.qrCode()).isEqualTo("https://qr.alipay.com/example");
        assertThat(response.expiresAt()).isEqualTo(order.getExpiresAt());
        assertThat(response.paymentReady()).isTrue();
        assertThat(response.paymentMessage()).isNull();
        verify(paymentRetrySleeper).pauseBeforeAttempt(2);
    }

    @Test
    void qrPrecreateExhaustionReturnsRecoverablePersistedOrder() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper paymentRetrySleeper = mock(PaymentRetrySleeper.class);
        AtomicReference<PaymentOrder> persisted = new AtomicReference<>();
        when(paymentOrderMapper.insert(any())).thenAnswer(invocation -> {
            persisted.set(invocation.getArgument(0));
            return 1;
        });
        when(paymentOrderMapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(alipayPaymentClient.precreate(any()))
                .thenThrow(new RetryablePaymentGatewayException("temporary", new RuntimeException("504")));
        PaymentOrderServiceImpl service = service(
                paymentOrderMapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                alipayPaymentClient,
                paymentRetrySleeper
        );

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        ArgumentCaptor<PaymentOrder> orderCaptor = ArgumentCaptor.forClass(PaymentOrder.class);
        ArgumentCaptor<AlipayPrecreateRequest> requestCaptor =
                ArgumentCaptor.forClass(AlipayPrecreateRequest.class);
        verify(paymentOrderMapper).insert(orderCaptor.capture());
        verify(alipayPaymentClient, times(3)).precreate(requestCaptor.capture());
        verify(paymentRetrySleeper, times(2)).pauseBeforeAttempt(anyInt());
        verify(paymentRetrySleeper).pauseBeforeAttempt(2);
        verify(paymentRetrySleeper).pauseBeforeAttempt(3);

        PaymentOrder order = orderCaptor.getValue();
        List<AlipayPrecreateRequest> requests = requestCaptor.getAllValues();
        assertThat(requests).allMatch(request -> request.equals(requests.get(0)));
        assertThat(requests.get(0).orderNo()).isEqualTo(order.getOrderNo());
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(order.getQrcode()).isNull();
        assertThat(response.orderNo()).isEqualTo(order.getOrderNo());
        assertThat(response.status()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.paymentHtml()).isNull();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentMessage())
                .isEqualTo("支付宝扫码支付服务暂时不可用，请稍后重试或改用支付宝收银台");
        verify(paymentOrderMapper, never()).update(any(), any());
        verify(paymentOrderMapper).selectOne(any());
    }

    @Test
    void qrPrecreateExhaustionReturnsConcurrentTerminalOrder() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper paymentRetrySleeper = mock(PaymentRetrySleeper.class);
        AtomicReference<PaymentOrder> persisted = new AtomicReference<>();
        when(paymentOrderMapper.insert(any())).thenAnswer(invocation -> {
            persisted.set(invocation.getArgument(0));
            return 1;
        });
        when(paymentOrderMapper.selectOne(any())).thenAnswer(invocation -> {
            persisted.get().setStatus(PaymentOrderStatus.PAID.name());
            persisted.get().setQrcode("https://qr.alipay.com/callback-race");
            return persisted.get();
        });
        when(alipayPaymentClient.precreate(any()))
                .thenThrow(new RetryablePaymentGatewayException("temporary", new RuntimeException("504")));
        PaymentOrderServiceImpl service = service(
                paymentOrderMapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                alipayPaymentClient,
                paymentRetrySleeper
        );

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        assertThat(response.status()).isEqualTo(PaymentOrderStatus.PAID.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.paymentHtml()).isNull();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentMessage()).isNull();
        verify(alipayPaymentClient, times(3)).precreate(any());
        verify(paymentRetrySleeper, times(2)).pauseBeforeAttempt(anyInt());
        verify(paymentOrderMapper).selectOne(any());
    }

    @Test
    void nonRetryableQrBusinessErrorClosesInsertedCreatedOrderAndRethrowsSafeMessage() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper paymentRetrySleeper = mock(PaymentRetrySleeper.class);
        String safeMessage = "支付宝扫码支付下单失败，请稍后重试或改用支付宝收银台";
        when(alipayPaymentClient.precreate(any())).thenThrow(new BusinessException(safeMessage));
        when(paymentOrderMapper.update(any(), any())).thenReturn(1);
        PaymentOrderServiceImpl service = service(
                paymentOrderMapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                alipayPaymentClient,
                paymentRetrySleeper
        );

        assertThatThrownBy(() -> service.createRechargeOrder(7L, 5000, PaymentMethod.QR))
                .isInstanceOf(BusinessException.class)
                .hasMessage(safeMessage);

        ArgumentCaptor<PaymentOrder> insertedCaptor = ArgumentCaptor.forClass(PaymentOrder.class);
        ArgumentCaptor<PaymentOrder> updateCaptor = ArgumentCaptor.forClass(PaymentOrder.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaUpdateWrapper> wrapperCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        InOrder calls = inOrder(paymentOrderMapper, alipayPaymentClient);
        calls.verify(paymentOrderMapper).insert(insertedCaptor.capture());
        calls.verify(alipayPaymentClient).precreate(any());
        verify(paymentOrderMapper).update(updateCaptor.capture(), wrapperCaptor.capture());
        verify(alipayPaymentClient).precreate(any());
        verifyNoInteractions(paymentRetrySleeper);

        PaymentOrder inserted = insertedCaptor.getValue();
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo(PaymentOrderStatus.CLOSED.name());
        assertThat(updateCaptor.getValue().getUpdatedAt()).isNotNull();
        assertCreatedGuard(wrapperCaptor.getValue(), inserted.getOrderNo());
    }

    @Test
    void nonRetryableQrBusinessErrorReturnsConcurrentTerminalOrderWhenCloseLoses() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper paymentRetrySleeper = mock(PaymentRetrySleeper.class);
        AtomicReference<PaymentOrder> persisted = new AtomicReference<>();
        when(paymentOrderMapper.insert(any())).thenAnswer(invocation -> {
            persisted.set(invocation.getArgument(0));
            return 1;
        });
        when(paymentOrderMapper.update(any(), any())).thenAnswer(invocation -> {
            persisted.get().setStatus(PaymentOrderStatus.PAID.name());
            persisted.get().setQrcode("https://qr.alipay.com/callback-race");
            return 0;
        });
        when(paymentOrderMapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(alipayPaymentClient.precreate(any()))
                .thenThrow(new BusinessException("支付宝扫码支付下单失败，请稍后重试或改用支付宝收银台"));
        PaymentOrderServiceImpl service = service(
                paymentOrderMapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                alipayPaymentClient,
                paymentRetrySleeper
        );
        AtomicReference<PaymentOrderCreateResponse> response = new AtomicReference<>();

        assertThatCode(() -> response.set(service.createRechargeOrder(7L, 5000, PaymentMethod.QR)))
                .doesNotThrowAnyException();

        assertThat(response.get().status()).isEqualTo(PaymentOrderStatus.PAID.name());
        assertThat(response.get().paymentReady()).isFalse();
        assertThat(response.get().paymentHtml()).isNull();
        assertThat(response.get().qrCode()).isNull();
        assertThat(response.get().paymentMessage()).isNull();
        verify(alipayPaymentClient).precreate(any());
        verifyNoInteractions(paymentRetrySleeper);
        verify(paymentOrderMapper).selectOne(any());
    }

    @Test
    void interruptedQrRetryReturnsConcurrentTerminalOrderWhenCloseLoses() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper paymentRetrySleeper = mock(PaymentRetrySleeper.class);
        AtomicReference<PaymentOrder> persisted = new AtomicReference<>();
        when(paymentOrderMapper.insert(any())).thenAnswer(invocation -> {
            persisted.set(invocation.getArgument(0));
            return 1;
        });
        when(paymentOrderMapper.update(any(), any())).thenAnswer(invocation -> {
            persisted.get().setStatus(PaymentOrderStatus.CLOSED.name());
            return 0;
        });
        when(paymentOrderMapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(alipayPaymentClient.precreate(any()))
                .thenThrow(new RetryablePaymentGatewayException("temporary", new RuntimeException("504")));
        doThrow(new BusinessException("支付宝扫码支付请求已中断，请重新尝试"))
                .when(paymentRetrySleeper).pauseBeforeAttempt(2);
        PaymentOrderServiceImpl service = service(
                paymentOrderMapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                alipayPaymentClient,
                paymentRetrySleeper
        );
        AtomicReference<PaymentOrderCreateResponse> response = new AtomicReference<>();

        assertThatCode(() -> response.set(service.createRechargeOrder(7L, 5000, PaymentMethod.QR)))
                .doesNotThrowAnyException();

        assertThat(response.get().status()).isEqualTo(PaymentOrderStatus.CLOSED.name());
        assertThat(response.get().paymentReady()).isFalse();
        assertThat(response.get().paymentHtml()).isNull();
        assertThat(response.get().qrCode()).isNull();
        assertThat(response.get().paymentMessage()).isNull();
        verify(alipayPaymentClient).precreate(any());
        verify(paymentRetrySleeper).pauseBeforeAttempt(2);
        verify(paymentOrderMapper).selectOne(any());
    }

    @Test
    void qrSuccessConditionallyStoresQrForCreatedOrder() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper paymentRetrySleeper = mock(PaymentRetrySleeper.class);
        stubOrderPersistence(paymentOrderMapper);
        when(alipayPaymentClient.precreate(any()))
                .thenReturn(new AlipayPrecreateResult("https://qr.alipay.com/example"));
        PaymentOrderServiceImpl service = service(
                paymentOrderMapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                alipayPaymentClient,
                paymentRetrySleeper
        );

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        ArgumentCaptor<PaymentOrder> insertedCaptor = ArgumentCaptor.forClass(PaymentOrder.class);
        ArgumentCaptor<PaymentOrder> updateCaptor = ArgumentCaptor.forClass(PaymentOrder.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaUpdateWrapper> wrapperCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(paymentOrderMapper).insert(insertedCaptor.capture());
        verify(paymentOrderMapper).update(updateCaptor.capture(), wrapperCaptor.capture());
        PaymentOrder update = updateCaptor.getValue();
        assertThat(update.getQrcode()).isEqualTo("https://qr.alipay.com/example");
        assertThat(update.getUpdatedAt()).isNotNull();
        assertThat(update.getStatus()).isNull();
        assertThat(update.getOrderNo()).isNull();
        assertCreatedGuard(wrapperCaptor.getValue(), insertedCaptor.getValue().getOrderNo());
        assertThat(response.paymentReady()).isTrue();
        assertThat(response.paymentMessage()).isNull();
        verifyNoInteractions(paymentRetrySleeper);
    }

    @Test
    void qrUpdateMissWithCreatedOrderReturnsRecoverableUnreadyResponse() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        AtomicReference<PaymentOrder> inserted = new AtomicReference<>();
        when(paymentOrderMapper.insert(any())).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(paymentOrderMapper.update(any(), any())).thenReturn(0);
        when(paymentOrderMapper.selectOne(any())).thenAnswer(invocation -> inserted.get());
        when(alipayPaymentClient.precreate(any()))
                .thenReturn(new AlipayPrecreateResult("https://qr.alipay.com/not-persisted"));
        PaymentOrderServiceImpl service = service(
                paymentOrderMapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                alipayPaymentClient,
                mock(PaymentRetrySleeper.class)
        );

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        assertThat(response.status()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentMessage())
                .isEqualTo("支付宝扫码支付服务暂时不可用，请稍后重试或改用支付宝收银台");
        assertThat(inserted.get().getQrcode()).isNull();
        verify(paymentOrderMapper).update(any(), any());
        verify(paymentOrderMapper).selectOne(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void unusableQrResultReturnsRecoverableUnreadyResponse(String qrCode) {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        AtomicReference<PaymentOrder> inserted = new AtomicReference<>();
        when(paymentOrderMapper.insert(any())).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(paymentOrderMapper.selectOne(any())).thenAnswer(invocation -> inserted.get());
        when(alipayPaymentClient.precreate(any())).thenReturn(new AlipayPrecreateResult(qrCode));
        PaymentOrderServiceImpl service = service(
                paymentOrderMapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                alipayPaymentClient,
                mock(PaymentRetrySleeper.class)
        );

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        assertThat(response.status()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentMessage())
                .isEqualTo("支付宝扫码支付服务暂时不可用，请稍后重试或改用支付宝收银台");
        assertThat(inserted.get().getQrcode()).isNull();
        verify(paymentOrderMapper, never()).update(any(), any());
        verify(paymentOrderMapper).selectOne(any());
    }

    @Test
    void concurrentTerminalQrResultStripsPaymentPayloads() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        AtomicReference<PaymentOrder> inserted = new AtomicReference<>();
        when(paymentOrderMapper.insert(any())).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(paymentOrderMapper.update(any(), any())).thenAnswer(invocation -> {
            inserted.get().setStatus(PaymentOrderStatus.PAID.name());
            inserted.get().setQrcode("https://qr.alipay.com/callback-race");
            return 0;
        });
        when(paymentOrderMapper.selectOne(any())).thenAnswer(invocation -> inserted.get());
        when(alipayPaymentClient.precreate(any()))
                .thenReturn(new AlipayPrecreateResult("https://qr.alipay.com/example"));
        PaymentOrderServiceImpl service = service(
                paymentOrderMapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                alipayPaymentClient,
                mock(PaymentRetrySleeper.class)
        );

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        assertThat(response.status()).isEqualTo(PaymentOrderStatus.PAID.name());
        assertThat(response.paymentHtml()).isNull();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.paymentMessage()).isNull();
        verify(paymentOrderMapper).selectOne(any());
    }

    @Test
    void createMethodsDoNotDeclareTransactionalBoundary() throws NoSuchMethodException {
        assertThat(PaymentOrderServiceImpl.class.getMethod(
                "createRechargeOrder", Long.class, int.class, PaymentMethod.class
        ).getAnnotation(Transactional.class)).isNull();
        assertThat(PaymentOrderServiceImpl.class.getMethod(
                "createMembershipOrder", Long.class, String.class, PaymentMethod.class
        ).getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void retrySleeperRestoresInterruptFlagAndThrowsSafeBusinessException() {
        PaymentRetrySleeper sleeper = new PaymentRetrySleeper();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> sleeper.pauseBeforeAttempt(2))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("支付宝扫码支付请求已中断，请重新尝试");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
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
                mock(PaymentRetrySleeper.class),
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
        assertThat(response.paymentReady()).isTrue();
        assertThat(response.paymentMessage()).isNull();
        assertThat(pagePayRequest.orderNo()).isEqualTo(order.getOrderNo());
        assertThat(pagePayRequest.subject()).isEqualTo(order.getProductName());
        assertThat(pagePayRequest.totalAmount()).isEqualByComparingTo("50.00");
        assertThat(pagePayRequest.notifyUrl()).isEqualTo(alipayProperties().getNotifyUrl());
        assertThat(pagePayRequest.returnUrl()).isEqualTo(alipayProperties().getReturnUrl());
        assertThat(pagePayRequest.timeoutExpress()).isEqualTo(alipayProperties().getTimeoutExpress());
        InOrder calls = inOrder(alipayPaymentClient, paymentOrderMapper);
        calls.verify(alipayPaymentClient).pagePay(any());
        calls.verify(paymentOrderMapper).insert(any());
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
                mock(PaymentRetrySleeper.class),
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
                mock(PaymentRetrySleeper.class),
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
                mock(PaymentRetrySleeper.class),
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
                mock(PaymentRetrySleeper.class),
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
                mock(PaymentRetrySleeper.class),
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
        return service(
                paymentOrderMapper,
                userMembershipMapper,
                creditAccountService,
                membershipService,
                alipayPaymentClient,
                mock(PaymentRetrySleeper.class)
        );
    }

    private PaymentOrderServiceImpl service(PaymentOrderMapper paymentOrderMapper,
                                            UserMembershipMapper userMembershipMapper,
                                            CreditAccountService creditAccountService,
                                            MembershipService membershipService,
                                            AlipayPaymentClient alipayPaymentClient,
                                            PaymentRetrySleeper paymentRetrySleeper) {
        return new PaymentOrderServiceImpl(
                paymentOrderMapper,
                creditAccountService,
                membershipService,
                new PaymentProductCatalog(),
                alipayProperties(),
                alipayPaymentClient,
                paymentRetrySleeper,
                transactionTemplate()
        );
    }

    private AtomicReference<PaymentOrder> stubOrderPersistence(PaymentOrderMapper paymentOrderMapper) {
        AtomicReference<PaymentOrder> persisted = new AtomicReference<>();
        when(paymentOrderMapper.insert(any())).thenAnswer(invocation -> {
            PaymentOrder order = invocation.getArgument(0);
            assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
            assertThat(order.getQrcode()).isNull();
            assertThat(order.getExpiresAt()).isNotNull();
            assertThat(order.getCreatedAt()).isNotNull();
            assertThat(order.getUpdatedAt()).isNotNull();
            persisted.set(order);
            return 1;
        });
        when(paymentOrderMapper.update(any(), any())).thenAnswer(invocation -> {
            PaymentOrder update = invocation.getArgument(0);
            PaymentOrder order = persisted.get();
            if (update.getQrcode() != null) {
                order.setQrcode(update.getQrcode());
            }
            if (update.getStatus() != null) {
                order.setStatus(update.getStatus());
            }
            order.setUpdatedAt(update.getUpdatedAt());
            return 1;
        });
        when(paymentOrderMapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        return persisted;
    }

    @SuppressWarnings("rawtypes")
    private void assertCreatedGuard(LambdaUpdateWrapper wrapper, String orderNo) {
        assertThat(wrapper.getSqlSegment()).contains("order_no").contains("status");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = wrapper.getParamNameValuePairs();
        assertThat(parameters).containsValue(orderNo).containsValue(PaymentOrderStatus.CREATED.name());
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
