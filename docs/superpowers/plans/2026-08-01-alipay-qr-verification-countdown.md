# Alipay QR Verification and Countdown Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a server-authoritative QR expiry countdown while preventing unverified Alipay order-code QR links from reaching users, including a single bounded replacement when Alipay repeatedly reports that a newly precreated trade does not exist.

**Architecture:** Strengthen the Alipay client contract by removing the undocumented timeout field and requiring the response order number to match. In `PaymentOrderServiceImpl`, insert the local order first, precreate, wait for Alipay propagation, query up to three times, and persist the QR only after `WAIT_BUYER_PAY`; a confirmed not-found result may close the source and create one replacement, while unknown states keep the order unready and never create another payment. The frontend reuses its existing `expiresAt` countdown, keeps normal polling silent, and reveals only the official cashier fallback when QR preparation fails.

**Tech Stack:** Java 17, Spring Boot, Alipay Java SDK, MyBatis-Plus, JUnit 5, Mockito, Django templates/tests, vanilla JavaScript, Node test runner

---

## File structure

- Modify `backend/src/main/java/com/manga/ai/payment/dto/AlipayPrecreateRequest.java`: remove the unsupported timeout argument.
- Modify `backend/src/main/java/com/manga/ai/payment/dto/AlipayPrecreateResult.java`: carry the Alipay response order number with the QR URL.
- Modify `backend/src/main/java/com/manga/ai/payment/client/DefaultAlipayPaymentClient.java`: omit undocumented request data and reject mismatched response orders.
- Modify `backend/src/main/java/com/manga/ai/payment/service/PaymentRetrySleeper.java`: add testable 5-second initial verification and 2-second retry waits.
- Modify `backend/src/main/java/com/manga/ai/payment/service/PaymentOrderServiceImpl.java`: add the bounded verification gate and one-time replacement.
- Modify `backend/src/test/java/com/manga/ai/payment/client/DefaultAlipayPaymentClientTest.java`: client contract tests.
- Modify `backend/src/test/java/com/manga/ai/payment/service/PaymentOrderServiceImplTest.java`: verification, replacement, uncertainty and race tests.
- Modify `frontend/templates/payments/membership.html`: make the countdown available to the renderer while leaving status/actions hidden by default.
- Modify `frontend/static/js/payment-center.js`: show countdown only for real QR/terminal states and expose only the cashier fallback on QR preparation failure.
- Modify `frontend/apps/series/test_payment_qr.py`: payment-page display contracts.
- Verify `frontend/static/js/payment-center-core.js`: no state-machine changes are required unless a failing test proves otherwise.
- Verify only `frontend/staticfiles/` and `backend/target/`: generated build output, never commit either directory.

### Task 1: Enforce a strict Alipay precreate client contract

**Files:**
- Modify: `backend/src/test/java/com/manga/ai/payment/client/DefaultAlipayPaymentClientTest.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/dto/AlipayPrecreateRequest.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/dto/AlipayPrecreateResult.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/client/DefaultAlipayPaymentClient.java:98-127`

- [ ] **Step 1: Change the successful request test and add mismatch tests**

Replace the current two-hour request assertion with this contract:

```java
@Test
void precreateUsesOnlyDocumentedOrderCodeFieldsAndReturnsMatchedOrderNumber()
        throws AlipayApiException {
    AlipayClient sdkClient = mock(AlipayClient.class);
    AlipayTradePrecreateResponse response = successfulPrecreateResponse(
            "PAY202607170010", "https://qr.alipay.com/example");
    when(sdkClient.execute(any(AlipayTradePrecreateRequest.class))).thenReturn(response);

    AlipayPrecreateResult result = paymentClient(sdkClient).precreate(new AlipayPrecreateRequest(
            "PAY202607170010",
            "高级会员",
            new BigDecimal("50.00"),
            "https://www.yzmovie.cn/api/v1/payments/alipay/notify"
    ));

    ArgumentCaptor<AlipayTradePrecreateRequest> captor =
            ArgumentCaptor.forClass(AlipayTradePrecreateRequest.class);
    verify(sdkClient).execute(captor.capture());
    AlipayTradePrecreateModel model =
            (AlipayTradePrecreateModel) captor.getValue().getBizModel();

    assertThat(result.orderNo()).isEqualTo("PAY202607170010");
    assertThat(result.qrCode()).isEqualTo("https://qr.alipay.com/example");
    assertThat(model.getProductCode()).isEqualTo("QR_CODE_OFFLINE");
    assertThat(model.getQrCodeTimeoutExpress()).isNull();
}

@Test
void precreateRejectsResponseForAnotherOrder() throws AlipayApiException {
    AlipayClient sdkClient = mock(AlipayClient.class);
    when(sdkClient.execute(any(AlipayTradePrecreateRequest.class))).thenReturn(
            successfulPrecreateResponse("PAY-OTHER", "https://qr.alipay.com/example"));

    assertThatThrownBy(() -> paymentClient(sdkClient).precreate(precreateRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("支付宝扫码支付订单校验失败，请改用支付宝收银台");
}

@Test
void precreateRejectsBlankResponseOrderNumber() throws AlipayApiException {
    AlipayClient sdkClient = mock(AlipayClient.class);
    when(sdkClient.execute(any(AlipayTradePrecreateRequest.class))).thenReturn(
            successfulPrecreateResponse(" ", "https://qr.alipay.com/example"));

    assertThatThrownBy(() -> paymentClient(sdkClient).precreate(precreateRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("支付宝扫码支付订单校验失败，请改用支付宝收银台");
}

private AlipayTradePrecreateResponse successfulPrecreateResponse(String orderNo, String qrCode) {
    AlipayTradePrecreateResponse response = new AlipayTradePrecreateResponse();
    response.setCode("10000");
    response.setOutTradeNo(orderNo);
    response.setQrCode(qrCode);
    return response;
}
```

