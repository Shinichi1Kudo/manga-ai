package com.manga.ai.payment.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import com.manga.ai.payment.dto.PendingPaymentOrderResponse;
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
import org.mockito.invocation.InvocationOnMock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
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
    void verifiedQrWaitsThenQueriesBeforePersistingAndResetsExpiryFromVerification() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper sleeper = mock(PaymentRetrySleeper.class);
        AtomicReference<LocalDateTime> verificationStartedAt = new AtomicReference<>();
        when(client.precreate(any())).thenAnswer(invocation -> precreateResult(
                invocation, "https://qr.alipay.com/verified"));
        doAnswer(invocation -> {
            verificationStartedAt.set(LocalDateTime.now());
            return null;
        }).when(sleeper).pauseBeforeQrVerification();
        when(client.query(any())).thenAnswer(invocation -> new AlipayQueryResult(
                invocation.getArgument(0), null, "WAIT_BUYER_PAY", null));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client, sleeper);

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        assertThat(response.paymentReady()).isTrue();
        assertThat(response.qrCode()).isEqualTo("https://qr.alipay.com/verified");
        assertThat(response.expiresAt())
                .isAfterOrEqualTo(verificationStartedAt.get().plusHours(2));
        assertThat(orders.get(response.orderNo()).getQrcode()).isEqualTo(response.qrCode());
        InOrder flow = inOrder(client, sleeper);
        flow.verify(client).precreate(any());
        flow.verify(sleeper).pauseBeforeQrVerification();
        flow.verify(client).query(response.orderNo());
        verify(sleeper, never()).pauseBeforeQrVerificationRetry();
    }

    @Test
    void verifiedQrRetriesOneNotFoundWithoutReplacingWhenSecondQueryWaits() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper sleeper = mock(PaymentRetrySleeper.class);
        when(client.precreate(any())).thenAnswer(invocation -> precreateResult(
                invocation, "https://qr.alipay.com/eventually-visible"));
        when(client.query(any()))
                .thenAnswer(invocation -> notFoundResult(invocation.getArgument(0)))
                .thenAnswer(invocation -> waitingResult(invocation.getArgument(0)));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client, sleeper);

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        assertThat(response.paymentReady()).isTrue();
        assertThat(response.qrCode()).isEqualTo("https://qr.alipay.com/eventually-visible");
        assertThat(orders).hasSize(1);
        verify(sleeper).pauseBeforeQrVerification();
        verify(sleeper).pauseBeforeQrVerificationRetry();
        verify(client, times(2)).query(response.orderNo());
    }

    @Test
    void verifiedQrReplacesExactlyOnceAfterThreeNotFoundQueries() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper sleeper = mock(PaymentRetrySleeper.class);
        when(client.precreate(any())).thenAnswer(invocation -> {
            AlipayPrecreateRequest request = invocation.getArgument(0);
            return new AlipayPrecreateResult(request.orderNo(),
                    "https://qr.alipay.com/" + request.orderNo());
        });
        AtomicInteger queries = new AtomicInteger();
        when(client.query(any())).thenAnswer(invocation -> {
            String orderNo = invocation.getArgument(0);
            return queries.incrementAndGet() <= 3 ? notFoundResult(orderNo) : waitingResult(orderNo);
        });
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client, sleeper);

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        assertThat(orders).hasSize(2);
        PaymentOrder source = orders.values().stream()
                .filter(order -> !order.getOrderNo().equals(response.orderNo()))
                .findFirst().orElseThrow();
        assertThat(source.getStatus()).isEqualTo(PaymentOrderStatus.CLOSED.name());
        assertThat(source.getQrcode()).isNull();
        assertThat(response.status()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(response.paymentReady()).isTrue();
        assertThat(response.qrCode()).isEqualTo("https://qr.alipay.com/" + response.orderNo());
        verify(client, times(2)).precreate(any());
        verify(client, times(4)).query(any());
        verify(sleeper, times(2)).pauseBeforeQrVerification();
        verify(sleeper, times(2)).pauseBeforeQrVerificationRetry();
    }

    @Test
    void verifiedQrStopsAfterReplacementAlsoHasThreeNotFoundQueries() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper sleeper = mock(PaymentRetrySleeper.class);
        when(client.precreate(any())).thenAnswer(invocation -> precreateResult(
                invocation, "https://qr.alipay.com/never-visible"));
        when(client.query(any())).thenAnswer(invocation -> notFoundResult(invocation.getArgument(0)));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client, sleeper);

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        assertThat(orders).hasSize(2);
        assertThat(orders.values()).filteredOn(order -> PaymentOrderStatus.CLOSED.name().equals(order.getStatus()))
                .hasSize(1);
        PaymentOrder replacement = orders.get(response.orderNo());
        assertThat(replacement.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(replacement.getQrcode()).isNull();
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentMessage())
                .isEqualTo("支付宝扫码支付服务暂时不可用，请稍后重试或改用支付宝收银台");
        verify(client, times(2)).precreate(any());
        verify(client, times(6)).query(any());
        verify(sleeper, times(2)).pauseBeforeQrVerification();
        verify(sleeper, times(4)).pauseBeforeQrVerificationRetry();
    }

    @Test
    void verifiedQrQueryFailureKeepsSingleCreatedOrderUnreadyAndUncertain() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper sleeper = mock(PaymentRetrySleeper.class);
        when(client.precreate(any())).thenAnswer(invocation -> precreateResult(
                invocation, "https://qr.alipay.com/must-not-leak"));
        when(client.query(any())).thenThrow(new BusinessException("gateway detail"));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client, sleeper);

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        assertThat(orders).hasSize(1);
        PaymentOrder persisted = orders.get(response.orderNo());
        assertThat(persisted.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(persisted.getQrcode()).isNull();
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentMessage()).isEqualTo("支付状态暂未确认，请稍后查询，请勿重复支付");
        verify(client).query(response.orderNo());
        verify(sleeper, never()).pauseBeforeQrVerificationRetry();
    }

    @Test
    void verifiedQrUnknownResultKeepsSingleCreatedOrderUnreadyAndUncertain() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        when(client.precreate(any())).thenAnswer(invocation -> precreateResult(
                invocation, "https://qr.alipay.com/must-not-leak"));
        when(client.query(any())).thenAnswer(invocation -> new AlipayQueryResult(
                invocation.getArgument(0), null, null, null, "40004", "ACQ.SYSTEM_ERROR"));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client);

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        assertThat(orders).hasSize(1);
        assertThat(orders.get(response.orderNo()).getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(orders.get(response.orderNo()).getQrcode()).isNull();
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentMessage()).isEqualTo("支付状态暂未确认，请稍后查询，请勿重复支付");
        verify(client).query(response.orderNo());
    }

    @Test
    void verifiedQrPaidRaceSettlesOnceWithoutReturningQr() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        CreditAccountService credits = mock(CreditAccountService.class);
        when(client.precreate(any())).thenAnswer(invocation -> precreateResult(
                invocation, "https://qr.alipay.com/must-not-return"));
        when(client.query(any())).thenAnswer(invocation -> new AlipayQueryResult(
                invocation.getArgument(0), "202608020001", "TRADE_SUCCESS", new BigDecimal("50.00")));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), credits,
                mock(MembershipService.class), client, mock(PaymentRetrySleeper.class));

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        assertThat(response.status()).isEqualTo(PaymentOrderStatus.PAID.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        assertThat(orders.get(response.orderNo()).getQrcode()).isNull();
        verify(credits).grantRechargeCredits(any());
        verify(credits, times(1)).grantRechargeCredits(any());
    }

    @Test
    void verifiedQrClosedTradeClosesLocalOrderWithoutQr() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        when(client.precreate(any())).thenAnswer(invocation -> precreateResult(
                invocation, "https://qr.alipay.com/must-not-return"));
        when(client.query(any())).thenAnswer(invocation -> new AlipayQueryResult(
                invocation.getArgument(0), null, "TRADE_CLOSED", null));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client);

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        assertThat(response.status()).isEqualTo(PaymentOrderStatus.CLOSED.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        assertThat(orders.get(response.orderNo()).getQrcode()).isNull();
    }

    @Test
    void resumeCreatedOrderWithoutQrUsesPostPrecreateVerification() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-VERIFY");
        orders.put(order.getOrderNo(), order);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper sleeper = mock(PaymentRetrySleeper.class);
        when(client.query(order.getOrderNo()))
                .thenReturn(waitingResult(order.getOrderNo()), waitingResult(order.getOrderNo()));
        when(client.precreate(any())).thenAnswer(invocation -> precreateResult(
                invocation, "https://qr.alipay.com/resume-verified"));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client, sleeper);

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());

        assertThat(response.paymentReady()).isTrue();
        assertThat(response.qrCode()).isEqualTo("https://qr.alipay.com/resume-verified");
        verify(client).precreate(any());
        verify(sleeper).pauseBeforeQrVerification();
        verify(client, times(2)).query(order.getOrderNo());
    }

    @Test
    void mismatchedPrecreateOrderNeverStoresOrVerifiesForeignQr() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        when(client.precreate(any())).thenReturn(new AlipayPrecreateResult(
                "PAY-FOREIGN", "https://qr.alipay.com/foreign"));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client);

        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        assertThat(orders).hasSize(1);
        assertThat(orders.get(response.orderNo()).getQrcode()).isNull();
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.paymentMessage()).isEqualTo("支付状态暂未确认，请稍后查询，请勿重复支付");
        verify(client, never()).query(any());
    }

    @Test
    void qrPrecreateRetriesSamePersistedOrderNumberAfterTransientFailure() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper paymentRetrySleeper = mock(PaymentRetrySleeper.class);
        AtomicReference<PaymentOrder> persisted = stubOrderPersistence(paymentOrderMapper);
        when(alipayPaymentClient.precreate(any()))
                .thenThrow(new RetryablePaymentGatewayException("temporary", new RuntimeException("504")))
                .thenAnswer(invocation -> precreateResult(
                        invocation,
                        "https://qr.alipay.com/example"
                ));
        when(alipayPaymentClient.query(any())).thenAnswer(invocation ->
                waitingResult(invocation.getArgument(0)));
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
    void interruptedQrRetryReturnsRecoverableCreatedOrderWithoutClosing() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper paymentRetrySleeper = mock(PaymentRetrySleeper.class);
        AtomicReference<PaymentOrder> persisted = stubOrderPersistence(paymentOrderMapper);
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
        PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

        assertThat(response.orderNo()).isEqualTo(persisted.get().getOrderNo());
        assertThat(response.status()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.paymentHtml()).isNull();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentMessage())
                .isEqualTo("支付宝扫码支付服务暂时不可用，请稍后重试或改用支付宝收银台");
        verify(alipayPaymentClient).precreate(any());
        verify(paymentRetrySleeper).pauseBeforeAttempt(2);
        verify(paymentOrderMapper).selectOne(any());
        verify(paymentOrderMapper, never()).update(any(), any());
    }

    @Test
    void qrSuccessConditionallyStoresQrForCreatedOrder() {
        PaymentOrderMapper paymentOrderMapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient alipayPaymentClient = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper paymentRetrySleeper = mock(PaymentRetrySleeper.class);
        stubOrderPersistence(paymentOrderMapper);
        when(alipayPaymentClient.precreate(any()))
                .thenAnswer(invocation -> precreateResult(
                        invocation,
                        "https://qr.alipay.com/example"
                ));
        when(alipayPaymentClient.query(any())).thenAnswer(invocation ->
                waitingResult(invocation.getArgument(0)));
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
        assertThat(update.getExpiresAt()).isNotNull();
        assertThat(update.getStatus()).isNull();
        assertThat(update.getOrderNo()).isNull();
        assertCreatedGuard(wrapperCaptor.getValue(), insertedCaptor.getValue().getOrderNo());
        assertThat(response.paymentReady()).isTrue();
        assertThat(response.paymentMessage()).isNull();
        verify(paymentRetrySleeper).pauseBeforeQrVerification();
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
                .thenAnswer(invocation -> precreateResult(
                        invocation,
                        "https://qr.alipay.com/not-persisted"
                ));
        when(alipayPaymentClient.query(any())).thenAnswer(invocation ->
                waitingResult(invocation.getArgument(0)));
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
        when(alipayPaymentClient.precreate(any()))
                .thenAnswer(invocation -> precreateResult(invocation, qrCode));
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
                .thenAnswer(invocation -> precreateResult(
                        invocation,
                        "https://qr.alipay.com/example"
                ));
        when(alipayPaymentClient.query(any())).thenAnswer(invocation ->
                waitingResult(invocation.getArgument(0)));
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
        assertThat(PaymentOrderServiceImpl.class.getMethod(
                "syncOrder", Long.class, String.class
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
        when(paymentOrderMapper.selectOne(any())).thenReturn(expired);
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
        when(paymentOrderMapper.selectOne(any())).thenReturn(expired);
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

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"unknown"})
    void scheduledExpiryKeepsCreatedWhenCancelActionIsNotDefinitive(String action) {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        CreditAccountService credits = mock(CreditAccountService.class);
        MembershipService memberships = mock(MembershipService.class);
        PaymentOrder expired = resumableQrOrder("PAY-EXPIRY-CANCEL-ACTION");
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectList(any())).thenReturn(List.of(expired));
        when(mapper.selectOne(any())).thenReturn(expired);
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            PaymentOrder update = invocation.getArgument(0);
            expired.setStatus(update.getStatus());
            return 1;
        });
        when(client.query(expired.getOrderNo())).thenReturn(new AlipayQueryResult(
                expired.getOrderNo(), null, "WAIT_BUYER_PAY", null
        ));
        when(client.cancel(expired.getOrderNo())).thenReturn(new AlipayCancelResult(true, false, action));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                credits,
                memberships,
                client
        );

        service.processExpiredQrOrders();

        assertThat(expired.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        verify(client).cancel(expired.getOrderNo());
        verify(mapper, never()).update(any(), any());
        verifyNoInteractions(credits, memberships);
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

    @Test
    void pendingOrdersRejectsAnonymousUser() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                mock(AlipayPaymentClient.class)
        );

        assertThatThrownBy(() -> service.pendingOrders(null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请先登录");
        verifyNoInteractions(mapper);
    }

    @Test
    void pendingOrdersFiltersCurrentNonExpiredCreatedOrdersAndMapsQrReadiness() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        PaymentOrder ready = pendingRechargeOrder("PAY-PENDING-READY");
        ready.setProductName("积分充值¥50");
        ready.setPaymentMethod(PaymentMethod.QR.name());
        ready.setQrcode("https://qr.alipay.com/ready");
        ready.setExpiresAt(LocalDateTime.now().plusHours(1));
        ready.setCreatedAt(LocalDateTime.now());
        PaymentOrder recoverable = pendingRechargeOrder("PAY-PENDING-RECOVERABLE");
        recoverable.setProductName("积分充值¥50");
        recoverable.setPaymentMethod(PaymentMethod.QR.name());
        recoverable.setExpiresAt(LocalDateTime.now().plusHours(1));
        recoverable.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        when(mapper.selectList(any())).thenReturn(List.of(ready, recoverable));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                mock(AlipayPaymentClient.class)
        );

        List<PendingPaymentOrderResponse> result = service.pendingOrders(7L);

        assertThat(result).extracting(PendingPaymentOrderResponse::paymentReady)
                .containsExactly(true, false);
        assertThat(result).allSatisfy(item -> {
            assertThat(item.orderNo()).startsWith("PAY-");
            assertThat(item.amountCents()).isEqualTo(5000);
            assertThat(item.paymentMethod()).isEqualTo(PaymentMethod.QR.name());
            assertThat(item.status()).isEqualTo(PaymentOrderStatus.CREATED.name());
        });
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<PaymentOrder>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(wrapperCaptor.capture());
        LambdaQueryWrapper<PaymentOrder> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment())
                .contains("user_id", "status", "expires_at", "created_at", "DESC", "LIMIT 20");
        assertThat(wrapper.getParamNameValuePairs())
                .containsValue(7L)
                .containsValue(PaymentOrderStatus.CREATED.name());
    }

    @Test
    void resumeStoredQrQueriesAuthoritativelyAndReturnsSameOrder() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-STORED");
        order.setQrcode("https://qr.alipay.com/stored");
        when(mapper.selectOne(any())).thenReturn(order);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, "WAIT_BUYER_PAY", null
        ));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());

        assertThat(response.orderNo()).isEqualTo(order.getOrderNo());
        assertThat(response.qrCode()).isEqualTo("https://qr.alipay.com/stored");
        assertThat(response.paymentReady()).isTrue();
        verify(client, never()).precreate(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumeStoredQrNotFoundClearsOldQrAndReturnsOnlyNewVerifiedQr() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-STORED-MISSING");
        order.setQrcode("https://qr.alipay.com/old-invalid");
        orders.put(order.getOrderNo(), order);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper sleeper = mock(PaymentRetrySleeper.class);
        when(client.query(order.getOrderNo())).thenReturn(
                notFoundResult(order.getOrderNo()),
                waitingResult(order.getOrderNo()));
        when(client.precreate(any())).thenAnswer(invocation -> precreateResult(
                invocation, "https://qr.alipay.com/new-verified"));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client, sleeper);

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());

        assertThat(response.orderNo()).isEqualTo(order.getOrderNo());
        assertThat(response.paymentReady()).isTrue();
        assertThat(response.qrCode()).isEqualTo("https://qr.alipay.com/new-verified");
        assertThat(response.qrCode()).isNotEqualTo("https://qr.alipay.com/old-invalid");
        assertThat(orders.get(order.getOrderNo()).getQrcode()).isEqualTo(response.qrCode());
        verify(client).precreate(any());
        verify(sleeper).pauseBeforeQrVerification();
        verify(client, times(2)).query(order.getOrderNo());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaUpdateWrapper> wrappers = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(any(), wrappers.capture());
        assertThat(wrappers.getAllValues().get(0).getSqlSet()).contains("qrcode");
        assertCreatedGuard(wrappers.getAllValues().get(0), order.getOrderNo());
    }

    @Test
    void resumeStoredQrNotFoundThenUnknownKeepsCreatedOrderWithoutAnyQr() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-STORED-UNKNOWN");
        order.setQrcode("https://qr.alipay.com/old-invalid");
        orders.put(order.getOrderNo(), order);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper sleeper = mock(PaymentRetrySleeper.class);
        when(client.query(order.getOrderNo())).thenReturn(
                notFoundResult(order.getOrderNo()),
                new AlipayQueryResult(
                        order.getOrderNo(), null, null, null, "40004", "ACQ.SYSTEM_ERROR"));
        when(client.precreate(any())).thenAnswer(invocation -> precreateResult(
                invocation, "https://qr.alipay.com/new-unverified"));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client, sleeper);

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());

        assertThat(orders).hasSize(1);
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(order.getQrcode()).isNull();
        assertThat(response.status()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentMessage()).isEqualTo("支付状态暂未确认，请稍后查询，请勿重复支付");
        verify(client).precreate(any());
        verify(sleeper).pauseBeforeQrVerification();
        verify(client, times(2)).query(order.getOrderNo());
        verify(mapper).update(any(), any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumeStoredQrExplicitUnknownInvalidatesQrAndPendingReadiness() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-STORED-INITIAL-UNKNOWN");
        order.setQrcode("https://qr.alipay.com/old-unverified");
        orders.put(order.getOrderNo(), order);
        when(mapper.selectList(any())).thenAnswer(invocation -> List.copyOf(orders.values()));
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, null, null, "40004", "ACQ.SYSTEM_ERROR"));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client);

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());
        List<PendingPaymentOrderResponse> pending = service.pendingOrders(7L);

        assertThat(orders).hasSize(1);
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(order.getQrcode()).isNull();
        assertThat(response.status()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentMessage()).isEqualTo("支付状态暂未确认，请稍后查询，请勿重复支付");
        assertThat(pending).singleElement().extracting(PendingPaymentOrderResponse::paymentReady).isEqualTo(false);
        verify(client, never()).precreate(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumeStoredQrQueryExceptionInvalidatesQrAndPendingReadiness() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-STORED-QUERY-FAILURE");
        order.setQrcode("https://qr.alipay.com/old-unverified");
        orders.put(order.getOrderNo(), order);
        when(mapper.selectList(any())).thenAnswer(invocation -> List.copyOf(orders.values()));
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        when(client.query(order.getOrderNo())).thenThrow(new BusinessException("gateway detail"));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client);

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());
        List<PendingPaymentOrderResponse> pending = service.pendingOrders(7L);

        assertThat(orders).hasSize(1);
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(order.getQrcode()).isNull();
        assertThat(response.status()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentMessage()).isEqualTo("支付状态暂未确认，请稍后查询，请勿重复支付");
        assertThat(pending).singleElement().extracting(PendingPaymentOrderResponse::paymentReady).isEqualTo(false);
        verify(client, never()).precreate(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumeStoredQrPlainRuntimeFailureIsSafelyNormalizedAndInvalidated() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-STORED-RUNTIME");
        order.setQrcode("https://qr.alipay.com/old-unverified");
        orders.put(order.getOrderNo(), order);
        when(mapper.selectList(any())).thenAnswer(invocation -> List.copyOf(orders.values()));
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        when(client.query(order.getOrderNo())).thenThrow(new IllegalStateException("raw gateway payload"));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client);

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());
        List<PendingPaymentOrderResponse> pending = service.pendingOrders(7L);

        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(order.getQrcode()).isNull();
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentMessage()).isEqualTo("支付状态暂未确认，请稍后查询，请勿重复支付");
        assertThat(response.paymentMessage()).doesNotContain("raw gateway payload");
        assertThat(pending).singleElement().extracting(PendingPaymentOrderResponse::paymentReady).isEqualTo(false);
        verify(client, never()).precreate(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumeStoredQrConfigurationMismatchInvalidatesQrButPreservesSafeMessage() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-STORED-CONFIG");
        order.setQrcode("https://qr.alipay.com/old-unverified");
        order.setAppId("legacy-app");
        orders.put(order.getOrderNo(), order);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client);

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());

        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(order.getQrcode()).isNull();
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentMessage()).isEqualTo("订单支付配置已变更，请先查询原订单状态，请勿重复支付");
        verifyNoInteractions(client);
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumeExpiredStoredQrExplicitUnknownInvalidatesWithoutCancelOrReplacement() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-EXPIRED-STORED-UNKNOWN");
        order.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        order.setQrcode("https://qr.alipay.com/expired-unverified");
        orders.put(order.getOrderNo(), order);
        when(mapper.selectList(any())).thenAnswer(invocation -> List.copyOf(orders.values()));
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, null, null, "40004", "ACQ.SYSTEM_ERROR"));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client);

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());
        List<PendingPaymentOrderResponse> pending = service.pendingOrders(7L);

        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(order.getQrcode()).isNull();
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentMessage()).isEqualTo("支付状态暂未确认，请稍后查询，请勿重复支付");
        assertThat(pending).singleElement().extracting(PendingPaymentOrderResponse::paymentReady).isEqualTo(false);
        verify(client, never()).cancel(any());
        verify(client, never()).precreate(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumeExpiredStoredQrRuntimeFailureIsSafelyNormalizedAndInvalidated() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        Map<String, PaymentOrder> orders = stubOrderStore(mapper);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-EXPIRED-STORED-RUNTIME");
        order.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        order.setQrcode("https://qr.alipay.com/expired-unverified");
        orders.put(order.getOrderNo(), order);
        when(mapper.selectList(any())).thenAnswer(invocation -> List.copyOf(orders.values()));
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        when(client.query(order.getOrderNo())).thenThrow(new IllegalArgumentException("raw sdk response"));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client);

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());
        List<PendingPaymentOrderResponse> pending = service.pendingOrders(7L);

        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(order.getQrcode()).isNull();
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentMessage()).isEqualTo("支付状态暂未确认，请稍后查询，请勿重复支付");
        assertThat(response.paymentMessage()).doesNotContain("raw sdk response");
        assertThat(pending).singleElement().extracting(PendingPaymentOrderResponse::paymentReady).isEqualTo(false);
        verify(client, never()).cancel(any());
        verify(client, never()).precreate(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumeStoredQrUnknownInvalidationLosingToPaidCallbackReturnsTerminalWithoutQr() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-STORED-UNKNOWN-PAID-RACE");
        order.setQrcode("https://qr.alipay.com/old-unverified");
        AtomicReference<PaymentOrder> persisted = new AtomicReference<>(order);
        when(mapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            PaymentOrder paid = paidCopy(order);
            paid.setPaymentMethod(PaymentMethod.QR.name());
            paid.setQrcode(order.getQrcode());
            persisted.set(paid);
            return 0;
        });
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, null, null, "40004", "ACQ.SYSTEM_ERROR"));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client);

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());

        assertThat(response.status()).isEqualTo(PaymentOrderStatus.PAID.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        verify(client, never()).precreate(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumeStoredQrNotFoundClearLosingToPaidCallbackReturnsPaidWithoutReprecreate() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-STORED-PAID-RACE");
        order.setQrcode("https://qr.alipay.com/old-invalid");
        AtomicReference<PaymentOrder> persisted = new AtomicReference<>(order);
        when(mapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            PaymentOrder paid = paidCopy(order);
            paid.setPaymentMethod(PaymentMethod.QR.name());
            paid.setQrcode(order.getQrcode());
            persisted.set(paid);
            return 0;
        });
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        when(client.query(order.getOrderNo())).thenReturn(notFoundResult(order.getOrderNo()));
        PaymentOrderServiceImpl service = service(
                mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
                mock(MembershipService.class), client);

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());

        assertThat(response.status()).isEqualTo(PaymentOrderStatus.PAID.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        verify(mapper).update(any(), any());
        verify(client, never()).precreate(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumeStoredQrStripsPayloadWhenCallbackWinsBeforeResponse() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-STORED-RACE");
        order.setQrcode("https://qr.alipay.com/stored-race");
        PaymentOrder paid = paidCopy(order);
        paid.setPaymentMethod(PaymentMethod.QR.name());
        paid.setQrcode(order.getQrcode());
        when(mapper.selectOne(any())).thenReturn(order, paid);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, "WAIT_BUYER_PAY", null
        ));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());

        assertThat(response.status()).isEqualTo(PaymentOrderStatus.PAID.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentHtml()).isNull();
        verify(mapper, times(2)).selectOne(any());
    }

    @Test
    void resumeQrWithoutMaterialPrecreatesTheSameOrderNumber() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-SAME");
        LocalDateTime previousExpiresAt = order.getExpiresAt();
        AtomicReference<PaymentOrder> persisted = new AtomicReference<>(order);
        AtomicReference<PaymentOrder> qrUpdate = new AtomicReference<>();
        when(mapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            PaymentOrder update = invocation.getArgument(0);
            if (update.getQrcode() != null) {
                qrUpdate.set(update);
                persisted.get().setQrcode(update.getQrcode());
            }
            if (update.getExpiresAt() != null) {
                persisted.get().setExpiresAt(update.getExpiresAt());
            }
            return 1;
        });
        when(client.query(order.getOrderNo())).thenReturn(
                notFoundResult(order.getOrderNo()),
                waitingResult(order.getOrderNo()));
        when(client.precreate(any())).thenAnswer(invocation -> precreateResult(
                invocation,
                "https://qr.alipay.com/resume"
        ));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());

        ArgumentCaptor<AlipayPrecreateRequest> requestCaptor =
                ArgumentCaptor.forClass(AlipayPrecreateRequest.class);
        verify(client).precreate(requestCaptor.capture());
        assertThat(requestCaptor.getValue().orderNo()).isEqualTo(order.getOrderNo());
        assertThat(response.orderNo()).isEqualTo(order.getOrderNo());
        assertThat(response.qrCode()).isEqualTo("https://qr.alipay.com/resume");
        assertThat(response.paymentReady()).isTrue();
        assertThat(qrUpdate.get().getExpiresAt()).isAfter(previousExpiresAt);
        assertThat(response.expiresAt()).isEqualTo(qrUpdate.get().getExpiresAt());
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumeQrRetryExhaustionKeepsSameRecoverableOrder() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-RETRY");
        when(mapper.selectOne(any())).thenReturn(order);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, null, null, "40004", "ACQ.TRADE_NOT_EXIST"
        ));
        when(client.precreate(any())).thenThrow(
                new RetryablePaymentGatewayException("temporary", new RuntimeException("504")));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());

        assertThat(response.orderNo()).isEqualTo(order.getOrderNo());
        assertThat(response.status()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        verify(client, times(3)).precreate(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumeRejectsWrongOwnerBeforeCallingAlipay() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-OWNER");
        when(mapper.selectOne(any())).thenReturn(order);
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        assertThatThrownBy(() -> service.resumeOrder(8L, order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单不存在");
        verifyNoInteractions(client);
    }

    @Test
    void resumeUnknownGatewayStateKeepsOrderAndRejectsDuplicatePayment() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-UNKNOWN");
        when(mapper.selectOne(any())).thenReturn(order);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, null, null, "40004", "ACQ.SYSTEM_ERROR"
        ));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        assertThatThrownBy(() -> service.resumeOrder(7L, order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("支付状态暂未确认，请稍后查询，请勿重复支付");
        verify(client, never()).precreate(any());
        verify(mapper, never()).insert(any());
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void resumePageRegeneratesFormOnlyFromStoredOrderSnapshot() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-PAGE");
        order.setPaymentMethod(PaymentMethod.PAGE.name());
        when(mapper.selectOne(any())).thenReturn(order);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, null, null, "40004", "ACQ.TRADE_NOT_EXIST"
        ));
        when(client.pagePay(any())).thenReturn(new AlipayPagePayResult("<form>resume</form>"));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());

        ArgumentCaptor<AlipayPagePayRequest> requestCaptor =
                ArgumentCaptor.forClass(AlipayPagePayRequest.class);
        verify(client).pagePay(requestCaptor.capture());
        assertThat(requestCaptor.getValue().orderNo()).isEqualTo(order.getOrderNo());
        assertThat(requestCaptor.getValue().subject()).isEqualTo(order.getProductName());
        assertThat(requestCaptor.getValue().totalAmount()).isEqualByComparingTo("50.00");
        assertThat(response.paymentReady()).isTrue();
        assertThat(response.paymentHtml()).isEqualTo("<form>resume</form>");
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumePaidLocalOrderReturnsNoPaymentPayloadWithoutQuery() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder paid = paidCopy(resumableQrOrder("PAY-RESUME-PAID"));
        paid.setPaymentMethod(PaymentMethod.QR.name());
        paid.setQrcode("https://qr.alipay.com/paid");
        when(mapper.selectOne(any())).thenReturn(paid);
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        PaymentOrderCreateResponse response = service.resumeOrder(7L, paid.getOrderNo());

        assertThat(response.status()).isEqualTo(PaymentOrderStatus.PAID.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentHtml()).isNull();
        verifyNoInteractions(client);
    }

    @Test
    void resumeExpiredQrNotFoundClosesAndNeverReturnsOldQr() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-EXPIRED");
        order.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        order.setQrcode("https://qr.alipay.com/expired");
        when(mapper.selectOne(any())).thenReturn(order);
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            order.setStatus(PaymentOrderStatus.CLOSED.name());
            return 1;
        });
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, null, null, "40004", "ACQ.TRADE_NOT_EXIST"
        ));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());

        assertThat(response.status()).isEqualTo(PaymentOrderStatus.CLOSED.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        verify(client, never()).cancel(any());
        verify(client, never()).precreate(any());
    }

    @Test
    void refreshQrCancelsWaitingTradeAndCreatesReplacementFromServerSnapshot() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder source = resumableQrOrder("PAY-REFRESH-SOURCE");
        source.setProductCode("month_standard");
        source.setProductName("包月标准会员");
        source.setOrderType(PaymentOrderType.MEMBERSHIP.name());
        source.setAmountCents(19900);
        source.setBaseCredits(0);
        source.setBonusCredits(2210);
        source.setTotalCredits(2210);
        source.setMembershipTier("STANDARD");
        source.setMembershipPeriod("MONTH");
        source.setMembershipMonths(1);
        AtomicReference<PaymentOrder> selected = new AtomicReference<>(source);
        AtomicReference<PaymentOrder> replacementRef = new AtomicReference<>();
        when(mapper.selectOne(any())).thenAnswer(invocation -> selected.get());
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            PaymentOrder update = invocation.getArgument(0);
            PaymentOrder current = selected.get();
            if (update.getStatus() != null) {
                current.setStatus(update.getStatus());
            }
            if (update.getQrcode() != null) {
                current.setQrcode(update.getQrcode());
            }
            current.setUpdatedAt(update.getUpdatedAt());
            return 1;
        });
        when(mapper.insert(any())).thenAnswer(invocation -> {
            PaymentOrder replacement = invocation.getArgument(0);
            replacementRef.set(replacement);
            selected.set(replacement);
            return 1;
        });
        when(client.query(source.getOrderNo())).thenReturn(new AlipayQueryResult(
                source.getOrderNo(), null, "WAIT_BUYER_PAY", null));
        when(client.cancel(source.getOrderNo())).thenReturn(
                new AlipayCancelResult(true, false, "close"));
        when(client.precreate(any())).thenAnswer(invocation -> precreateResult(
                invocation,
                "https://qr.alipay.com/refreshed"
        ));
        when(client.query(any())).thenAnswer(invocation ->
                waitingResult(invocation.getArgument(0)));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        PaymentOrderCreateResponse response = service.refreshQrOrder(7L, source.getOrderNo());

        PaymentOrder replacement = replacementRef.get();
        assertThat(source.getStatus()).isEqualTo(PaymentOrderStatus.CLOSED.name());
        assertThat(replacement).isNotNull();
        assertThat(replacement.getOrderNo()).isNotEqualTo(source.getOrderNo());
        assertThat(replacement.getPaymentMethod()).isEqualTo(PaymentMethod.QR.name());
        assertThat(replacement.getProductCode()).isEqualTo("month_standard");
        assertThat(replacement.getProductName()).isEqualTo("包月标准会员");
        assertThat(replacement.getAmountCents()).isEqualTo(19900);
        assertThat(replacement.getBaseCredits()).isZero();
        assertThat(replacement.getBonusCredits()).isEqualTo(2210);
        assertThat(replacement.getTotalCredits()).isEqualTo(2210);
        assertThat(replacement.getMembershipTier()).isEqualTo("STANDARD");
        assertThat(replacement.getMembershipPeriod()).isEqualTo("MONTH");
        assertThat(replacement.getMembershipMonths()).isEqualTo(1);
        assertThat(replacement.getAppId()).isEqualTo(source.getAppId());
        assertThat(replacement.getSellerId()).isEqualTo(source.getSellerId());
        assertThat(replacement.getExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(119));
        assertThat(response.orderNo()).isEqualTo(replacement.getOrderNo());
        assertThat(response.qrCode()).isEqualTo("https://qr.alipay.com/refreshed");
        assertThat(response.paymentReady()).isTrue();
        verify(client).cancel(source.getOrderNo());
        verify(mapper).insert(replacement);
    }

    @Test
    void refreshQrRejectsOrderFromDifferentAlipayMerchantBeforeGatewayLookup() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder source = resumableQrOrder("PAY-REFRESH-OLD-MERCHANT");
        source.setAppId("legacy-app");
        source.setSellerId("legacy-seller");
        when(mapper.selectOne(any())).thenReturn(source);
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        assertThatThrownBy(() -> service.refreshQrOrder(7L, source.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单支付配置已变更，请先查询原订单状态，请勿重复支付");
        verifyNoInteractions(client);
        verify(mapper, never()).update(any(), any());
        verify(mapper, never()).insert(any());
        assertThat(source.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
    }

    @Test
    void refreshQrSettlesPaidTradeWithoutCreatingReplacement() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        CreditAccountService credits = mock(CreditAccountService.class);
        PaymentOrder order = resumableQrOrder("PAY-REFRESH-PAID");
        when(mapper.selectOne(any())).thenReturn(order);
        when(mapper.update(any(), any())).thenReturn(1);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), "trade-paid", "TRADE_SUCCESS", new BigDecimal("50.00")));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                credits,
                mock(MembershipService.class),
                client
        );

        PaymentOrderCreateResponse response = service.refreshQrOrder(7L, order.getOrderNo());

        assertThat(response.status()).isEqualTo(PaymentOrderStatus.PAID.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.qrCode()).isNull();
        verify(credits).grantRechargeCredits(order);
        verify(client, never()).cancel(any());
        verify(client, never()).precreate(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void refreshQrRejectsUnknownTradeStateWithoutCancellingOrReplacing() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-REFRESH-UNKNOWN");
        when(mapper.selectOne(any())).thenReturn(order);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, null, null, "40004", "ACQ.SYSTEM_ERROR"));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        assertThatThrownBy(() -> service.refreshQrOrder(7L, order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("支付状态暂未确认，请稍后查询，请勿重复支付");
        verify(client, never()).cancel(any());
        verify(client, never()).precreate(any());
        verify(mapper, never()).update(any(), any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void refreshQrRejectsRetryRequiredCancelAndKeepsSourceCreated() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-REFRESH-CANCEL-RETRY");
        when(mapper.selectOne(any())).thenReturn(order);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, "WAIT_BUYER_PAY", null));
        when(client.cancel(order.getOrderNo())).thenReturn(new AlipayCancelResult(true, true, "close"));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        assertThatThrownBy(() -> service.refreshQrOrder(7L, order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("支付状态暂未确认，请稍后查询，请勿重复支付");
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        verify(mapper, never()).update(any(), any());
        verify(mapper, never()).insert(any());
        verify(client, never()).precreate(any());
    }

    @Test
    void refreshQrRejectsOrderOwnedByAnotherUserBeforeCallingAlipay() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-REFRESH-OWNER");
        when(mapper.selectOne(any())).thenReturn(order);
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        assertThatThrownBy(() -> service.refreshQrOrder(8L, order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单不存在");
        verifyNoInteractions(client);
        verify(mapper, never()).insert(any());
    }

    @Test
    void refreshQrRejectsPageOrderBeforeCallingAlipay() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-REFRESH-PAGE");
        order.setPaymentMethod(PaymentMethod.PAGE.name());
        when(mapper.selectOne(any())).thenReturn(order);
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        assertThatThrownBy(() -> service.refreshQrOrder(7L, order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("仅扫码支付订单可刷新二维码");
        verifyNoInteractions(client);
        verify(mapper, never()).insert(any());
    }

    @Test
    void refreshQrRejectsSecondCallAfterSourceWasClosed() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-REFRESH-CLOSED");
        order.setStatus(PaymentOrderStatus.CLOSED.name());
        when(mapper.selectOne(any())).thenReturn(order);
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        assertThatThrownBy(() -> service.refreshQrOrder(7L, order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该订单当前状态不支持刷新二维码");
        verifyNoInteractions(client);
        verify(mapper, never()).insert(any());
    }

    @Test
    void switchToPageSettlesPaidQrInsteadOfCreatingAnotherOrder() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        CreditAccountService credits = mock(CreditAccountService.class);
        PaymentOrder order = resumableQrOrder("PAY-SWITCH-PAID");
        when(mapper.selectOne(any())).thenReturn(order);
        when(mapper.update(any(), any())).thenReturn(1);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), "trade-paid", "TRADE_SUCCESS", new BigDecimal("50.00")
        ));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                credits,
                mock(MembershipService.class),
                client
        );

        PaymentOrderCreateResponse response = service.switchToPage(7L, order.getOrderNo());

        assertThat(response.status()).isEqualTo(PaymentOrderStatus.PAID.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.paymentHtml()).isNull();
        assertThat(response.qrCode()).isNull();
        verify(credits).grantRechargeCredits(order);
        verify(client, never()).pagePay(any());
        verify(client, never()).cancel(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void switchClosedQrCreatesOnePageOrderFromStoredSnapshot() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-SWITCH-CLOSED");
        order.setProductCode("recharge_5000");
        order.setProductName("积分充值¥50");
        AtomicReference<PaymentOrder> persisted = new AtomicReference<>(order);
        when(mapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            PaymentOrder update = invocation.getArgument(0);
            if (update.getStatus() != null) {
                persisted.get().setStatus(update.getStatus());
            }
            return 1;
        });
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, "TRADE_CLOSED", null
        ));
        when(client.pagePay(any())).thenReturn(new AlipayPagePayResult("<form></form>"));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        PaymentOrderCreateResponse response = service.switchToPage(7L, order.getOrderNo());

        ArgumentCaptor<AlipayPagePayRequest> pageCaptor =
                ArgumentCaptor.forClass(AlipayPagePayRequest.class);
        ArgumentCaptor<PaymentOrder> insertCaptor = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(client).pagePay(pageCaptor.capture());
        verify(mapper, times(1)).insert(insertCaptor.capture());
        PaymentOrder pageOrder = insertCaptor.getValue();
        assertThat(pageOrder.getOrderNo()).isNotEqualTo(order.getOrderNo());
        assertThat(pageOrder.getPaymentMethod()).isEqualTo(PaymentMethod.PAGE.name());
        assertThat(pageOrder.getProductCode()).isEqualTo(order.getProductCode());
        assertThat(pageOrder.getProductName()).isEqualTo(order.getProductName());
        assertThat(pageOrder.getAmountCents()).isEqualTo(order.getAmountCents());
        assertThat(pageOrder.getBaseCredits()).isEqualTo(order.getBaseCredits());
        assertThat(pageOrder.getBonusCredits()).isEqualTo(order.getBonusCredits());
        assertThat(pageOrder.getTotalCredits()).isEqualTo(order.getTotalCredits());
        assertThat(pageCaptor.getValue().orderNo()).isEqualTo(pageOrder.getOrderNo());
        assertThat(pageCaptor.getValue().subject()).isEqualTo(order.getProductName());
        assertThat(pageCaptor.getValue().totalAmount()).isEqualByComparingTo("50.00");
        assertThat(response.paymentMethod()).isEqualTo(PaymentMethod.PAGE.name());
        assertThat(response.paymentHtml()).isEqualTo("<form></form>");
        assertThat(response.paymentReady()).isTrue();
    }

    @Test
    void switchWaitingQrRequiresDefinitiveCancelBeforeCreatingPageOrder() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-SWITCH-WAITING");
        AtomicReference<PaymentOrder> persisted = new AtomicReference<>(order);
        when(mapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            persisted.get().setStatus(PaymentOrderStatus.CLOSED.name());
            return 1;
        });
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, "WAIT_BUYER_PAY", null
        ));
        when(client.cancel(order.getOrderNo())).thenReturn(new AlipayCancelResult(true, false, "close"));
        when(client.pagePay(any())).thenReturn(new AlipayPagePayResult("<form></form>"));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        PaymentOrderCreateResponse response = service.switchToPage(7L, order.getOrderNo());

        verify(client).cancel(order.getOrderNo());
        verify(client).pagePay(any());
        verify(mapper).insert(any());
        assertThat(response.paymentMethod()).isEqualTo(PaymentMethod.PAGE.name());
    }

    @Test
    void switchWaitingQrRejectsRetryRequiredCancelAndKeepsOriginalCreated() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-SWITCH-RETRY");
        when(mapper.selectOne(any())).thenReturn(order);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, "WAIT_BUYER_PAY", null
        ));
        when(client.cancel(order.getOrderNo())).thenReturn(new AlipayCancelResult(true, true, "close"));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        assertThatThrownBy(() -> service.switchToPage(7L, order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("支付状态暂未确认，请稍后查询，请勿重复支付");
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        verify(client, never()).pagePay(any());
        verify(mapper, never()).insert(any());
        verify(mapper, never()).update(any(), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"unknown"})
    void switchWaitingQrRejectsCancelWithoutDefinitiveAction(String action) {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        CreditAccountService credits = mock(CreditAccountService.class);
        MembershipService memberships = mock(MembershipService.class);
        PaymentOrder order = resumableQrOrder("PAY-SWITCH-CANCEL-ACTION");
        when(mapper.selectOne(any())).thenReturn(order);
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            PaymentOrder update = invocation.getArgument(0);
            order.setStatus(update.getStatus());
            return 1;
        });
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, "WAIT_BUYER_PAY", null
        ));
        when(client.cancel(order.getOrderNo())).thenReturn(new AlipayCancelResult(true, false, action));
        when(client.pagePay(any())).thenReturn(new AlipayPagePayResult("<form></form>"));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                credits,
                memberships,
                client
        );

        assertThatThrownBy(() -> service.switchToPage(7L, order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("支付状态暂未确认，请稍后查询，请勿重复支付");

        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        verify(mapper, never()).update(any(), any());
        verify(mapper, never()).insert(any());
        verify(client, never()).pagePay(any());
        verifyNoInteractions(credits, memberships);
    }

    @Test
    void switchCloseLosingToPaidCallbackReturnsPaidWithoutCreatingPageOrder() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-SWITCH-RACE");
        PaymentOrder paid = paidCopy(order);
        when(mapper.selectOne(any())).thenReturn(order, paid);
        when(mapper.update(any(), any())).thenReturn(0);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, "TRADE_CLOSED", null
        ));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        PaymentOrderCreateResponse response = service.switchToPage(7L, order.getOrderNo());

        assertThat(response.status()).isEqualTo(PaymentOrderStatus.PAID.name());
        assertThat(response.paymentHtml()).isNull();
        assertThat(response.qrCode()).isNull();
        verify(client, never()).pagePay(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void switchCloseAlreadyWonElsewhereDoesNotCreateDuplicatePageOrder() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-SWITCH-DUPLICATE");
        PaymentOrder closed = resumableQrOrder(order.getOrderNo());
        closed.setStatus(PaymentOrderStatus.CLOSED.name());
        when(mapper.selectOne(any())).thenReturn(order, closed);
        when(mapper.update(any(), any())).thenReturn(0);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, "TRADE_CLOSED", null
        ));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        assertThatThrownBy(() -> service.switchToPage(7L, order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("支付状态暂未确认，请稍后查询，请勿重复支付");
        verify(client, never()).pagePay(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void switchRejectsPageSourceWithoutCallingAlipay() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-SWITCH-PAGE");
        order.setPaymentMethod(PaymentMethod.PAGE.name());
        when(mapper.selectOne(any())).thenReturn(order);
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        assertThatThrownBy(() -> service.switchToPage(7L, order.getOrderNo()))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(client);
        verify(mapper, never()).insert(any());
    }

    @Test
    void expiredQrNotFoundClosesLocallyWithoutCancel() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder expired = resumableQrOrder("PAY-EXPIRED-NOT-FOUND");
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectList(any())).thenReturn(List.of(expired));
        when(mapper.selectOne(any())).thenReturn(expired);
        when(client.query(expired.getOrderNo())).thenReturn(new AlipayQueryResult(
                expired.getOrderNo(), null, null, null, "40004", "ACQ.TRADE_NOT_EXIST"
        ));
        when(mapper.update(any(), any())).thenReturn(1);
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        service.processExpiredQrOrders();

        verify(mapper).update(any(), any());
        verify(client, never()).cancel(any());
    }

    @Test
    void expiredQrUnknownQueryRemainsRetryableWithoutCancelOrBenefits() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        CreditAccountService credits = mock(CreditAccountService.class);
        MembershipService memberships = mock(MembershipService.class);
        PaymentOrder expired = resumableQrOrder("PAY-EXPIRED-UNKNOWN");
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectList(any())).thenReturn(List.of(expired));
        when(mapper.selectOne(any())).thenReturn(expired);
        when(client.query(expired.getOrderNo())).thenReturn(new AlipayQueryResult(
                expired.getOrderNo(), null, null, null, "40004", "ACQ.SYSTEM_ERROR"
        ));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                credits,
                memberships,
                client
        );

        service.processExpiredQrOrders();

        verify(client, never()).cancel(any());
        verify(mapper, never()).update(any(), any());
        verifyNoInteractions(credits, memberships);
    }

    @Test
    void syncNonExpiredNotFoundKeepsRecoverableOrderCreated() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-SYNC-NOT-FOUND");
        when(mapper.selectOne(any())).thenReturn(order);
        when(client.query(order.getOrderNo())).thenReturn(
                notFoundResult(order.getOrderNo()),
                waitingResult(order.getOrderNo()));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        service.syncOrder(7L, order.getOrderNo());

        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        verify(mapper, never()).update(any(), any());
        verify(client, never()).cancel(any());
        verify(client, never()).precreate(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"unknown"})
    void syncExpiredQrKeepsCreatedWhenCancelActionIsNotDefinitive(String action) {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        CreditAccountService credits = mock(CreditAccountService.class);
        MembershipService memberships = mock(MembershipService.class);
        PaymentOrder expired = resumableQrOrder("PAY-SYNC-CANCEL-ACTION");
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectOne(any())).thenReturn(expired);
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            PaymentOrder update = invocation.getArgument(0);
            expired.setStatus(update.getStatus());
            return 1;
        });
        when(client.query(expired.getOrderNo())).thenReturn(new AlipayQueryResult(
                expired.getOrderNo(), null, "WAIT_BUYER_PAY", null
        ));
        when(client.cancel(expired.getOrderNo())).thenReturn(new AlipayCancelResult(true, false, action));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                credits,
                memberships,
                client
        );

        Object response = service.syncOrder(7L, expired.getOrderNo());

        assertThat(response).extracting("status").isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(expired.getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
        verify(client).cancel(expired.getOrderNo());
        verify(mapper, never()).update(any(), any());
        verifyNoInteractions(credits, memberships);
    }

    @Test
    void resumeClosedLocalOrderReturnsPayloadStrippedWithoutQuery() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-CLOSED");
        order.setStatus(PaymentOrderStatus.CLOSED.name());
        order.setQrcode("https://qr.alipay.com/stale-closed");
        when(mapper.selectOne(any())).thenReturn(order);
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());

        assertThat(response.status()).isEqualTo(PaymentOrderStatus.CLOSED.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.paymentHtml()).isNull();
        assertThat(response.qrCode()).isNull();
        verifyNoInteractions(client);
    }

    @Test
    void resumePrecreateBusinessFailureConditionallyClosesAndRethrows() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-BUSINESS-FAILURE");
        String safeMessage = "当前支付宝应用未开通订单码支付，请改用支付宝收银台";
        when(mapper.selectOne(any())).thenReturn(order);
        when(mapper.update(any(), any())).thenReturn(1);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, null, null, "40004", "ACQ.TRADE_NOT_EXIST"
        ));
        when(client.precreate(any())).thenThrow(new BusinessException(safeMessage));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        assertThatThrownBy(() -> service.resumeOrder(7L, order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessage(safeMessage);

        ArgumentCaptor<PaymentOrder> updateCaptor = ArgumentCaptor.forClass(PaymentOrder.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaUpdateWrapper> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(updateCaptor.capture(), wrapperCaptor.capture());
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo(PaymentOrderStatus.CLOSED.name());
        assertCreatedGuard(wrapperCaptor.getValue(), order.getOrderNo());
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumeInterruptedRetryReloadsPaidWhenCallbackWins() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper sleeper = mock(PaymentRetrySleeper.class);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-INTERRUPTED-RACE");
        PaymentOrder paid = paidCopy(order);
        paid.setPaymentMethod(PaymentMethod.QR.name());
        when(mapper.selectOne(any())).thenReturn(order, paid);
        when(mapper.update(any(), any())).thenReturn(0);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, null, null, "40004", "ACQ.TRADE_NOT_EXIST"
        ));
        when(client.precreate(any())).thenThrow(
                new RetryablePaymentGatewayException("temporary", new RuntimeException("504")));
        doThrow(new BusinessException("支付宝扫码支付请求已中断，请重新尝试"))
                .when(sleeper).pauseBeforeAttempt(2);
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client,
                sleeper
        );

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());

        assertThat(response.status()).isEqualTo(PaymentOrderStatus.PAID.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.paymentHtml()).isNull();
        assertThat(response.qrCode()).isNull();
        verify(mapper, never()).update(any(), any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumeInterruptedRetryKeepsSameRecoverableCreatedOrderWithoutClosing() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentRetrySleeper sleeper = mock(PaymentRetrySleeper.class);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-INTERRUPTED");
        when(mapper.selectOne(any())).thenReturn(order);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, null, null, "40004", "ACQ.TRADE_NOT_EXIST"
        ));
        when(client.precreate(any())).thenThrow(
                new RetryablePaymentGatewayException("temporary", new RuntimeException("504")));
        doThrow(new BusinessException("支付宝扫码支付请求已中断，请重新尝试"))
                .when(sleeper).pauseBeforeAttempt(2);
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client,
                sleeper
        );

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());

        assertThat(response.orderNo()).isEqualTo(order.getOrderNo());
        assertThat(response.status()).isEqualTo(PaymentOrderStatus.CREATED.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.paymentHtml()).isNull();
        assertThat(response.qrCode()).isNull();
        assertThat(response.paymentMessage())
                .isEqualTo("支付宝扫码支付服务暂时不可用，请稍后重试或改用支付宝收银台");
        verify(client).precreate(any());
        verify(sleeper).pauseBeforeAttempt(2);
        verify(mapper, never()).update(any(), any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumeCreatedPaidQuerySettlesOnceAndReloadsPayloadStrippedPaidOrder() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        CreditAccountService credits = mock(CreditAccountService.class);
        MembershipService memberships = mock(MembershipService.class);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-QUERY-PAID");
        AtomicReference<PaymentOrder> persisted = new AtomicReference<>(order);
        when(mapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(mapper.update(any(), any())).thenReturn(1);
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), "trade-resume-paid", "TRADE_SUCCESS", new BigDecimal("50.00")
        ));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                credits,
                memberships,
                client
        );

        PaymentOrderCreateResponse first = service.resumeOrder(7L, order.getOrderNo());
        PaymentOrderCreateResponse second = service.resumeOrder(7L, order.getOrderNo());

        assertThat(first.status()).isEqualTo(PaymentOrderStatus.PAID.name());
        assertThat(first.paymentReady()).isFalse();
        assertThat(first.paymentHtml()).isNull();
        assertThat(first.qrCode()).isNull();
        assertThat(second.status()).isEqualTo(PaymentOrderStatus.PAID.name());
        assertThat(second.paymentHtml()).isNull();
        assertThat(second.qrCode()).isNull();
        verify(client, times(1)).query(order.getOrderNo());
        verify(client, never()).precreate(any());
        verify(client, never()).pagePay(any());
        verify(credits, times(1)).grantRechargeCredits(order);
        verifyNoInteractions(memberships);
        verify(mapper, times(1)).update(any(), any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumeCreatedClosedQueryConditionallyClosesAndStripsPaymentPayload() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        CreditAccountService credits = mock(CreditAccountService.class);
        MembershipService memberships = mock(MembershipService.class);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-QUERY-CLOSED");
        order.setQrcode("https://qr.alipay.com/stale-closed-query");
        when(mapper.selectOne(any())).thenReturn(order);
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            PaymentOrder update = invocation.getArgument(0);
            order.setStatus(update.getStatus());
            return 1;
        });
        when(client.query(order.getOrderNo())).thenReturn(new AlipayQueryResult(
                order.getOrderNo(), null, "TRADE_CLOSED", null
        ));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                credits,
                memberships,
                client
        );

        PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());

        assertThat(response.status()).isEqualTo(PaymentOrderStatus.CLOSED.name());
        assertThat(response.paymentReady()).isFalse();
        assertThat(response.paymentHtml()).isNull();
        assertThat(response.qrCode()).isNull();
        ArgumentCaptor<PaymentOrder> updateCaptor = ArgumentCaptor.forClass(PaymentOrder.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaUpdateWrapper> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(updateCaptor.capture(), wrapperCaptor.capture());
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo(PaymentOrderStatus.CLOSED.name());
        assertCreatedGuard(wrapperCaptor.getValue(), order.getOrderNo());
        verify(client, never()).precreate(any());
        verify(client, never()).pagePay(any());
        verifyNoInteractions(credits, memberships);
        verify(mapper, never()).insert(any());
    }

    @Test
    void resumeQueryExceptionUsesUnifiedUncertainMessageAndDoesNothingElse() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-RESUME-QUERY-ERROR");
        when(mapper.selectOne(any())).thenReturn(order);
        when(client.query(order.getOrderNo())).thenThrow(new BusinessException("raw query failure"));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        assertThatThrownBy(() -> service.resumeOrder(7L, order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("支付状态暂未确认，请稍后查询，请勿重复支付");
        verify(client, never()).cancel(any());
        verify(client, never()).pagePay(any());
        verify(client, never()).precreate(any());
        verify(mapper, never()).update(any(), any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void switchNotFoundQrClosesOriginalAndCreatesPageFromStoredSnapshot() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder source = resumableQrOrder("PAY-SWITCH-NOT-FOUND");
        AtomicReference<PaymentOrder> persisted = new AtomicReference<>(source);
        when(mapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            persisted.get().setStatus(PaymentOrderStatus.CLOSED.name());
            return 1;
        });
        when(client.query(source.getOrderNo())).thenReturn(new AlipayQueryResult(
                source.getOrderNo(), null, null, null, "40004", "ACQ.TRADE_NOT_EXIST"
        ));
        when(client.pagePay(any())).thenReturn(new AlipayPagePayResult("<form>not-found</form>"));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        PaymentOrderCreateResponse response = service.switchToPage(7L, source.getOrderNo());

        ArgumentCaptor<PaymentOrder> insertCaptor = ArgumentCaptor.forClass(PaymentOrder.class);
        ArgumentCaptor<AlipayPagePayRequest> requestCaptor =
                ArgumentCaptor.forClass(AlipayPagePayRequest.class);
        verify(mapper).insert(insertCaptor.capture());
        verify(client).pagePay(requestCaptor.capture());
        PaymentOrder pageOrder = insertCaptor.getValue();
        assertThat(pageOrder.getOrderNo()).isNotEqualTo(source.getOrderNo());
        assertThat(pageOrder.getProductCode()).isEqualTo(source.getProductCode());
        assertThat(pageOrder.getProductName()).isEqualTo(source.getProductName());
        assertThat(pageOrder.getAmountCents()).isEqualTo(source.getAmountCents());
        assertThat(pageOrder.getBaseCredits()).isEqualTo(source.getBaseCredits());
        assertThat(pageOrder.getBonusCredits()).isEqualTo(source.getBonusCredits());
        assertThat(pageOrder.getTotalCredits()).isEqualTo(source.getTotalCredits());
        assertThat(requestCaptor.getValue().totalAmount()).isEqualByComparingTo("50.00");
        assertThat(response.paymentMethod()).isEqualTo(PaymentMethod.PAGE.name());
        assertThat(response.paymentReady()).isTrue();
        verify(client, never()).cancel(any());
    }

    @Test
    void switchUnknownQueryRejectsWithoutAnyFollowupPaymentAction() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder source = resumableQrOrder("PAY-SWITCH-UNKNOWN");
        when(mapper.selectOne(any())).thenReturn(source);
        when(client.query(source.getOrderNo())).thenReturn(new AlipayQueryResult(
                source.getOrderNo(), null, null, null, "40004", "ACQ.SYSTEM_ERROR"
        ));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        assertThatThrownBy(() -> service.switchToPage(7L, source.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("支付状态暂未确认，请稍后查询，请勿重复支付");
        verify(client, never()).cancel(any());
        verify(client, never()).pagePay(any());
        verify(client, never()).precreate(any());
        verify(mapper, never()).update(any(), any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void switchWrongOwnerRejectsBeforeCallingAlipay() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder source = resumableQrOrder("PAY-SWITCH-WRONG-OWNER");
        when(mapper.selectOne(any())).thenReturn(source);
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        assertThatThrownBy(() -> service.switchToPage(8L, source.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单不存在");
        verifyNoInteractions(client);
        verify(mapper, never()).update(any(), any());
        verify(mapper, never()).insert(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "CLOSED", "FAILED"})
    void switchTerminalSourceRejectsBeforeCallingAlipay(String status) {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder source = resumableQrOrder("PAY-SWITCH-TERMINAL-" + status);
        source.setStatus(status);
        when(mapper.selectOne(any())).thenReturn(source);
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        assertThatThrownBy(() -> service.switchToPage(7L, source.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该订单当前状态不支持切换支付方式");
        verifyNoInteractions(client);
        verify(mapper, never()).update(any(), any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void switchQueryExceptionUsesUnifiedUncertainMessageAndDoesNothingElse() {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder source = resumableQrOrder("PAY-SWITCH-QUERY-ERROR");
        when(mapper.selectOne(any())).thenReturn(source);
        when(client.query(source.getOrderNo())).thenThrow(new BusinessException("raw query failure"));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );

        assertThatThrownBy(() -> service.switchToPage(7L, source.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("支付状态暂未确认，请稍后查询，请勿重复支付");
        verify(client, never()).cancel(any());
        verify(client, never()).pagePay(any());
        verify(client, never()).precreate(any());
        verify(mapper, never()).update(any(), any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void concurrentResumePrecreateFinishesBeforeSwitchQueriesAndCancelsQrBeforePage()
            throws Exception {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder source = resumableQrOrder("PAY-CONCURRENT-RESUME-FIRST");
        AtomicReference<PaymentOrder> persisted = new AtomicReference<>(source);
        AtomicInteger queryCalls = new AtomicInteger();
        AtomicInteger pageInserts = new AtomicInteger();
        AtomicBoolean qrPayable = new AtomicBoolean();
        AtomicBoolean definitiveCancel = new AtomicBoolean();
        CountDownLatch precreateEntered = new CountDownLatch(1);
        CountDownLatch allowPrecreate = new CountDownLatch(1);
        CountDownLatch switchStarted = new CountDownLatch(1);
        CountDownLatch switchQueryEntered = new CountDownLatch(1);

        when(mapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            PaymentOrder update = invocation.getArgument(0);
            synchronized (persisted.get()) {
                if (!PaymentOrderStatus.CREATED.name().equals(persisted.get().getStatus())) {
                    return 0;
                }
                if (update.getQrcode() != null) {
                    persisted.get().setQrcode(update.getQrcode());
                }
                if (update.getStatus() != null) {
                    persisted.get().setStatus(update.getStatus());
                }
                return 1;
            }
        });
        when(mapper.insert(any())).thenAnswer(invocation -> {
            PaymentOrder inserted = invocation.getArgument(0);
            if (PaymentMethod.PAGE.name().equals(inserted.getPaymentMethod())) {
                pageInserts.incrementAndGet();
            }
            return 1;
        });
        when(client.query(source.getOrderNo())).thenAnswer(invocation -> {
            if (queryCalls.incrementAndGet() == 1) {
                return new AlipayQueryResult(
                        source.getOrderNo(), null, null, null, "40004", "ACQ.TRADE_NOT_EXIST"
                );
            }
            switchQueryEntered.countDown();
            return qrPayable.get()
                    ? new AlipayQueryResult(source.getOrderNo(), null, "WAIT_BUYER_PAY", null)
                    : new AlipayQueryResult(
                    source.getOrderNo(), null, null, null, "40004", "ACQ.TRADE_NOT_EXIST"
            );
        });
        when(client.precreate(any())).thenAnswer(invocation -> {
            precreateEntered.countDown();
            assertThat(allowPrecreate.await(2, TimeUnit.SECONDS)).isTrue();
            qrPayable.set(true);
            return precreateResult(invocation, "https://qr.alipay.com/concurrent-resume-first");
        });
        when(client.cancel(source.getOrderNo())).thenAnswer(invocation -> {
            qrPayable.set(false);
            definitiveCancel.set(true);
            return new AlipayCancelResult(true, false, "close");
        });
        when(client.pagePay(any())).thenReturn(new AlipayPagePayResult("<form>page</form>"));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<PaymentOrderCreateResponse> resumeFuture = executor.submit(
                    () -> service.resumeOrder(7L, source.getOrderNo()));
            assertThat(precreateEntered.await(2, TimeUnit.SECONDS)).isTrue();

            Future<PaymentOrderCreateResponse> switchFuture = executor.submit(() -> {
                switchStarted.countDown();
                return service.switchToPage(7L, source.getOrderNo());
            });
            assertThat(switchStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> switchFuture.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(switchQueryEntered.getCount()).isEqualTo(1L);
            verify(client, never()).cancel(any());
            verify(client, never()).pagePay(any());

            allowPrecreate.countDown();
            PaymentOrderCreateResponse resumeResponse = resumeFuture.get(2, TimeUnit.SECONDS);
            PaymentOrderCreateResponse switchResponse = switchFuture.get(2, TimeUnit.SECONDS);

            assertThat(resumeResponse.paymentMethod()).isEqualTo(PaymentMethod.QR.name());
            assertThat(resumeResponse.paymentReady()).isTrue();
            assertThat(switchResponse.paymentMethod()).isEqualTo(PaymentMethod.PAGE.name());
            assertThat(switchResponse.paymentReady()).isTrue();
            assertThat(definitiveCancel).isTrue();
            assertThat(qrPayable).isFalse();
            assertThat(source.getStatus()).isEqualTo(PaymentOrderStatus.CLOSED.name());
            assertThat(pageInserts).hasValue(1);
            assertThat(queryCalls).hasValue(3);
            InOrder paymentOrder = inOrder(client);
            paymentOrder.verify(client).cancel(source.getOrderNo());
            paymentOrder.verify(client).pagePay(any());
            verify(mapper, times(1)).insert(any());
        } finally {
            allowPrecreate.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentSwitchCompletesBeforeResumeAndPreventsOriginalQrPrecreate()
            throws Exception {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder source = resumableQrOrder("PAY-CONCURRENT-SWITCH-FIRST");
        AtomicReference<PaymentOrder> persisted = new AtomicReference<>(source);
        AtomicInteger queryCalls = new AtomicInteger();
        AtomicInteger pageInserts = new AtomicInteger();
        AtomicBoolean qrPayable = new AtomicBoolean();
        CountDownLatch switchQueryEntered = new CountDownLatch(1);
        CountDownLatch allowSwitchQuery = new CountDownLatch(1);
        CountDownLatch resumeStarted = new CountDownLatch(1);
        CountDownLatch precreateEntered = new CountDownLatch(1);

        when(mapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            PaymentOrder update = invocation.getArgument(0);
            synchronized (persisted.get()) {
                if (!PaymentOrderStatus.CREATED.name().equals(persisted.get().getStatus())) {
                    return 0;
                }
                if (update.getQrcode() != null) {
                    persisted.get().setQrcode(update.getQrcode());
                }
                if (update.getStatus() != null) {
                    persisted.get().setStatus(update.getStatus());
                }
                return 1;
            }
        });
        when(mapper.insert(any())).thenAnswer(invocation -> {
            PaymentOrder inserted = invocation.getArgument(0);
            if (PaymentMethod.PAGE.name().equals(inserted.getPaymentMethod())) {
                pageInserts.incrementAndGet();
            }
            return 1;
        });
        when(client.query(source.getOrderNo())).thenAnswer(invocation -> {
            if (queryCalls.incrementAndGet() == 1) {
                switchQueryEntered.countDown();
                assertThat(allowSwitchQuery.await(2, TimeUnit.SECONDS)).isTrue();
            }
            return new AlipayQueryResult(
                    source.getOrderNo(), null, null, null, "40004", "ACQ.TRADE_NOT_EXIST"
            );
        });
        when(client.precreate(any())).thenAnswer(invocation -> {
            precreateEntered.countDown();
            qrPayable.set(true);
            return precreateResult(invocation, "https://qr.alipay.com/unsafe-late-precreate");
        });
        when(client.pagePay(any())).thenReturn(new AlipayPagePayResult("<form>page</form>"));
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<PaymentOrderCreateResponse> switchFuture = executor.submit(
                    () -> service.switchToPage(7L, source.getOrderNo()));
            assertThat(switchQueryEntered.await(2, TimeUnit.SECONDS)).isTrue();

            Future<PaymentOrderCreateResponse> resumeFuture = executor.submit(() -> {
                resumeStarted.countDown();
                return service.resumeOrder(7L, source.getOrderNo());
            });
            assertThat(resumeStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> resumeFuture.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(precreateEntered.getCount()).isEqualTo(1L);
            verify(client, never()).precreate(any());

            allowSwitchQuery.countDown();
            PaymentOrderCreateResponse switchResponse = switchFuture.get(2, TimeUnit.SECONDS);
            PaymentOrderCreateResponse resumeResponse = resumeFuture.get(2, TimeUnit.SECONDS);

            assertThat(switchResponse.paymentMethod()).isEqualTo(PaymentMethod.PAGE.name());
            assertThat(switchResponse.paymentReady()).isTrue();
            assertThat(resumeResponse.status()).isEqualTo(PaymentOrderStatus.CLOSED.name());
            assertThat(resumeResponse.paymentReady()).isFalse();
            assertThat(resumeResponse.paymentHtml()).isNull();
            assertThat(resumeResponse.qrCode()).isNull();
            assertThat(source.getStatus()).isEqualTo(PaymentOrderStatus.CLOSED.name());
            assertThat(qrPayable).isFalse();
            assertThat(pageInserts).hasValue(1);
            assertThat(queryCalls).hasValue(1);
            verify(client, never()).precreate(any());
            verify(client, never()).cancel(any());
            verify(client, times(1)).pagePay(any());
            verify(mapper, times(1)).insert(any());
        } finally {
            allowSwitchQuery.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentResumeRenewsQrBeforeSyncQueriesAndLeavesOrderCreated()
            throws Exception {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder order = resumableQrOrder("PAY-CONCURRENT-RESUME-SYNC");
        AtomicReference<PaymentOrder> persisted = new AtomicReference<>(order);
        AtomicReference<LocalDateTime> precreateCompletedAt = new AtomicReference<>();
        AtomicInteger queryCalls = new AtomicInteger();
        AtomicInteger closeUpdates = new AtomicInteger();
        AtomicBoolean qrPayable = new AtomicBoolean();
        CountDownLatch precreateEntered = new CountDownLatch(1);
        CountDownLatch allowPrecreate = new CountDownLatch(1);
        CountDownLatch syncStarted = new CountDownLatch(1);
        CountDownLatch syncQueryEntered = new CountDownLatch(1);

        when(mapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            PaymentOrder update = invocation.getArgument(0);
            synchronized (persisted) {
                PaymentOrder current = persisted.get();
                if (!PaymentOrderStatus.CREATED.name().equals(current.getStatus())) {
                    return 0;
                }
                if (update.getQrcode() != null) {
                    current.setQrcode(update.getQrcode());
                }
                if (update.getExpiresAt() != null) {
                    current.setExpiresAt(update.getExpiresAt());
                }
                if (update.getUpdatedAt() != null) {
                    current.setUpdatedAt(update.getUpdatedAt());
                }
                if (update.getStatus() != null) {
                    current.setStatus(update.getStatus());
                    if (PaymentOrderStatus.CLOSED.name().equals(update.getStatus())) {
                        closeUpdates.incrementAndGet();
                    }
                }
                return 1;
            }
        });
        when(client.query(order.getOrderNo())).thenAnswer(invocation -> {
            if (queryCalls.incrementAndGet() == 1) {
                return new AlipayQueryResult(
                        order.getOrderNo(), null, null, null, "40004", "ACQ.TRADE_NOT_EXIST"
                );
            }
            syncQueryEntered.countDown();
            return qrPayable.get()
                    ? new AlipayQueryResult(order.getOrderNo(), null, "WAIT_BUYER_PAY", null)
                    : new AlipayQueryResult(
                    order.getOrderNo(), null, null, null, "40004", "ACQ.TRADE_NOT_EXIST"
            );
        });
        when(client.precreate(any())).thenAnswer(invocation -> {
            synchronized (persisted) {
                persisted.get().setExpiresAt(LocalDateTime.now().minusSeconds(1));
            }
            precreateEntered.countDown();
            assertThat(allowPrecreate.await(2, TimeUnit.SECONDS)).isTrue();
            qrPayable.set(true);
            precreateCompletedAt.set(LocalDateTime.now());
            return precreateResult(invocation, "https://qr.alipay.com/concurrent-resume-sync");
        });
        when(client.cancel(order.getOrderNo())).thenAnswer(invocation -> {
            qrPayable.set(false);
            return new AlipayCancelResult(true, false, "close");
        });
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<PaymentOrderCreateResponse> resumeFuture = executor.submit(
                    () -> service.resumeOrder(7L, order.getOrderNo()));
            assertThat(precreateEntered.await(2, TimeUnit.SECONDS)).isTrue();

            Future<?> syncFuture = executor.submit(() -> {
                syncStarted.countDown();
                return service.syncOrder(7L, order.getOrderNo());
            });
            assertThat(syncStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> syncFuture.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(syncQueryEntered.getCount()).isEqualTo(1L);
            assertThat(queryCalls).hasValue(1);
            assertThat(closeUpdates).hasValue(0);
            verify(client, never()).cancel(any());

            allowPrecreate.countDown();
            PaymentOrderCreateResponse resumeResponse = resumeFuture.get(2, TimeUnit.SECONDS);
            Object syncResponse = syncFuture.get(2, TimeUnit.SECONDS);

            assertThat(resumeResponse.status()).isEqualTo(PaymentOrderStatus.CREATED.name());
            assertThat(resumeResponse.paymentReady()).isTrue();
            assertThat(resumeResponse.qrCode())
                    .isEqualTo("https://qr.alipay.com/concurrent-resume-sync");
            assertThat(resumeResponse.expiresAt())
                    .isAfterOrEqualTo(precreateCompletedAt.get().plusHours(2));
            assertThat(syncResponse).extracting("status")
                    .isEqualTo(PaymentOrderStatus.CREATED.name());
            assertThat(persisted.get().getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
            assertThat(qrPayable).isTrue();
            assertThat(closeUpdates).hasValue(0);
            assertThat(queryCalls).hasValue(3);
            verify(client, never()).cancel(any());
            verify(mapper, never()).insert(any());
        } finally {
            allowPrecreate.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentExpiryReloadSkipsOrderRenewedByResume()
            throws Exception {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        PaymentOrder staleScanOrder = resumableQrOrder("PAY-CONCURRENT-RESUME-EXPIRY");
        staleScanOrder.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        PaymentOrder current = resumableQrOrder(staleScanOrder.getOrderNo());
        AtomicReference<PaymentOrder> persisted = new AtomicReference<>(current);
        AtomicReference<LocalDateTime> precreateCompletedAt = new AtomicReference<>();
        AtomicInteger queryCalls = new AtomicInteger();
        AtomicInteger closeUpdates = new AtomicInteger();
        AtomicBoolean qrPayable = new AtomicBoolean();
        CountDownLatch precreateEntered = new CountDownLatch(1);
        CountDownLatch allowPrecreate = new CountDownLatch(1);
        CountDownLatch staleScanSelected = new CountDownLatch(1);

        when(mapper.selectList(any())).thenAnswer(invocation -> {
            staleScanSelected.countDown();
            return List.of(staleScanOrder);
        });
        when(mapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            PaymentOrder update = invocation.getArgument(0);
            synchronized (persisted) {
                PaymentOrder stored = persisted.get();
                if (!PaymentOrderStatus.CREATED.name().equals(stored.getStatus())) {
                    return 0;
                }
                if (update.getQrcode() != null) {
                    stored.setQrcode(update.getQrcode());
                }
                if (update.getExpiresAt() != null) {
                    stored.setExpiresAt(update.getExpiresAt());
                }
                if (update.getUpdatedAt() != null) {
                    stored.setUpdatedAt(update.getUpdatedAt());
                }
                if (update.getStatus() != null) {
                    stored.setStatus(update.getStatus());
                    if (PaymentOrderStatus.CLOSED.name().equals(update.getStatus())) {
                        closeUpdates.incrementAndGet();
                    }
                }
                return 1;
            }
        });
        when(client.query(current.getOrderNo())).thenAnswer(invocation -> {
            int call = queryCalls.incrementAndGet();
            return call == 1
                    ? notFoundResult(current.getOrderNo())
                    : waitingResult(current.getOrderNo());
        });
        when(client.precreate(any())).thenAnswer(invocation -> {
            synchronized (persisted) {
                persisted.get().setExpiresAt(LocalDateTime.now().minusSeconds(1));
            }
            precreateEntered.countDown();
            assertThat(allowPrecreate.await(2, TimeUnit.SECONDS)).isTrue();
            qrPayable.set(true);
            precreateCompletedAt.set(LocalDateTime.now());
            return precreateResult(invocation, "https://qr.alipay.com/concurrent-resume-expiry");
        });
        when(client.cancel(current.getOrderNo())).thenAnswer(invocation -> {
            qrPayable.set(false);
            return new AlipayCancelResult(true, false, "close");
        });
        PaymentOrderServiceImpl service = service(
                mapper,
                mock(UserMembershipMapper.class),
                mock(CreditAccountService.class),
                mock(MembershipService.class),
                client
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<PaymentOrderCreateResponse> resumeFuture = executor.submit(
                    () -> service.resumeOrder(7L, current.getOrderNo()));
            assertThat(precreateEntered.await(2, TimeUnit.SECONDS)).isTrue();

            Future<?> expiryFuture = executor.submit(service::processExpiredQrOrders);
            assertThat(staleScanSelected.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> expiryFuture.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(queryCalls).hasValue(1);
            assertThat(closeUpdates).hasValue(0);
            verify(client, never()).cancel(any());

            allowPrecreate.countDown();
            PaymentOrderCreateResponse resumeResponse = resumeFuture.get(2, TimeUnit.SECONDS);
            expiryFuture.get(2, TimeUnit.SECONDS);

            assertThat(resumeResponse.status()).isEqualTo(PaymentOrderStatus.CREATED.name());
            assertThat(resumeResponse.paymentReady()).isTrue();
            assertThat(resumeResponse.qrCode())
                    .isEqualTo("https://qr.alipay.com/concurrent-resume-expiry");
            assertThat(resumeResponse.expiresAt())
                    .isAfterOrEqualTo(precreateCompletedAt.get().plusHours(2));
            assertThat(persisted.get().getStatus()).isEqualTo(PaymentOrderStatus.CREATED.name());
            assertThat(persisted.get().getQrcode())
                    .isEqualTo("https://qr.alipay.com/concurrent-resume-expiry");
            assertThat(qrPayable).isTrue();
            assertThat(queryCalls).hasValue(2);
            assertThat(closeUpdates).hasValue(0);
            verify(client, never()).cancel(any());
            verify(mapper, times(3)).selectOne(any());
            verify(mapper, never()).insert(any());
        } finally {
            allowPrecreate.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void refundCancelBlocksCallbackUntilSwitchClosesOriginalAndCreatesPageOrder()
            throws Exception {
        PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
        AlipayPaymentClient client = mock(AlipayPaymentClient.class);
        CreditAccountService credits = mock(CreditAccountService.class);
        MembershipService memberships = mock(MembershipService.class);
        PaymentOrder source = resumableQrOrder("PAY-CONCURRENT-REFUND-CALLBACK");
        AtomicReference<PaymentOrder> persisted = new AtomicReference<>(source);
        AtomicInteger pageInserts = new AtomicInteger();
        AtomicBoolean refundReturned = new AtomicBoolean();
        CountDownLatch closeUpdateEntered = new CountDownLatch(1);
        CountDownLatch allowCloseUpdate = new CountDownLatch(1);
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch transactionStarted = new CountDownLatch(1);

        when(mapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            PaymentOrder update = invocation.getArgument(0);
            if (PaymentOrderStatus.CLOSED.name().equals(update.getStatus())) {
                closeUpdateEntered.countDown();
                assertThat(allowCloseUpdate.await(2, TimeUnit.SECONDS)).isTrue();
            }
            synchronized (persisted) {
                PaymentOrder current = persisted.get();
                if (!PaymentOrderStatus.CREATED.name().equals(current.getStatus())) {
                    return 0;
                }
                current.setStatus(update.getStatus());
                return 1;
            }
        });
        when(mapper.insert(any())).thenAnswer(invocation -> {
            PaymentOrder inserted = invocation.getArgument(0);
            if (PaymentMethod.PAGE.name().equals(inserted.getPaymentMethod())) {
                pageInserts.incrementAndGet();
            }
            return 1;
        });
        when(client.query(source.getOrderNo())).thenReturn(new AlipayQueryResult(
                source.getOrderNo(), null, "WAIT_BUYER_PAY", null
        ));
        when(client.cancel(source.getOrderNo())).thenAnswer(invocation -> {
            refundReturned.set(true);
            return new AlipayCancelResult(true, false, "refund");
        });
        when(client.pagePay(any())).thenAnswer(invocation -> {
            assertThat(refundReturned).isTrue();
            assertThat(persisted.get().getStatus()).isEqualTo(PaymentOrderStatus.CLOSED.name());
            return new AlipayPagePayResult("<form>page</form>");
        });
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> {
            transactionStarted.countDown();
            return transactionStatus;
        });
        PaymentOrderServiceImpl service = new PaymentOrderServiceImpl(
                mapper,
                credits,
                memberships,
                new PaymentProductCatalog(),
                alipayProperties(),
                client,
                mock(PaymentRetrySleeper.class),
                new TransactionTemplate(transactionManager)
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<PaymentOrderCreateResponse> switchFuture = executor.submit(
                    () -> service.switchToPage(7L, source.getOrderNo()));
            assertThat(closeUpdateEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(refundReturned).isTrue();

            Future<PaymentSettlement> callbackFuture = executor.submit(() -> {
                callbackStarted.countDown();
                return service.settlePaidOrder(
                        source.getOrderNo(),
                        "trade-after-refund",
                        "notify-after-refund",
                        new BigDecimal("50.00"),
                        "TRADE_SUCCESS",
                        "app-1",
                        "seller-1"
                );
            });
            assertThat(callbackStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(transactionStarted.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThatThrownBy(() -> callbackFuture.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            verify(client, never()).pagePay(any());
            verifyNoInteractions(credits, memberships);

            allowCloseUpdate.countDown();
            PaymentOrderCreateResponse switchResponse = switchFuture.get(2, TimeUnit.SECONDS);
            PaymentSettlement settlement = callbackFuture.get(2, TimeUnit.SECONDS);

            assertThat(switchResponse.paymentMethod()).isEqualTo(PaymentMethod.PAGE.name());
            assertThat(switchResponse.paymentReady()).isTrue();
            assertThat(switchResponse.orderNo()).isNotEqualTo(source.getOrderNo());
            assertThat(settlement.settled()).isFalse();
            assertThat(settlement.status()).isEqualTo(PaymentOrderStatus.CLOSED.name());
            assertThat(source.getStatus()).isEqualTo(PaymentOrderStatus.CLOSED.name());
            assertThat(pageInserts).hasValue(1);
            assertThat(transactionStarted.getCount()).isZero();
            verify(mapper, times(1)).update(any(), any());
            verify(mapper, times(1)).insert(any());
            verifyNoInteractions(credits, memberships);
            InOrder paymentFlow = inOrder(client);
            paymentFlow.verify(client).query(source.getOrderNo());
            paymentFlow.verify(client).cancel(source.getOrderNo());
            paymentFlow.verify(client).pagePay(any());
        } finally {
            allowCloseUpdate.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
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

    private PaymentOrder resumableQrOrder(String orderNo) {
        PaymentOrder order = pendingRechargeOrder(orderNo);
        order.setProductCode("recharge_5000");
        order.setProductName("积分充值¥50");
        order.setPaymentMethod(PaymentMethod.QR.name());
        order.setChannel("ALIPAY");
        order.setAppId("app-1");
        order.setSellerId("seller-1");
        order.setExpiresAt(LocalDateTime.now().plusHours(1));
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
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

    private Map<String, PaymentOrder> stubOrderStore(PaymentOrderMapper mapper) {
        Map<String, PaymentOrder> orders = new LinkedHashMap<>();
        when(mapper.insert(any())).thenAnswer(invocation -> {
            PaymentOrder order = invocation.getArgument(0);
            orders.put(order.getOrderNo(), order);
            return 1;
        });
        when(mapper.selectOne(any())).thenAnswer(invocation -> {
            @SuppressWarnings("rawtypes")
            LambdaQueryWrapper wrapper = invocation.getArgument(0);
            wrapper.getSqlSegment();
            return orders.get(orderNoFromParameters(wrapper.getParamNameValuePairs(), orders));
        });
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            PaymentOrder update = invocation.getArgument(0);
            @SuppressWarnings("rawtypes")
            LambdaUpdateWrapper wrapper = invocation.getArgument(1);
            wrapper.getSqlSegment();
            PaymentOrder current = orders.get(orderNoFromParameters(wrapper.getParamNameValuePairs(), orders));
            if (current == null || !PaymentOrderStatus.CREATED.name().equals(current.getStatus())) {
                return 0;
            }
            if (update.getQrcode() != null) {
                current.setQrcode(update.getQrcode());
            }
            if (wrapper.getSqlSet() != null && wrapper.getSqlSet().contains("qrcode")) {
                current.setQrcode(null);
            }
            if (update.getExpiresAt() != null) {
                current.setExpiresAt(update.getExpiresAt());
            }
            if (update.getStatus() != null) {
                current.setStatus(update.getStatus());
            }
            if (update.getAlipayTradeNo() != null) {
                current.setAlipayTradeNo(update.getAlipayTradeNo());
            }
            if (update.getNotifyId() != null) {
                current.setNotifyId(update.getNotifyId());
            }
            if (update.getTradeStatus() != null) {
                current.setTradeStatus(update.getTradeStatus());
            }
            if (update.getPaidAt() != null) {
                current.setPaidAt(update.getPaidAt());
            }
            if (update.getCreditedAt() != null) {
                current.setCreditedAt(update.getCreditedAt());
            }
            current.setUpdatedAt(update.getUpdatedAt());
            return 1;
        });
        return orders;
    }

    private String orderNoFromParameters(Map<String, Object> parameters, Map<String, PaymentOrder> orders) {
        String orderNo = parameters.values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(orders::containsKey)
                .findFirst()
                .orElseGet(() -> orders.size() == 1 ? orders.keySet().iterator().next() : null);
        if (orderNo == null) {
            throw new AssertionError("Unable to resolve order from " + parameters + " among " + orders.keySet());
        }
        return orderNo;
    }

    private AlipayQueryResult notFoundResult(String orderNo) {
        return new AlipayQueryResult(orderNo, null, null, null, "40004", "ACQ.TRADE_NOT_EXIST");
    }

    private AlipayQueryResult waitingResult(String orderNo) {
        return new AlipayQueryResult(orderNo, null, "WAIT_BUYER_PAY", null);
    }

    private AlipayPrecreateResult precreateResult(InvocationOnMock invocation, String qrCode) {
        AlipayPrecreateRequest request = invocation.getArgument(0);
        return new AlipayPrecreateResult(request.orderNo(), qrCode);
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