Use `successfulPrecreateResponse(precreateRequest().orderNo(), qrCode)` in every existing test that expects a successful precreate; unsuccessful response tests keep their existing response codes.

Update the shared request fixture to the four-field record:

```java
private AlipayPrecreateRequest precreateRequest() {
    return new AlipayPrecreateRequest(
            "PAY202607170010",
            "积分充值¥50",
            new BigDecimal("50.00"),
            "https://www.yzmovie.cn/api/v1/payments/alipay/notify"
    );
}
```

- [ ] **Step 2: Run the client tests and verify RED**

Run:

```bash
cd backend
mvn -q -Dtest=DefaultAlipayPaymentClientTest test
```

Expected: compilation/test failure because the request/result records and client still use the old timeout-only contract.

- [ ] **Step 3: Remove the timeout field from the request record and add response order number**

```java
public record AlipayPrecreateRequest(
        String orderNo,
        String subject,
        BigDecimal totalAmount,
        String notifyUrl
) {
}
```

```java
public record AlipayPrecreateResult(String orderNo, String qrCode) {
}
```

- [ ] **Step 4: Validate the response order before returning the QR**

In `DefaultAlipayPaymentClient.precreate`, remove:

```java
model.setQrCodeTimeoutExpress("2h");
```

After `response.isSuccess()` and before QR validation, add:

```java
if (!notBlank(response.getOutTradeNo())
        || !request.orderNo().equals(response.getOutTradeNo())) {
    log.warn("支付宝订单码响应订单号不匹配: requestOrderNo={}, responseOrderNo={}",
            request.orderNo(), response.getOutTradeNo());
    throw new BusinessException("支付宝扫码支付订单校验失败，请改用支付宝收银台");
}
```

Return both values:

```java
return new AlipayPrecreateResult(response.getOutTradeNo(), response.getQrCode());
```

- [ ] **Step 5: Run the focused test and verify GREEN**

```bash
mvn -q -Dtest=DefaultAlipayPaymentClientTest test
```

Expected: all client tests pass; the captured model has a null `qrCodeTimeoutExpress`.

- [ ] **Step 6: Commit the gateway contract**

```bash
git add backend/src/main/java/com/manga/ai/payment/dto/AlipayPrecreateRequest.java \
  backend/src/main/java/com/manga/ai/payment/dto/AlipayPrecreateResult.java \
  backend/src/main/java/com/manga/ai/payment/client/DefaultAlipayPaymentClient.java \
  backend/src/test/java/com/manga/ai/payment/client/DefaultAlipayPaymentClientTest.java
git commit -m "fix: validate alipay qr precreate response"
```

### Task 2: Add testable verification timing

**Files:**
- Modify: `backend/src/test/java/com/manga/ai/payment/service/PaymentOrderServiceImplTest.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/service/PaymentRetrySleeper.java`

- [ ] **Step 1: Add a focused interruption test for the verification wait**

```java
@Test
void qrVerificationSleeperRestoresInterruptFlag() {
    PaymentRetrySleeper sleeper = new PaymentRetrySleeper();
    Thread.currentThread().interrupt();
    try {
        assertThatThrownBy(sleeper::pauseBeforeQrVerification)
                .isInstanceOf(BusinessException.class)
                .hasMessage("支付宝扫码支付请求已中断，请重新尝试");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
        Thread.interrupted();
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

```bash
mvn -q -Dtest=PaymentOrderServiceImplTest#qrVerificationSleeperRestoresInterruptFlag test
```

Expected: compilation failure because `pauseBeforeQrVerification()` does not exist.

- [ ] **Step 3: Refactor the sleeper around one interruption-safe primitive**

```java
public void pauseBeforeAttempt(int nextAttempt) {
    long baseMillis = nextAttempt == 2 ? 1000L : 2000L;
    pause(baseMillis + ThreadLocalRandom.current().nextLong(0L, 251L));
}

public void pauseBeforeQrVerification() {
    pause(5000L);
}

public void pauseBeforeQrVerificationRetry() {
    pause(2000L);
}

private void pause(long delayMillis) {
    try {
        Thread.sleep(delayMillis);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new BusinessException("支付宝扫码支付请求已中断，请重新尝试");
    }
}
```

- [ ] **Step 4: Run the focused sleeper tests**

```bash
mvn -q -Dtest=PaymentOrderServiceImplTest#qrVerificationSleeperRestoresInterruptFlag+retrySleeperRestoresInterruptFlagAndThrowsSafeBusinessException test
```

Expected: both interruption tests pass without waiting five seconds because the thread is already interrupted.

- [ ] **Step 5: Commit the verification timing primitive**

```bash
git add backend/src/main/java/com/manga/ai/payment/service/PaymentRetrySleeper.java \
  backend/src/test/java/com/manga/ai/payment/service/PaymentOrderServiceImplTest.java
git commit -m "feat: add alipay qr verification waits"
```

### Task 3: Gate QR persistence on authoritative query results

**Files:**
- Modify: `backend/src/test/java/com/manga/ai/payment/service/PaymentOrderServiceImplTest.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/service/PaymentOrderServiceImpl.java:50-505`

- [ ] **Step 1: Add the verified-ready persistence test**

```java
@Test
void qrIsPersistedOnlyAfterAlipayConfirmsWaitingForBuyer() {
    PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
    AlipayPaymentClient client = mock(AlipayPaymentClient.class);
    PaymentRetrySleeper sleeper = mock(PaymentRetrySleeper.class);
    AtomicReference<PaymentOrder> persisted = stubOrderPersistence(mapper);
    when(client.precreate(any())).thenAnswer(invocation -> {
        AlipayPrecreateRequest request = invocation.getArgument(0);
        return new AlipayPrecreateResult(request.orderNo(), "https://qr.alipay.com/verified");
    });
    when(client.query(any())).thenAnswer(invocation -> new AlipayQueryResult(
            invocation.getArgument(0), null, "WAIT_BUYER_PAY", new BigDecimal("50.00")));
    PaymentOrderServiceImpl service = service(
            mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
            mock(MembershipService.class), client, sleeper);

    PaymentOrderCreateResponse response =
            service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

    InOrder order = inOrder(mapper, client, sleeper);
    order.verify(mapper).insert(any());
    order.verify(client).precreate(any());
    order.verify(sleeper).pauseBeforeQrVerification();
    order.verify(client).query(response.orderNo());
    order.verify(mapper).update(any(), any());
    assertThat(persisted.get().getQrcode()).isEqualTo("https://qr.alipay.com/verified");
    assertThat(response.paymentReady()).isTrue();
    assertThat(response.qrCode()).isEqualTo("https://qr.alipay.com/verified");
    assertThat(response.expiresAt()).isAfter(LocalDateTime.now().plusMinutes(119));
}
```

- [ ] **Step 2: Add the bounded not-found replacement test**

Use an ordered in-memory mapper stub holding the selected order and both inserts:

```java
@Test
void repeatedNotFoundCreatesExactlyOneVerifiedReplacement() {
    PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
    AlipayPaymentClient client = mock(AlipayPaymentClient.class);
    PaymentRetrySleeper sleeper = mock(PaymentRetrySleeper.class);
    List<PaymentOrder> inserted = new java.util.ArrayList<>();
    AtomicReference<PaymentOrder> selected = new AtomicReference<>();
    when(mapper.insert(any())).thenAnswer(invocation -> {
        PaymentOrder value = invocation.getArgument(0);
        inserted.add(value);
        selected.set(value);
        return 1;
    });
    when(mapper.selectOne(any())).thenAnswer(invocation -> selected.get());
    when(mapper.update(any(), any())).thenAnswer(invocation -> {
        PaymentOrder update = invocation.getArgument(0);
        if (update.getStatus() != null) selected.get().setStatus(update.getStatus());
        if (update.getQrcode() != null) selected.get().setQrcode(update.getQrcode());
        return 1;
    });
    when(client.precreate(any())).thenAnswer(invocation -> {
        AlipayPrecreateRequest request = invocation.getArgument(0);
        return new AlipayPrecreateResult(
                request.orderNo(), "https://qr.alipay.com/" + request.orderNo());
    });
    AtomicInteger queries = new AtomicInteger();
    when(client.query(any())).thenAnswer(invocation -> {
        String orderNo = invocation.getArgument(0);
        return queries.incrementAndGet() <= 3
                ? new AlipayQueryResult(orderNo, null, null, null,
                        "40004", "ACQ.TRADE_NOT_EXIST")
                : new AlipayQueryResult(orderNo, null, "WAIT_BUYER_PAY",
                        new BigDecimal("50.00"));
    });
    PaymentOrderServiceImpl service = service(
            mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
            mock(MembershipService.class), client, sleeper);

    PaymentOrderCreateResponse response =
            service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

    assertThat(inserted).hasSize(2);
    assertThat(inserted.get(0).getStatus()).isEqualTo(PaymentOrderStatus.CLOSED.name());
    assertThat(response.orderNo()).isEqualTo(inserted.get(1).getOrderNo());
    assertThat(response.paymentReady()).isTrue();
    assertThat(response.qrCode()).contains(inserted.get(1).getOrderNo());
    verify(client, times(2)).precreate(any());
    verify(client, times(4)).query(any());
    verify(sleeper, times(2)).pauseBeforeQrVerificationRetry();
}
```

- [ ] **Step 3: Add the second-not-found and unknown-state safety tests**

```java
@Test
void secondNotFoundReturnsUnreadyReplacementWithoutExposingQr() {
    QrCreateFixture fixture = qrCreateFixture();
    when(fixture.client().query(any())).thenAnswer(invocation -> new AlipayQueryResult(
            invocation.getArgument(0), null, null, null,
            "40004", "ACQ.TRADE_NOT_EXIST"));

    PaymentOrderCreateResponse response = fixture.service()
            .createRechargeOrder(7L, 5000, PaymentMethod.QR);

    assertThat(fixture.inserted()).hasSize(2);
    assertThat(response.orderNo()).isEqualTo(fixture.inserted().get(1).getOrderNo());
    assertThat(response.status()).isEqualTo(PaymentOrderStatus.CREATED.name());
    assertThat(response.paymentReady()).isFalse();
    assertThat(response.qrCode()).isNull();
    assertThat(response.paymentMessage()).isEqualTo(
            "支付宝扫码支付服务暂时不可用，请稍后重试或改用支付宝收银台");
    verify(fixture.client(), times(2)).precreate(any());
    verify(fixture.client(), times(6)).query(any());
}

@Test
void unknownVerificationStateKeepsOneOrderAndNeverExposesQr() {
    QrCreateFixture fixture = qrCreateFixture();
    when(fixture.client().query(any())).thenThrow(new BusinessException("gateway unknown"));

    PaymentOrderCreateResponse response = fixture.service()
            .createRechargeOrder(7L, 5000, PaymentMethod.QR);

    assertThat(fixture.inserted()).hasSize(1);
    assertThat(response.status()).isEqualTo(PaymentOrderStatus.CREATED.name());
    assertThat(response.paymentReady()).isFalse();
    assertThat(response.qrCode()).isNull();
    assertThat(response.paymentMessage()).isEqualTo(
            "支付状态暂未确认，请稍后查询，请勿重复支付");
    verify(fixture.client()).precreate(any());
    verify(fixture.client()).query(any());
}
```

`QrCreateFixture` is a private test record with this complete factory, so both safety tests use a real conditional in-memory order lifecycle:

```java
private record QrCreateFixture(
        PaymentOrderServiceImpl service,
        AlipayPaymentClient client,
        List<PaymentOrder> inserted
) {
}

private QrCreateFixture qrCreateFixture() {
    PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
    AlipayPaymentClient client = mock(AlipayPaymentClient.class);
    PaymentRetrySleeper sleeper = mock(PaymentRetrySleeper.class);
    List<PaymentOrder> inserted = new java.util.ArrayList<>();
    AtomicReference<PaymentOrder> selected = new AtomicReference<>();
    when(mapper.insert(any())).thenAnswer(invocation -> {
        PaymentOrder value = invocation.getArgument(0);
        inserted.add(value);
        selected.set(value);
        return 1;
    });
    when(mapper.selectOne(any())).thenAnswer(invocation -> selected.get());
    when(mapper.update(any(), any())).thenAnswer(invocation -> {
        PaymentOrder update = invocation.getArgument(0);
        if (update.getStatus() != null) selected.get().setStatus(update.getStatus());
        if (update.getQrcode() != null) selected.get().setQrcode(update.getQrcode());
        selected.get().setUpdatedAt(update.getUpdatedAt());
        return 1;
    });
    when(client.precreate(any())).thenAnswer(invocation -> {
        AlipayPrecreateRequest request = invocation.getArgument(0);
        return new AlipayPrecreateResult(
                request.orderNo(), "https://qr.alipay.com/" + request.orderNo());
    });
    PaymentOrderServiceImpl service = service(
            mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
            mock(MembershipService.class), client, sleeper);
    return new QrCreateFixture(service, client, inserted);
}
```

- [ ] **Step 4: Add the paid-during-verification idempotency test**

```java
@Test
void paidDuringVerificationSettlesAndReturnsNoQrPayload() {
    PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
    AlipayPaymentClient client = mock(AlipayPaymentClient.class);
    CreditAccountService credits = mock(CreditAccountService.class);
    AtomicReference<PaymentOrder> persisted = stubOrderPersistence(mapper);
    when(client.precreate(any())).thenAnswer(invocation -> {
        AlipayPrecreateRequest request = invocation.getArgument(0);
        return new AlipayPrecreateResult(request.orderNo(), "https://qr.alipay.com/paid-race");
    });
    when(client.query(any())).thenAnswer(invocation -> new AlipayQueryResult(
            invocation.getArgument(0), "trade-paid", "TRADE_SUCCESS",
            new BigDecimal("50.00")));
    PaymentOrderServiceImpl service = service(
            mapper, mock(UserMembershipMapper.class), credits,
            mock(MembershipService.class), client, mock(PaymentRetrySleeper.class));

    PaymentOrderCreateResponse response =
            service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

    assertThat(response.status()).isEqualTo(PaymentOrderStatus.PAID.name());
    assertThat(response.paymentReady()).isFalse();
    assertThat(response.qrCode()).isNull();
    verify(credits, times(1)).grantRechargeCredits(any());
    assertThat(persisted.get().getQrcode()).isNull();
}
```

- [ ] **Step 5: Run the new tests and verify RED**

```bash
mvn -q -Dtest=PaymentOrderServiceImplTest test
```

Expected: the new tests fail because QR persistence currently happens immediately after precreate, with no authoritative query or automatic replacement.

- [ ] **Step 6: Add bounded verification outcome types inside the service**

Add these private types and constants to `PaymentOrderServiceImpl`:

```java
private static final int QR_VERIFICATION_QUERY_ATTEMPTS = 3;

private enum QrVerificationState {
    READY, PAID, NOT_FOUND, CLOSED, UNKNOWN, UNAVAILABLE
}

private record QrVerificationOutcome(
        QrVerificationState state,
        String qrCode,
        AlipayQueryResult queryResult
) {
}
```

Keep the existing public response fields as the wire-level state contract instead of adding a duplicate enum: `QR_READY` is `status=CREATED`, `paymentReady=true`, nonblank `qrCode`; `PAID` is `status=PAID`; `QR_UNAVAILABLE` is `paymentReady=false` plus `QR_UNAVAILABLE_MESSAGE`; `PAYMENT_UNKNOWN` is `paymentReady=false` plus `PAYMENT_STATE_UNCERTAIN_MESSAGE`. The tests in Steps 1-4 assert every mapping.

Remove `QR_TIMEOUT_EXPRESS`; construct `AlipayPrecreateRequest` with only order number, subject, amount and notify URL.

- [ ] **Step 7: Implement precreate followed by bounded authoritative query**

```java
private QrVerificationOutcome precreateAndVerifyQr(PaymentOrder order) {
    AlipayPrecreateResult precreateResult = precreateQrWithRetry(order);
    if (precreateResult == null || !notBlank(precreateResult.qrCode())) {
        return new QrVerificationOutcome(QrVerificationState.UNAVAILABLE, null, null);
    }
    try {
        paymentRetrySleeper.pauseBeforeQrVerification();
        for (int attempt = 1; attempt <= QR_VERIFICATION_QUERY_ATTEMPTS; attempt++) {
            AlipayQueryResult queryResult = alipayPaymentClient.query(order.getOrderNo());
            if (queryResult != null && queryResult.paid()) {
                log.info("支付宝订单码验证完成: orderNo={}, attempt={}, state=PAID",
                        order.getOrderNo(), attempt);
                return new QrVerificationOutcome(QrVerificationState.PAID, null, queryResult);
            }
            if (waitingForBuyer(queryResult)) {
                log.info("支付宝订单码验证完成: orderNo={}, attempt={}, state=WAIT_BUYER_PAY",
                        order.getOrderNo(), attempt);
                return new QrVerificationOutcome(
                        QrVerificationState.READY, precreateResult.qrCode(), queryResult);
            }
            if (tradeClosed(queryResult)) {
                return new QrVerificationOutcome(QrVerificationState.CLOSED, null, queryResult);
            }
            if (!notFound(queryResult)) {
                return new QrVerificationOutcome(QrVerificationState.UNKNOWN, null, queryResult);
            }
            if (attempt < QR_VERIFICATION_QUERY_ATTEMPTS) {
                paymentRetrySleeper.pauseBeforeQrVerificationRetry();
            }
        }
        return new QrVerificationOutcome(QrVerificationState.NOT_FOUND, null, null);
    } catch (BusinessException exception) {
        log.warn("支付宝订单码验证状态不明确: orderNo={}", order.getOrderNo());
        return new QrVerificationOutcome(QrVerificationState.UNKNOWN, null, null);
    }
}
```

- [ ] **Step 8: Route create/resume QR material through one verified handler**

Refactor timestamp initialization into:

```java
private void initializeNewOrder(PaymentOrder order) {
    LocalDateTime now = LocalDateTime.now();
    order.setExpiresAt(now.plusHours(2));
    order.setCreatedAt(now);
    order.setUpdatedAt(now);
}
```

Use the following handler after an order has been inserted, with `allowReplacement=true` for a user-created/resumed QR flow and `false` for the automatically generated replacement:

```java
private PaymentOrderCreateResponse prepareVerifiedQr(
        PaymentOrder order, boolean allowReplacement) {
    QrVerificationOutcome outcome = precreateAndVerifyQr(order);
    if (outcome.state() == QrVerificationState.READY) {
        saveQrIfCreated(order.getOrderNo(), outcome.qrCode());
        return reloadedQrCreateResponse(order.getOrderNo());
    }
    if (outcome.state() == QrVerificationState.PAID) {
        settleFromQuery(order, outcome.queryResult(), "precreate-verification");
        return toCreateResponseWithoutPaymentPayload(findOrder(order.getOrderNo()));
    }
    if (outcome.state() == QrVerificationState.CLOSED) {
        closeCreatedOrder(order.getOrderNo());
        return toCreateResponse(findOrder(order.getOrderNo()), null, false,
                QR_UNAVAILABLE_MESSAGE);
    }
    if (outcome.state() == QrVerificationState.UNKNOWN) {
        return toCreateResponse(findOrder(order.getOrderNo()), null, false,
                PAYMENT_STATE_UNCERTAIN_MESSAGE);
    }
    if (outcome.state() == QrVerificationState.NOT_FOUND && allowReplacement) {
        if (closeCreatedOrder(order.getOrderNo()) == 0) {
            return toCreateResponse(findOrder(order.getOrderNo()), null, false,
                    PAYMENT_STATE_UNCERTAIN_MESSAGE);
        }
        PaymentOrder replacement = cloneAsPaymentOrder(order, PaymentMethod.QR);
        initializeNewOrder(replacement);
        log.warn("支付宝订单码验证不存在，创建一次性替换订单: sourceOrderNo={}, replacementOrderNo={}",
                order.getOrderNo(), replacement.getOrderNo());
        paymentOrderMapper.insert(replacement);
        return prepareVerifiedQr(replacement, false);
    }
    return toCreateResponse(findOrder(order.getOrderNo()), null, false,
            QR_UNAVAILABLE_MESSAGE);
}
```

`prepareQrPayAndInsert` inserts the source exactly once and calls `prepareVerifiedQr(order, true)`. The no-QR branch of `doResumeOrder` calls the same verified handler instead of saving `precreateResult` directly. Keep `saveQrIfCreated` conditional on `status=CREATED`; do not log the QR URL.

When the verification succeeds, reset the authoritative expiry from the successful verification time rather than the earlier local insert time:

```java
private void saveQrIfCreated(String orderNo, String qrCode) {
    LocalDateTime now = LocalDateTime.now();
    PaymentOrder update = new PaymentOrder();
    update.setQrcode(qrCode);
    update.setExpiresAt(now.plusHours(2));
    update.setUpdatedAt(now);
    LambdaUpdateWrapper<PaymentOrder> wrapper = new LambdaUpdateWrapper<>();
    wrapper.eq(PaymentOrder::getOrderNo, orderNo)
            .eq(PaymentOrder::getStatus, PaymentOrderStatus.CREATED.name());
    paymentOrderMapper.update(update, wrapper);
}
```

Remove the duplicate `saveResumedQrIfCreated`; resumed QR creation now uses the same verified save and therefore the same two-hour server expiry rule.

- [ ] **Step 9: Update successful service fixtures to return matching orders and WAIT_BUYER_PAY**

Every mocked successful precreate must derive the result order number from the request:

```java
when(client.precreate(any())).thenAnswer(invocation -> {
    AlipayPrecreateRequest request = invocation.getArgument(0);
    return new AlipayPrecreateResult(request.orderNo(), "https://qr.alipay.com/example");
});
when(client.query(any())).thenAnswer(invocation -> new AlipayQueryResult(
        invocation.getArgument(0), null, "WAIT_BUYER_PAY", new BigDecimal("50.00")));
```

Where a test already queries before precreate (resume, refresh, switch), return the existing safe-action result first and the verification `WAIT_BUYER_PAY` result second using consecutive `thenReturn(...)` values. Retain the existing paid/closed/unknown result in tests that assert those branches.

- [ ] **Step 10: Run the full payment service test and verify GREEN**

```bash
mvn -q -Dtest=PaymentOrderServiceImplTest test
```

Expected: every service test passes; verified QR tests observe query before persistence; not-found replacement is bounded to two inserted orders; unknown state exposes no QR.

- [ ] **Step 11: Commit the verified QR service flow**

```bash
git add backend/src/main/java/com/manga/ai/payment/service/PaymentOrderServiceImpl.java \
  backend/src/test/java/com/manga/ai/payment/service/PaymentOrderServiceImplTest.java
git commit -m "fix: verify alipay orders before showing qr"
```

### Task 4: Show the real countdown and only the abnormal cashier fallback

**Files:**
- Modify: `frontend/apps/series/test_payment_qr.py:750-820`
- Modify: `frontend/templates/payments/membership.html:365-390`
- Modify: `frontend/static/js/payment-center.js:330-370, 950-1015`

- [ ] **Step 1: Change the display contract test to require a visible-capable countdown**

Replace the current countdown assertion and add the fallback contract:

```python
countdown_start = template.index('<p id="paymentQrCountdown"')
countdown_tag = template[countdown_start:template.index('>', countdown_start) + 1]
self.assertNotIn(' hidden', countdown_tag)

actions_start = template.index('<div id="paymentQrActions"')
actions_tag = template[actions_start:template.index('>', actions_start) + 1]
self.assertIn(' hidden', actions_tag)
```

Add a controller-source contract:

```python
def test_qr_countdown_and_cashier_fallback_are_state_driven(self):
    controller = (
        Path(settings.BASE_DIR) / 'static/js/payment-center.js'
    ).read_text(encoding='utf-8')
    self.assertIn('const showQrCountdown =', controller)
    self.assertIn('this.elements.qrCountdown.hidden = !showQrCountdown;', controller)
    self.assertIn('const showCashierFallback =', controller)
    self.assertIn('this.elements.qrActions.hidden = !showCashierFallback;', controller)
    self.assertIn('this.elements.qrCashier.hidden = !showCashierFallback;', controller)
    self.assertIn("this.elements.qrStatus.hidden = !showQrStatus;", controller)
    self.assertNotIn('正在查询支付状态', controller)
```

- [ ] **Step 2: Run the focused Django tests and verify RED**

```bash
cd frontend
/tmp/yzmovie-frontend-test-venv2/bin/python manage.py test \
  apps.series.test_payment_qr.MembershipQrPaymentTests
```

If the class name differs, run the containing `apps.series.test_payment_qr` module. Expected: failure because the countdown tag is hidden and the renderer has no state-driven visibility variables.

- [ ] **Step 3: Remove only the countdown's template-level hidden attribute**

```html
<p id="paymentQrCountdown" class="payment-center-countdown">正在读取有效期</p>
```

Keep `paymentQrStatus` and `paymentQrActions` template tags hidden.

- [ ] **Step 4: Drive countdown and fallback visibility from rendered state**

In `render()`, after computing `showQrCanvas`, add:

```javascript
const showQrCountdown = showQrCanvas
    || ui === PaymentCenterCore.UI.PAID
    || ui === PaymentCenterCore.UI.EXPIRED;
this.elements.qrCountdown.hidden = !showQrCountdown;

const showCashierFallback = order.status === 'CREATED'
    && order.paymentMethod === 'QR'
    && !order.paymentReady
    && ui === PaymentCenterCore.UI.CREATE_ERROR;
this.elements.qrActions.hidden = !showCashierFallback;
this.elements.qrSync.hidden = true;
this.elements.qrRefresh.hidden = true;
this.elements.qrRetryRender.hidden = true;
this.elements.qrOpenLink.hidden = true;
this.elements.qrCashier.hidden = !showCashierFallback;
```

Keep the existing five-second silent polling and `updateCountdown()` implementation. Do not add “正在查询支付状态” or show the manual sync button. Restrict the top method chooser to a fresh choice/error without a current order:

```javascript
this.elements.methods.hidden = !(
    ui === PaymentCenterCore.UI.CHOOSING
    || (ui === PaymentCenterCore.UI.CREATE_ERROR && !order)
);
```

- [ ] **Step 5: Run frontend tests and verify GREEN**

```bash
node --test static/js/payment-center-core.test.js
/tmp/yzmovie-frontend-test-venv2/bin/python manage.py test apps.series.test_payment_qr
```

Expected: Node and Django payment tests pass; the template exposes the countdown, while runtime state keeps it hidden until a valid QR exists and keeps all normal actions hidden.

- [ ] **Step 6: Commit the payment-page display change**

```bash
git add frontend/templates/payments/membership.html \
  frontend/static/js/payment-center.js frontend/apps/series/test_payment_qr.py
git commit -m "feat: show verified qr expiry countdown"
```

### Task 5: Full regression, review and production delivery

**Files:**
- Verify all files from this plan and `2026-08-01-first-render-static-assets.md`.
- Do not add `.claude/`, `.superpowers/`, any PID/log file, credentials, `frontend/staticfiles/`, or `backend/target/`.

- [ ] **Step 1: Run full local verification**

```bash
cd backend
mvn -q test
mvn -q -DskipTests package
cd ../frontend
npm ci
npm run build
node --test static/js/payment-center-core.test.js
/tmp/yzmovie-frontend-test-venv2/bin/python manage.py test \
  apps.auth.tests apps.role.tests apps.series.tests \
  apps.series.test_payment_qr apps.series.test_static_assets
/tmp/yzmovie-frontend-test-venv2/bin/python manage.py check
/tmp/yzmovie-frontend-test-venv2/bin/python manage.py collectstatic --noinput
cd ..
git diff --check
git status --short
```

Expected: Maven tests/package, npm build, Node tests, all Django tests/checks and collectstatic exit 0; only intended tracked files differ and existing untracked files remain untouched.

- [ ] **Step 2: Review the final diff for payment safety**

Run:

```bash
git diff origin/dev_20260629_pay...HEAD -- \
  backend/src/main/java/com/manga/ai/payment \
  backend/src/test/java/com/manga/ai/payment \
  frontend/templates frontend/static frontend/apps/series
rg -n 'app-private-key|alipay-public-key|Azkc5592255|qr\.alipay\.com/[A-Za-z0-9]+' \
  backend frontend docs/superpowers/plans
```

Expected: the diff contains no credentials or real QR tokens; the only `qr.alipay.com` values are fixed test examples; every QR response is query-verified before persistence.

- [ ] **Step 3: Commit regenerated CSS only if Task 4 changed scanned class tokens**

```bash
git add frontend/static/css/site.css
git diff --cached --quiet || git commit -m "build: refresh compiled site css"
```

Expected: either no new CSS diff exists, or exactly one generated CSS commit is created.

- [ ] **Step 4: Push the current branch**

```bash
git push origin dev_20260629_pay
```

Expected: `origin/dev_20260629_pay` advances to the tested implementation commit.

- [ ] **Step 5: Back up the running backend artifact and fast-forward the server**

Run through the already authorized Tencent Cloud terminal on `82.156.129.98`:

```bash
sudo install -d -m 700 /var/backups/manga-ai
sudo cp /root/.openclaw/workspace/manga-ai/backend/target/manga-ai-1.0.0-SNAPSHOT.jar \
  /var/backups/manga-ai/manga-ai-1.0.0-SNAPSHOT.pre-qr-reliability-20260801.jar
sudo git -C /root/.openclaw/workspace/manga-ai pull --ff-only origin dev_20260629_pay
sudo git -C /root/.openclaw/workspace/manga-ai rev-parse HEAD
```

Expected: backup exists, pull is a fast-forward, and server HEAD equals the pushed local HEAD.

- [ ] **Step 6: Build and restart backend first**

```bash
cd /root/.openclaw/workspace/manga-ai/backend
sudo mvn -q test
sudo mvn -q -DskipTests package
sudo systemctl restart manga-ai-backend.service
sudo systemctl is-active manga-ai-backend.service
sudo journalctl -u manga-ai-backend.service -n 80 --no-pager
```

Expected: Maven exits 0, service is `active`, and recent logs contain no startup, Flyway, database or Alipay configuration errors.

- [ ] **Step 7: Collect and restart the public frontend**

```bash
sudo /usr/bin/python3 /root/.openclaw/workspace/manga-ai/frontend/manage.py collectstatic --noinput
sudo systemctl restart manga-ai-frontend.service
sudo systemctl is-active manga-ai-frontend.service
```

Expected: collectstatic succeeds and frontend service is `active`.

- [ ] **Step 8: Verify public HTTP and cold rendering without creating a live order**

```bash
curl -fsS https://www.yzmovie.cn/ -o /tmp/yzmovie-home.html
curl -fsS https://www.yzmovie.cn/auth/login/ -o /tmp/yzmovie-login.html
rg -n '/static/css/site\.css|/static/vendor/lucide/lucide\.min\.js' \
  /tmp/yzmovie-home.html /tmp/yzmovie-login.html
! rg -n 'cdn\.tailwindcss\.com|fonts\.googleapis\.com|unpkg\.com/lucide' \
  /tmp/yzmovie-home.html /tmp/yzmovie-login.html
curl -fsSI https://www.yzmovie.cn/static/css/site.css
```

Expected: pages and local CSS return HTTP 200; HTML references local versioned assets and contains no removed external dependencies. In a cold-cache throttled browser, the homepage, login page and membership page paint without raw stacked text.

Inspect the membership template served to an authenticated session without clicking a purchase button. Expected: normal page has no extra payment action; once the user voluntarily creates a verified QR order, the panel shows QR, `二维码有效期 HH:MM:SS`, and order number only. No automated verification step creates a real Alipay order or initiates a charge.

- [ ] **Step 9: Observe logs and hand off a user-initiated payment check**

```bash
sudo tail -n 120 /var/log/manga-ai-backend.log
```

Expected: no full QR URL, secret or signing material is logged. Ask the user to initiate one small recharge; confirm the new order is query-verified as `WAIT_BUYER_PAY`, a visible countdown appears, and successful payment settles once. If Alipay reports not found, confirm at most one replacement order is generated and no invalid QR reaches the browser.
