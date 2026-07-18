# Alipay Recoverable Payment Center Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复生产二维码脚本缺失和错误调用，并交付一个不会在下单期间消失、可恢复待支付订单的支付宝统一支付中心。

**Architecture:** Java 后端继续拥有订单、金额、支付宝查询和幂等结算，新增瞬时网关错误分类、同一订单号三次短重试、可恢复预下单、待支付列表与安全切换收银台接口。Django 只做代理；浏览器用独立的二维码核心模块和支付中心状态机区分“订单业务状态”与“二维码渲染状态”，头像旁的订单入口负责重新打开待支付订单。

**Tech Stack:** Java 17、Spring Boot 3.2、MyBatis-Plus、支付宝 Java SDK、JUnit 5/Mockito、Django 4.2、原生 JavaScript、Node `node:test`、`qrcode@1.5.3`、Lucide、CSS。

---

## File Map

### Backend

- Create `backend/src/main/java/com/manga/ai/payment/exception/RetryablePaymentGatewayException.java`: 表示必须保留原商户订单号处理的支付宝瞬时网络异常。
- Create `backend/src/main/java/com/manga/ai/payment/service/PaymentRetrySleeper.java`: 封装 1 秒、2 秒短退避，测试中可替换。
- Create `backend/src/main/java/com/manga/ai/payment/dto/PendingPaymentOrderResponse.java`: 待支付列表的最小安全 DTO。
- Modify `backend/src/main/java/com/manga/ai/payment/client/DefaultAlipayPaymentClient.java`: 分类预下单异常并校验二维码域名。
- Modify `backend/src/main/java/com/manga/ai/payment/dto/AlipayQueryResult.java`: 区分支付宝确认订单不存在与状态未知。
- Modify `backend/src/main/java/com/manga/ai/payment/dto/PaymentOrderCreateResponse.java`: 增加 `paymentReady` 和安全的恢复提示。
- Modify `backend/src/main/java/com/manga/ai/payment/service/PaymentOrderService.java`: 增加待支付列表和恢复订单契约。
- Modify `backend/src/main/java/com/manga/ai/payment/service/PaymentOrderServiceImpl.java`: 同订单号重试、预下单前落库、列表查询、恢复、安全切换与过期处理。
- Modify `backend/src/main/java/com/manga/ai/payment/controller/PaymentController.java`: 暴露待支付、恢复和安全切换端点。
- Modify `backend/src/test/java/com/manga/ai/payment/client/DefaultAlipayPaymentClientTest.java`: 锁定异常分类与 QR 域名规则。
- Modify `backend/src/test/java/com/manga/ai/payment/service/PaymentOrderServiceImplTest.java`: 锁定三次重试、列表、恢复、并发安全。
- Create `backend/src/test/java/com/manga/ai/payment/controller/PaymentControllerTest.java`: 锁定三个新端点与登录用户透传。

### Frontend

- Create `frontend/static/js/payment-center-core.js`: 无 DOM 框架依赖的状态常量、支付宝 QR URL 校验和 `toCanvas` 调用封装。
- Create `frontend/static/js/payment-center-core.test.js`: 使用 Node 原生测试准确验证 Canvas 参数顺序和 URL 白名单。
- Create `frontend/static/js/payment-center.js`: 统一支付中心状态机、轮询、恢复与收银台提交。
- Create `frontend/static/css/payment-center.css`: 桌面右侧侧栏和移动端全屏布局。
- Modify `frontend/templates/base.html`: 头像旁订单入口、待支付角标和全站计数刷新。
- Modify `frontend/templates/payments/membership.html`: 用统一支付中心替换两个旧弹窗，并注入产品购买数据。
- Modify `frontend/apps/series/views.py`: 增加待支付列表、恢复与安全切换代理。
- Modify `frontend/apps/series/urls.py`: 增加代理路由，静态路由必须位于动态订单号路由之前。
- Modify `frontend/apps/series/test_payment_qr.py`: 测试代理、模板结构、状态隔离和本地静态资源。
- Modify `frontend/apps/series/tests.py`: 更新原支付模板契约断言。

### Deployment

- Review `frontend/requirements.txt`: 确认不增加二维码运行时依赖；本计划不修改该文件。
- Verify `frontend/static/vendor/qrcode/qrcode.min.js`: 文件必须保留为 `qrcode@1.5.3`，不改动供应商文件。
- Verify `frontend/staticfiles/`: 由 `collectstatic` 生成，不提交 Git。

---

### Task 0: Confirm Branch and Green Baseline

**Files:**
- Review only; do not modify or stage untracked files.

- [ ] **Step 1: Confirm the branch contains the remote tip**

Run:

```bash
git branch --show-current
git status --short --branch
git fetch origin
git merge-base --is-ancestor origin/dev_20260629_pay HEAD
```

Expected: branch is `dev_20260629_pay`; the ancestor command exits 0. Known untracked `.claude/`, `.superpowers/` and PID files remain untouched. If the ancestor check fails because the remote advanced, stop and reconcile the remote commits before implementation; do not force-push or discard local documentation commits.

- [ ] **Step 2: Run the existing backend baseline**

Run:

```bash
cd backend
mvn -q test
```

Expected: 0 failures and 0 errors before the first implementation edit.

- [ ] **Step 3: Run the existing frontend baseline**

Run:

```bash
cd frontend
python3 manage.py test apps.auth.tests apps.role.tests apps.series.tests apps.series.test_payment_qr
```

Expected: all existing Django tests pass. Record any pre-existing failure and resolve the baseline before attributing it to this plan.

---

### Task 1: Classify Retryable Alipay Precreate Failures

**Files:**
- Create: `backend/src/main/java/com/manga/ai/payment/exception/RetryablePaymentGatewayException.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/client/DefaultAlipayPaymentClient.java`
- Test: `backend/src/test/java/com/manga/ai/payment/client/DefaultAlipayPaymentClientTest.java`

- [ ] **Step 1: Write failing client tests**

Add tests that distinguish transient transport failures from business errors and reject untrusted QR links:

```java
@Test
void precreateMapsGatewayTimeoutToRetryableException() throws AlipayApiException {
    AlipayClient sdkClient = mock(AlipayClient.class);
    when(sdkClient.execute(any(AlipayTradePrecreateRequest.class)))
            .thenThrow(new AlipayApiException("504 Gateway Time-out"));

    assertThatThrownBy(() -> paymentClient(sdkClient).precreate(precreateRequest()))
            .isInstanceOf(RetryablePaymentGatewayException.class)
            .hasMessage("支付宝扫码支付预下单暂时不可用");
}

@Test
void precreateDoesNotMarkBusinessErrorRetryable() throws AlipayApiException {
    AlipayClient sdkClient = mock(AlipayClient.class);
    AlipayTradePrecreateResponse response = new AlipayTradePrecreateResponse();
    response.setCode("40004");
    response.setSubCode("ACQ.ACCESS_FORBIDDEN");
    when(sdkClient.execute(any(AlipayTradePrecreateRequest.class))).thenReturn(response);

    assertThatThrownBy(() -> paymentClient(sdkClient).precreate(precreateRequest()))
            .isInstanceOf(BusinessException.class)
            .isNotInstanceOf(RetryablePaymentGatewayException.class)
            .hasMessageContaining("改用支付宝收银台");
}

@Test
void precreateRejectsQrCodeOutsideOfficialAlipayHost() throws AlipayApiException {
    AlipayClient sdkClient = mock(AlipayClient.class);
    AlipayTradePrecreateResponse response = new AlipayTradePrecreateResponse();
    response.setCode("10000");
    response.setQrCode("https://qr.alipay.com.evil.example/pay");
    when(sdkClient.execute(any(AlipayTradePrecreateRequest.class))).thenReturn(response);

    assertThatThrownBy(() -> paymentClient(sdkClient).precreate(precreateRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("支付宝扫码支付二维码地址无效，请改用支付宝收银台");
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd backend
mvn -q -Dtest=DefaultAlipayPaymentClientTest test
```

Expected: compilation fails because `RetryablePaymentGatewayException` does not exist, or the timeout test receives ordinary `BusinessException`.

- [ ] **Step 3: Add the typed exception**

Create:

```java
package com.manga.ai.payment.exception;

import com.manga.ai.common.exception.BusinessException;

public final class RetryablePaymentGatewayException extends BusinessException {

    public RetryablePaymentGatewayException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}
```

- [ ] **Step 4: Classify transport errors and validate returned QR URLs**

In `DefaultAlipayPaymentClient`, add exact-host validation and timeout classification:

```java
private boolean isOfficialQrCode(String value) {
    try {
        URI uri = URI.create(value);
        return "https".equalsIgnoreCase(uri.getScheme())
                && "qr.alipay.com".equalsIgnoreCase(uri.getHost())
                && uri.getUserInfo() == null;
    } catch (IllegalArgumentException e) {
        return false;
    }
}

private boolean isRetryablePrecreateException(AlipayApiException exception) {
    String diagnostic = String.join(" ",
            String.valueOf(exception.getErrCode()),
            String.valueOf(exception.getErrMsg()),
            String.valueOf(exception.getMessage())).toLowerCase(Locale.ROOT);
    return List.of(
            "502", "503", "504", "gateway time-out", "gateway timeout",
            "timed out", "timeout", "connection reset", "connection refused"
    ).stream().anyMatch(diagnostic::contains);
}
```

After checking `response.getQrCode()` is nonblank, reject it unless `isOfficialQrCode(...)` is true. In the `AlipayApiException` catch block, throw `RetryablePaymentGatewayException` only when `isRetryablePrecreateException(e)` is true; preserve the current safe `BusinessException` for all other errors.

- [ ] **Step 5: Run the client tests and verify GREEN**

Run:

```bash
cd backend
mvn -q -Dtest=DefaultAlipayPaymentClientTest test
```

Expected: all `DefaultAlipayPaymentClientTest` tests pass; logs contain no QR string or credential.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/manga/ai/payment/exception/RetryablePaymentGatewayException.java \
        backend/src/main/java/com/manga/ai/payment/client/DefaultAlipayPaymentClient.java \
        backend/src/test/java/com/manga/ai/payment/client/DefaultAlipayPaymentClientTest.java
git commit -m "fix: classify transient alipay precreate failures"
```

---

### Task 2: Persist and Retry One Recoverable QR Order

**Files:**
- Create: `backend/src/main/java/com/manga/ai/payment/service/PaymentRetrySleeper.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/dto/PaymentOrderCreateResponse.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/service/PaymentOrderServiceImpl.java`
- Test: `backend/src/test/java/com/manga/ai/payment/service/PaymentOrderServiceImplTest.java`

- [ ] **Step 1: Write failing retry and persistence tests**

Add a mocked `PaymentRetrySleeper` to the test service factory. Lock these contracts:

```java
@Test
void qrPrecreateRetriesSamePersistedOrderNumberAfterTransientFailure() {
    PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
    AlipayPaymentClient client = mock(AlipayPaymentClient.class);
    PaymentRetrySleeper sleeper = mock(PaymentRetrySleeper.class);
    when(client.precreate(any()))
            .thenThrow(new RetryablePaymentGatewayException("temporary", new RuntimeException("504")))
            .thenReturn(new AlipayPrecreateResult("https://qr.alipay.com/example"));
    PaymentOrderServiceImpl service = service(mapper, client, sleeper);

    PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

    ArgumentCaptor<PaymentOrder> inserted = ArgumentCaptor.forClass(PaymentOrder.class);
    ArgumentCaptor<AlipayPrecreateRequest> requests = ArgumentCaptor.forClass(AlipayPrecreateRequest.class);
    InOrder calls = inOrder(mapper, client);
    calls.verify(mapper).insert(inserted.capture());
    calls.verify(client, times(2)).precreate(requests.capture());
    assertThat(requests.getAllValues()).extracting(AlipayPrecreateRequest::orderNo)
            .containsOnly(inserted.getValue().getOrderNo());
    assertThat(response.orderNo()).isEqualTo(inserted.getValue().getOrderNo());
    assertThat(response.paymentReady()).isTrue();
    verify(sleeper).pauseBeforeAttempt(2);
}

@Test
void qrPrecreateExhaustionReturnsRecoverablePersistedOrder() {
    PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
    AlipayPaymentClient client = mock(AlipayPaymentClient.class);
    PaymentRetrySleeper sleeper = mock(PaymentRetrySleeper.class);
    when(client.precreate(any())).thenThrow(
            new RetryablePaymentGatewayException("temporary", new RuntimeException("504")));
    PaymentOrderServiceImpl service = service(mapper, client, sleeper);

    PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000, PaymentMethod.QR);

    ArgumentCaptor<AlipayPrecreateRequest> requests = ArgumentCaptor.forClass(AlipayPrecreateRequest.class);
    verify(client, times(3)).precreate(requests.capture());
    assertThat(requests.getAllValues()).extracting(AlipayPrecreateRequest::orderNo).hasSize(3);
    assertThat(requests.getAllValues().stream().map(AlipayPrecreateRequest::orderNo).distinct()).hasSize(1);
    assertThat(response.status()).isEqualTo("CREATED");
    assertThat(response.paymentReady()).isFalse();
    assertThat(response.qrCode()).isNull();
    assertThat(response.paymentMessage()).contains("稍后重试");
    verify(mapper).insert(any(PaymentOrder.class));
    verify(sleeper).pauseBeforeAttempt(2);
    verify(sleeper).pauseBeforeAttempt(3);
}
```

The first assertion block intentionally verifies one distinct order number across all attempts. Also add tests that:

1. A non-retryable `BusinessException` calls `precreate` once, closes the inserted local order with `CREATED -> CLOSED`, and rethrows the safe message.
2. QR success conditionally updates only `qrcode` and `updated_at` for the same `CREATED` order.
3. PAGE payment still calls `pagePay` before one insert and returns `paymentReady=true`.

- [ ] **Step 2: Run the service test and verify RED**

Run:

```bash
cd backend
mvn -q -Dtest=PaymentOrderServiceImplTest test
```

Expected: compilation fails because `PaymentRetrySleeper` and the two response fields do not exist.

- [ ] **Step 3: Add the injectable sleeper**

Create:

```java
package com.manga.ai.payment.service;

import com.manga.ai.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class PaymentRetrySleeper {

    public void pauseBeforeAttempt(int nextAttempt) {
        long baseMillis = nextAttempt == 2 ? 1000L : 2000L;
        long delayMillis = baseMillis + ThreadLocalRandom.current().nextLong(0L, 251L);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("支付宝扫码支付请求已中断，请重新尝试");
        }
    }
}
```

- [ ] **Step 4: Extend the create response without changing business status**

Append to `PaymentOrderCreateResponse`:

```java
boolean paymentReady,
String paymentMessage
```

`status` remains the authoritative order status. `paymentReady=false` means the local order exists but payment material is not ready; it must never be translated to `FAILED` by the browser.

- [ ] **Step 5: Persist QR orders before the network call**

Remove `@Transactional` from both create methods so the 1/2-second waits do not hold a database transaction. Keep PAGE behavior as “generate form, then insert”. For QR:

1. Populate immutable amount, product, credit and membership snapshot fields.
2. Set `CREATED`, `expiresAt`, timestamps and insert once before calling Alipay.
3. Call `precreateQrWithRetry(order)` with the same `order.getOrderNo()` on every attempt.
4. On success, conditionally update `qrcode` where `order_no` matches and status is still `CREATED`.
5. On retry exhaustion, leave `qrcode` null and return `paymentReady=false` with a user-safe message.
6. On a non-retryable business error, conditionally close the local order and rethrow; do not leave an unusable order in the pending list.
7. Reload after the conditional QR update; if a concurrent callback already made the order terminal, return that terminal state with both payment payloads stripped.

Use this retry shape:

```java
private AlipayPrecreateResult precreateQrWithRetry(PaymentOrder order) {
    for (int attempt = 1; attempt <= 3; attempt++) {
        try {
            return alipayPaymentClient.precreate(precreateRequest(order));
        } catch (RetryablePaymentGatewayException exception) {
            log.warn("支付宝预下单暂时失败: orderNo={}, attempt={}/3",
                    order.getOrderNo(), attempt);
            if (attempt == 3) {
                return null;
            }
            paymentRetrySleeper.pauseBeforeAttempt(attempt + 1);
        }
    }
    return null;
}
```

Do not log the exception message, response body or QR string. This same-ID rule follows the [支付宝订单码注意事项](https://ideservice.alipay.com/cms/site/0izcs4), which requires confirming the previous payment result before changing `out_trade_no`; the later safe-switch flow performs that query/cancel gate explicitly.

- [ ] **Step 6: Update existing response constructors and service fixtures**

Update every `new PaymentOrderCreateResponse(...)` call. Pass `mock(PaymentRetrySleeper.class)` from the shared service fixture and every direct `new PaymentOrderServiceImpl(...)` call. Do not add real sleeps to tests.

- [ ] **Step 7: Run service and client tests**

Run:

```bash
cd backend
mvn -q -Dtest=PaymentOrderServiceImplTest,DefaultAlipayPaymentClientTest test
```

Expected: all selected tests pass; retry tests observe one persisted order number across exactly 2 or 3 calls.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/manga/ai/payment/service/PaymentRetrySleeper.java \
        backend/src/main/java/com/manga/ai/payment/dto/PaymentOrderCreateResponse.java \
        backend/src/main/java/com/manga/ai/payment/service/PaymentOrderServiceImpl.java \
        backend/src/test/java/com/manga/ai/payment/service/PaymentOrderServiceImplTest.java
git commit -m "fix: preserve recoverable alipay qr orders"
```

---

### Task 3: Add Pending, Resume, and Safe Cashier-Switch Services

**Files:**
- Create: `backend/src/main/java/com/manga/ai/payment/dto/PendingPaymentOrderResponse.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/dto/AlipayQueryResult.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/client/DefaultAlipayPaymentClient.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/service/PaymentOrderService.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/service/PaymentOrderServiceImpl.java`
- Test: `backend/src/test/java/com/manga/ai/payment/client/DefaultAlipayPaymentClientTest.java`
- Test: `backend/src/test/java/com/manga/ai/payment/service/PaymentOrderServiceImplTest.java`

- [ ] **Step 1: Write failing pending-list tests**

Return both ready QR orders and recoverable QR orders whose `qrcode` is null. Capture the `LambdaQueryWrapper` and assert its SQL segment contains user, `CREATED`, expiry, descending creation order and `LIMIT 20`. Also test `null` user receives “请先登录”.

```java
assertThat(result).extracting(PendingPaymentOrderResponse::paymentReady)
        .containsExactly(true, false);
assertThat(result).allSatisfy(item -> {
    assertThat(item.orderNo()).startsWith("PAY-");
    assertThat(item.amountCents()).isEqualTo(5000);
    assertThat(item.paymentMethod()).isEqualTo("QR");
});
```

- [ ] **Step 2: Write failing resume tests**

Cover stored QR, missing QR, paid, closed, expired and ownership outcomes. The missing-QR case must reuse the original order number:

```java
@Test
void resumeQrWithoutMaterialPrecreatesTheSameOrderNumber() {
    PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
    AlipayPaymentClient client = mock(AlipayPaymentClient.class);
    PaymentOrder order = pendingRechargeOrder("PAY-RESUME-SAME");
    order.setQrcode(null);
    when(mapper.selectOne(any())).thenReturn(order);
    when(client.query(order.getOrderNo())).thenReturn(
            new AlipayQueryResult(order.getOrderNo(), null, null, null,
                    "40004", "ACQ.TRADE_NOT_EXIST"));
    when(client.precreate(any())).thenReturn(
            new AlipayPrecreateResult("https://qr.alipay.com/resume"));
    PaymentOrderServiceImpl service = service(mapper, client, mock(PaymentRetrySleeper.class));

    PaymentOrderCreateResponse response = service.resumeOrder(7L, order.getOrderNo());

    verify(client).precreate(argThat(request -> request.orderNo().equals(order.getOrderNo())));
    assertThat(response.orderNo()).isEqualTo(order.getOrderNo());
    assertThat(response.paymentReady()).isTrue();
}
```

For a QR order with a stored QR and `WAIT_BUYER_PAY`, assert resume returns it without another `precreate`. For retry exhaustion, assert the same order remains `CREATED`, `paymentReady=false`, and no second row is inserted.

- [ ] **Step 3: Write failing safe-switch tests**

Add cases for all authoritative outcomes:

1. `TRADE_SUCCESS`: settle existing order, return `PAID`, never call `pagePay`.
2. `TRADE_CLOSED`: close local order, then create one PAGE order from the original server snapshot.
3. `ACQ.TRADE_NOT_EXIST`: close local order, then create PAGE safely.
4. `WAIT_BUYER_PAY`: call `cancel`; create PAGE only when cancel is successful and `retryRequired=false`.
5. Unknown query or retry-required cancel: leave QR order `CREATED`, throw a safe “请勿重复支付” message, never call `pagePay`.
6. A callback winning the race before the conditional close: reload `PAID` and do not create PAGE.
7. Wrong-owner, PAGE-source and unsupported terminal orders are rejected without query, cancel or new order creation.

Add expiry regressions for a recoverable QR order whose QR was never generated: `ACQ.TRADE_NOT_EXIST` closes it locally without cancel, while an unknown query result stays available for the scheduled retry and never grants benefits.

Capture `AlipayPagePayRequest` and assert amount and subject come from the stored order, never from request parameters.

- [ ] **Step 4: Run service tests and verify RED**

Run:

```bash
cd backend
mvn -q -Dtest=PaymentOrderServiceImplTest,DefaultAlipayPaymentClientTest test
```

Expected: the pending DTO, query classification and service methods are missing.

- [ ] **Step 5: Add query classification and pending DTO**

Extend `AlipayQueryResult` while preserving a four-argument convenience constructor:

```java
public record AlipayQueryResult(
        String orderNo,
        String alipayTradeNo,
        String tradeStatus,
        BigDecimal totalAmount,
        String code,
        String subCode
) {
    public AlipayQueryResult(String orderNo, String alipayTradeNo,
                             String tradeStatus, BigDecimal totalAmount) {
        this(orderNo, alipayTradeNo, tradeStatus, totalAmount, null, null);
    }

    public boolean notFound() {
        return "ACQ.TRADE_NOT_EXIST".equals(subCode);
    }
}
```

Populate `code` and `subCode` from every non-success query response. Do not treat arbitrary non-success responses as “not found”. Add `paymentReady` to the pending DTO:

```java
public record PendingPaymentOrderResponse(
        String orderNo,
        String orderType,
        String productName,
        int amountCents,
        String paymentMethod,
        boolean paymentReady,
        String status,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {
}
```

- [ ] **Step 6: Add service contracts and pending query**

Add:

```java
List<PendingPaymentOrderResponse> pendingOrders(Long userId);
PaymentOrderCreateResponse resumeOrder(Long userId, String orderNo);
PaymentOrderCreateResponse switchToPage(Long userId, String orderNo);
```

Query current-user, non-expired `CREATED` orders in descending creation order with `LIMIT 20`. Map `paymentReady=true` for PAGE orders and QR orders with a nonblank `qrcode`. The existing `idx_payment_user_status (user_id, status, created_at)` is already present; do not add a duplicate index.

- [ ] **Step 7: Implement authoritative resume**

`resumeOrder` must:

1. Validate login and ownership.
2. Return payment payloads as null for an already terminal local order.
3. Query Alipay before restoring a `CREATED` order.
4. Settle paid results and close `TRADE_CLOSED` results through existing conditional transitions.
5. For expired QR, perform the existing final query/cancel path and return no old QR.
6. For QR with a stored QR, return it only while the order remains `CREATED` and unexpired.
7. For QR with no stored QR, call `precreateQrWithRetry(order)` only when Alipay says `WAIT_BUYER_PAY` or `ACQ.TRADE_NOT_EXIST`; keep the same order number, conditionally save the returned QR, and return `paymentReady=false` on transient exhaustion.
8. For an unknown query result, keep the order and return/throw a recoverable safe message; do not create another order.
9. For PAGE, regenerate the safe form from the stored amount and product snapshot.

Update `processExpiredQrOrder` to close an `ACQ.TRADE_NOT_EXIST` local order directly. Keep unknown results retryable; paid settlement and waiting-order cancellation retain the existing conditional transition rules.

Extract `findOwnedOrder`, `pagePayRequest`, `saveQrIfCreated`, and `toCreateResponseWithoutPaymentPayload` to avoid duplicating ownership and payload-stripping rules.

- [ ] **Step 8: Implement safe switch to PAGE**

`switchToPage` must first call the authoritative query flow for the owned QR order. If it is still waiting, call cancel. Continue only after `TRADE_CLOSED`, `ACQ.TRADE_NOT_EXIST`, or a definitive successful cancel. Then:

1. Conditionally update original `CREATED -> CLOSED`.
2. Reload the original order; if a concurrent callback changed it to `PAID`, return paid with no payment payload.
3. Clone product code/name, amount, credits and membership fields into a new PAGE order; do not accept any amount or benefit input from the browser.
4. Generate the PAGE form and insert the new order once.

If query/cancel is uncertain, keep the original QR order and throw “支付状态暂未确认，请稍后查询，请勿重复支付”.

- [ ] **Step 9: Run service tests and verify GREEN**

Run:

```bash
cd backend
mvn -q -Dtest=PaymentOrderServiceImplTest,PaymentOrderExpiryTaskTest,DefaultAlipayPaymentClientTest test
```

Expected: all selected tests pass; resume never changes the order number, and PAGE fallback is impossible until the QR order is definitively non-payable.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/manga/ai/payment/dto/PendingPaymentOrderResponse.java \
        backend/src/main/java/com/manga/ai/payment/dto/AlipayQueryResult.java \
        backend/src/main/java/com/manga/ai/payment/client/DefaultAlipayPaymentClient.java \
        backend/src/main/java/com/manga/ai/payment/service/PaymentOrderService.java \
        backend/src/main/java/com/manga/ai/payment/service/PaymentOrderServiceImpl.java \
        backend/src/test/java/com/manga/ai/payment/client/DefaultAlipayPaymentClientTest.java \
        backend/src/test/java/com/manga/ai/payment/service/PaymentOrderServiceImplTest.java
git commit -m "feat: safely restore and switch payment orders"
```

---

### Task 4: Expose Backend and Django Proxy Endpoints

**Files:**
- Modify: `backend/src/main/java/com/manga/ai/payment/controller/PaymentController.java`
- Create: `backend/src/test/java/com/manga/ai/payment/controller/PaymentControllerTest.java`
- Modify: `frontend/apps/series/views.py`
- Modify: `frontend/apps/series/urls.py`
- Test: `frontend/apps/series/test_payment_qr.py`

- [ ] **Step 1: Write failing backend controller tests**

Use `MockMvcBuilders.standaloneSetup` with a mocked service and set/clear `UserContextHolder` around each test. Verify:

```java
mockMvc.perform(get("/v1/payments/orders/pending"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].orderNo").value("PAY-PENDING"));

mockMvc.perform(post("/v1/payments/orders/PAY-PENDING/resume"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.paymentMethod").value("QR"));

mockMvc.perform(post("/v1/payments/orders/PAY-PENDING/switch-to-page"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.paymentMethod").value("PAGE"));

verify(paymentOrderService).pendingOrders(7L);
verify(paymentOrderService).resumeOrder(7L, "PAY-PENDING");
verify(paymentOrderService).switchToPage(7L, "PAY-PENDING");
```

- [ ] **Step 2: Run controller test and verify RED**

Run:

```bash
cd backend
mvn -q -Dtest=PaymentControllerTest test
```

Expected: 404 for all three new routes.

- [ ] **Step 3: Add controller routes**

Add before the dynamic status method:

```java
@GetMapping("/orders/pending")
public Result<List<PendingPaymentOrderResponse>> pendingOrders() {
    return Result.success(paymentOrderService.pendingOrders(UserContextHolder.getUserId()));
}

@PostMapping("/orders/{orderNo}/resume")
public Result<PaymentOrderCreateResponse> resumeOrder(@PathVariable String orderNo) {
    return Result.success(paymentOrderService.resumeOrder(UserContextHolder.getUserId(), orderNo));
}

@PostMapping("/orders/{orderNo}/switch-to-page")
public Result<PaymentOrderCreateResponse> switchToPage(@PathVariable String orderNo) {
    return Result.success(paymentOrderService.switchToPage(UserContextHolder.getUserId(), orderNo));
}
```

- [ ] **Step 4: Write failing Django proxy tests**

Add tests that set `session['token']`, mock `BackendClient`, and assert:

```python
backend_client.get.assert_called_once_with('/v1/payments/orders/pending')
backend_client.post.assert_called_once_with('/v1/payments/orders/PAY-PENDING/resume')
backend_client.post.assert_called_once_with('/v1/payments/orders/PAY-PENDING/switch-to-page')
```

Use separate mock/test cases for the two POST assertions. Also verify missing backend auth returns the existing error envelope rather than leaking a traceback.

- [ ] **Step 5: Run Django tests and verify RED**

Run:

```bash
cd frontend
python3 manage.py test apps.series.test_payment_qr
```

Expected: 404 because the three proxy paths do not exist.

- [ ] **Step 6: Add Django proxy views and routes**

Implement:

```python
def payment_pending_orders(request):
    client = get_client(request)
    try:
        result = client.get('/v1/payments/orders/pending')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': e.status_code, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def payment_order_resume(request, order_no):
    client = get_client(request)
    try:
        result = client.post(f'/v1/payments/orders/{order_no}/resume')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': e.status_code, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def payment_order_switch_to_page(request, order_no):
    client = get_client(request)
    try:
        result = client.post(f'/v1/payments/orders/{order_no}/switch-to-page')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': e.status_code, 'message': e.message}, status=400)
```

Add URL patterns in this exact order:

```python
path('api/payments/orders/pending/', views.payment_pending_orders, name='payment_pending_orders'),
path('api/payments/orders/<str:order_no>/resume/', views.payment_order_resume, name='payment_order_resume'),
path('api/payments/orders/<str:order_no>/switch-to-page/', views.payment_order_switch_to_page,
     name='payment_order_switch_to_page'),
path('api/payments/orders/<str:order_no>/', views.payment_order_status, name='payment_order_status'),
```

- [ ] **Step 7: Run backend and Django endpoint tests**

Run:

```bash
cd backend
mvn -q -Dtest=PaymentControllerTest,PaymentOrderServiceImplTest test
cd ../frontend
python3 manage.py test apps.series.test_payment_qr
```

Expected: all selected tests pass.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/manga/ai/payment/controller/PaymentController.java \
        backend/src/test/java/com/manga/ai/payment/controller/PaymentControllerTest.java \
        frontend/apps/series/views.py frontend/apps/series/urls.py \
        frontend/apps/series/test_payment_qr.py
git commit -m "feat: expose safe payment recovery api"
```

---

### Task 5: Add a Testable QR Rendering Core

**Files:**
- Create: `frontend/static/js/payment-center-core.js`
- Create: `frontend/static/js/payment-center-core.test.js`
- Modify: `frontend/apps/series/test_payment_qr.py`

- [ ] **Step 1: Write the failing Node tests**

Create `payment-center-core.test.js`:

```javascript
const test = require('node:test');
const assert = require('node:assert/strict');
const core = require('./payment-center-core.js');

test('renderQr calls qrcode 1.5.3 with canvas, text, options', async () => {
    const canvas = {id: 'paymentQrCanvas'};
    const options = {width: 232, margin: 1};
    let received;
    const api = {toCanvas: async (...args) => { received = args; }};

    await core.renderQr(api, canvas, 'https://qr.alipay.com/example', options);

    assert.deepEqual(received, [canvas, 'https://qr.alipay.com/example', options]);
});

test('trustedQrUrl accepts only exact https qr.alipay.com host', () => {
    assert.equal(core.isTrustedQrUrl('https://qr.alipay.com/example'), true);
    assert.equal(core.isTrustedQrUrl('http://qr.alipay.com/example'), false);
    assert.equal(core.isTrustedQrUrl('https://qr.alipay.com.evil.example/x'), false);
    assert.equal(core.isTrustedQrUrl('https://qr.alipay.com@evil.example/x'), false);
});

test('render failure does not change the business order status', () => {
    const state = core.withRenderFailure({ui: 'QR_READY', orderStatus: 'CREATED'}, 'asset-missing');
    assert.equal(state.ui, 'QR_RENDER_ERROR');
    assert.equal(state.orderStatus, 'CREATED');
    assert.equal(state.renderError, 'asset-missing');
});
```

- [ ] **Step 2: Run Node tests and verify RED**

Run:

```bash
cd frontend
node --test static/js/payment-center-core.test.js
```

Expected: `MODULE_NOT_FOUND` for `payment-center-core.js`.

- [ ] **Step 3: Implement the UMD-compatible core**

Create a browser-and-Node compatible module:

```javascript
(function (root, factory) {
    const api = factory();
    if (typeof module === 'object' && module.exports) module.exports = api;
    root.PaymentCenterCore = api;
}(typeof globalThis !== 'undefined' ? globalThis : window, function () {
    const UI = Object.freeze({
        IDLE: 'IDLE', CHOOSING: 'CHOOSING', CREATING: 'CREATING',
        CREATE_ERROR: 'CREATE_ERROR', QR_READY: 'QR_READY',
        QR_RENDER_ERROR: 'QR_RENDER_ERROR', QUERYING: 'QUERYING',
        PAID: 'PAID', EXPIRED: 'EXPIRED', PAGE_REDIRECTING: 'PAGE_REDIRECTING'
    });

    function isTrustedQrUrl(value) {
        try {
            const url = new URL(value);
            return url.protocol === 'https:' && url.hostname === 'qr.alipay.com'
                && !url.username && !url.password;
        } catch (error) {
            return false;
        }
    }

    async function renderQr(qrApi, canvas, text, options) {
        if (!qrApi || typeof qrApi.toCanvas !== 'function') throw new Error('asset-missing');
        if (!isTrustedQrUrl(text)) throw new Error('untrusted-qr-url');
        await qrApi.toCanvas(canvas, text, options);
    }

    function withRenderFailure(state, message) {
        return {...state, ui: UI.QR_RENDER_ERROR, renderError: message};
    }

    return {UI, isTrustedQrUrl, renderQr, withRenderFailure};
}));
```

- [ ] **Step 4: Add static-file tests**

In Django tests, use `django.contrib.staticfiles.finders.find` and assert the two files available at this task exist:

```python
self.assertIsNotNone(finders.find('vendor/qrcode/qrcode.min.js'))
self.assertIsNotNone(finders.find('js/payment-center-core.js'))
```

Task 8 adds the `payment-center.js` assertion after that file exists.

- [ ] **Step 5: Run Node and Django tests**

Run:

```bash
cd frontend
node --test static/js/payment-center-core.test.js
python3 manage.py test apps.series.test_payment_qr
```

Expected: Node tests pass; Django confirms the vendor and core assets are discoverable.

- [ ] **Step 6: Commit**

```bash
git add frontend/static/js/payment-center-core.js \
        frontend/static/js/payment-center-core.test.js \
        frontend/apps/series/test_payment_qr.py
git commit -m "test: lock alipay qr rendering contract"
```

---

### Task 6: Add the Global Pending-Order Entry

**Files:**
- Modify: `frontend/templates/base.html`
- Test: `frontend/apps/series/test_payment_qr.py`

- [ ] **Step 1: Write failing navigation contract tests**

Assert `base.html` contains an independent icon link between membership badge and avatar menu:

```python
self.assertIn('id="pendingPaymentButton"', base_template)
self.assertIn('href="/membership/?paymentCenter=pending"', base_template)
self.assertIn('id="pendingPaymentCount"', base_template)
self.assertIn("fetch('/api/payments/orders/pending/')", base_template)
self.assertIn('loadPendingPaymentCount()', base_template)
```

Use deterministic string positions to assert the three controls are independent siblings in the expected order:

```python
membership_pos = base_template.index('id="membershipBadgeButton"')
pending_pos = base_template.index('id="pendingPaymentButton"')
menu_pos = base_template.index('id="userMenuToggle"')
self.assertLess(membership_pos, pending_pos)
self.assertLess(pending_pos, menu_pos)

membership_button = base_template[membership_pos:pending_pos]
pending_button = base_template[pending_pos:menu_pos]
self.assertNotIn('id="pendingPaymentButton"', membership_button)
self.assertNotIn('id="userMenuToggle"', pending_button)
```

- [ ] **Step 2: Run Django test and verify RED**

Run:

```bash
cd frontend
python3 manage.py test apps.series.test_payment_qr
```

Expected: missing pending-payment button and fetch call.

- [ ] **Step 3: Add the icon entry and count loader**

Add an icon-only link with tooltip/title and stable dimensions:

```html
<a id="pendingPaymentButton" href="/membership/?paymentCenter=pending"
   class="pending-payment-button" aria-label="待支付订单" title="待支付订单">
    <i data-lucide="receipt-text" class="w-4 h-4"></i>
    <span id="pendingPaymentCount" class="pending-payment-count" hidden>0</span>
</a>
```

Add `loadPendingPaymentCount` next to `loadUserCredits`:

```javascript
function loadPendingPaymentCount() {
    return fetch('/api/payments/orders/pending/')
        .then(response => response.json())
        .then(payload => {
            const count = payload.code === 200 && Array.isArray(payload.data) ? payload.data.length : 0;
            const badge = document.getElementById('pendingPaymentCount');
            if (!badge) return count;
            badge.textContent = count > 99 ? '99+' : String(count);
            badge.hidden = count === 0;
            return count;
        })
        .catch(() => 0);
}
```

Call it once for authenticated pages and expose it as `window.loadPendingPaymentCount` so payment success can refresh the count.

- [ ] **Step 4: Add responsive styles**

Give the icon button a fixed `40px` square, a visible focus ring, and an absolute count badge. At `max-width: 768px`, keep it reachable inside the mobile navigation actions without overlapping the avatar or membership popover.

- [ ] **Step 5: Run Django tests**

Run:

```bash
cd frontend
python3 manage.py test apps.series.test_payment_qr
```

Expected: navigation contract tests pass.

- [ ] **Step 6: Commit**

```bash
git add frontend/templates/base.html frontend/apps/series/test_payment_qr.py
git commit -m "feat: add pending payment nav entry"
```

---

### Task 7: Replace Payment Modals With the Unified Payment Center Shell

**Files:**
- Create: `frontend/static/css/payment-center.css`
- Modify: `frontend/templates/payments/membership.html`
- Test: `frontend/apps/series/test_payment_qr.py`

- [ ] **Step 1: Write failing shell and responsive tests**

Assert the membership template:

```python
self.assertIn('id="paymentCenterOverlay"', template)
self.assertIn('id="paymentCenterPanel"', template)
self.assertIn('id="paymentCenterCurrentTab"', template)
self.assertIn('id="paymentCenterPendingTab"', template)
self.assertIn('id="paymentPendingList"', template)
self.assertIn('id="paymentQrCanvas"', template)
self.assertNotIn('id="paymentMethodModal"', template)
self.assertNotIn('id="paymentQrModal"', template)
self.assertIn("{% static 'css/payment-center.css' %}", template)
```

Read the CSS file and assert it contains a desktop constrained width and the mobile full-screen breakpoint.

- [ ] **Step 2: Run Django tests and verify RED**

Run:

```bash
cd frontend
python3 manage.py test apps.series.test_payment_qr
```

Expected: unified payment center IDs and CSS are missing.

- [ ] **Step 3: Add the single-layer payment center markup**

Replace both `.payment-modal` blocks with one overlay and one `<aside>`:

```html
<div id="paymentCenterOverlay" class="payment-center-overlay" hidden></div>
<aside id="paymentCenterPanel" class="payment-center" role="dialog" aria-modal="true"
       aria-labelledby="paymentCenterTitle" aria-hidden="true">
    <header class="payment-center-header">
        <div><p>支付宝</p><h2 id="paymentCenterTitle">支付中心</h2></div>
        <button id="paymentCenterClose" type="button" class="payment-center-icon-button"
                aria-label="关闭支付中心" title="关闭">
            <i data-lucide="x"></i>
        </button>
    </header>
    <nav class="payment-center-tabs" aria-label="支付中心视图">
        <button id="paymentCenterCurrentTab" type="button" aria-selected="true">当前支付</button>
        <button id="paymentCenterPendingTab" type="button" aria-selected="false">
            待支付订单 <span id="paymentCenterPendingCount">0</span>
        </button>
    </nav>
    <section id="paymentCenterCurrentView"></section>
    <section id="paymentCenterPendingView" hidden>
        <div id="paymentPendingList"></div>
    </section>
</aside>
```

Inside `paymentCenterCurrentView`, provide stable containers for product summary, method buttons, busy state, QR Canvas, order number, countdown, status, error text and actions. Use buttons with Lucide icons for query, retry render, open Alipay link and cashier fallback.

- [ ] **Step 4: Add desktop and mobile CSS**

Implement these fixed layout contracts:

```css
.payment-center { position: fixed; inset: 0 0 0 auto; width: min(520px, 92vw); }
.payment-center[aria-hidden="true"] { transform: translateX(100%); pointer-events: none; }
.payment-center[aria-hidden="false"] { transform: translateX(0); }
.payment-center-overlay { position: fixed; inset: 0; }
.payment-center-qr { width: 232px; height: 232px; }

@media (max-width: 768px) {
    .payment-center { inset: 0; width: 100%; max-width: none; }
    .payment-center-actions { grid-template-columns: 1fr; }
}

@media (prefers-reduced-motion: reduce) {
    .payment-center, .payment-center-overlay { transition: none; }
}
```

Use at most `8px` corner radius, stable button heights, no nested cards, and no decorative gradients.

- [ ] **Step 5: Include versioned local assets**

At the end of `membership.html`, load in this order:

```html
<script src="{% static 'vendor/qrcode/qrcode.min.js' %}?v=1.5.3"></script>
<script src="{% static 'js/payment-center-core.js' %}?v=20260719"></script>
<script src="{% static 'js/payment-center.js' %}?v=20260719"></script>
```

Do not place Canvas inside the options object and do not add CDN fallbacks.

- [ ] **Step 6: Run template tests**

Run:

```bash
cd frontend
python3 manage.py test apps.series.test_payment_qr
```

Expected: all tests that exist at this point pass. Do not add the state-machine behavior tests until Task 8, so this task ends green.

- [ ] **Step 7: Commit**

```bash
git add frontend/static/css/payment-center.css \
        frontend/templates/payments/membership.html \
        frontend/apps/series/test_payment_qr.py
git commit -m "feat: add unified payment center shell"
```

---

### Task 8: Implement the Payment Center State Machine and Recovery

**Files:**
- Create: `frontend/static/js/payment-center.js`
- Modify: `frontend/templates/payments/membership.html`
- Modify: `frontend/templates/base.html`
- Test: `frontend/apps/series/test_payment_qr.py`
- Test: `frontend/apps/series/tests.py`

- [ ] **Step 1: Write failing behavior-contract tests**

Assert the static JS contains and uses each authoritative state:

```python
for state in (
    'CHOOSING', 'CREATING', 'CREATE_ERROR', 'QR_READY',
    'QR_RENDER_ERROR', 'QUERYING', 'PAID', 'EXPIRED', 'PAGE_REDIRECTING'
):
    self.assertIn(state, payment_center_js)

self.assertIn("PaymentCenterCore.renderQr(window.QRCode, canvas, order.qrCode", payment_center_js)
self.assertNotIn("currentQrOrder.status = 'FAILED'", payment_center_js)
self.assertIn("fetch('/api/payments/orders/pending/')", payment_center_js)
self.assertIn("/resume/", payment_center_js)
self.assertIn("/switch-to-page/", payment_center_js)
self.assertIn("paymentReady", payment_center_js)
self.assertIn("document.addEventListener('visibilitychange'", payment_center_js)
self.assertIn("window.loadUserCredits", payment_center_js)
self.assertIn("window.loadPendingPaymentCount", payment_center_js)
```

Proxy tests must also confirm reopening a pending QR order displays the original `qrCode` response rather than creating another order.

- [ ] **Step 2: Run Node and Django tests and verify RED**

Run:

```bash
cd frontend
node --test static/js/payment-center-core.test.js
python3 manage.py test apps.series.test_payment_qr apps.series.tests
```

Expected: `payment-center.js` is missing or behavior contracts fail.

- [ ] **Step 3: Implement the controller class and state renderer**

Use a single controller instance:

```javascript
class PaymentCenter {
    constructor(root, options) {
        this.root = root;
        this.options = options;
        this.state = {ui: PaymentCenterCore.UI.IDLE, orderStatus: null};
        this.currentPurchase = null;
        this.currentOrder = null;
        this.pollTimer = null;
        this.countdownTimer = null;
        this.syncInFlight = false;
        this.bind();
    }

    transition(ui, patch = {}) {
        this.state = {...this.state, ...patch, ui};
        this.render();
    }

    openPurchase(purchase) {
        this.currentPurchase = purchase;
        this.open();
        this.showCurrent();
        this.transition(PaymentCenterCore.UI.CHOOSING, {error: ''});
    }
}
```

`open()` must reveal panel and overlay, set `aria-hidden=false`, lock body scrolling and move focus into the panel. `close()` stops timers and hides the panel without changing the backend order.

- [ ] **Step 4: Keep the panel open during order creation**

Implement `chooseMethod(method)` so it transitions to `CREATING` before calling `fetch`. On a QR response, always retain the returned order first. If `paymentReady=true`, call `showQrOrder`; if `paymentReady=false`, transition to `CREATE_ERROR` while preserving order number, amount, expiry and a “重试预下单” action that uses `/resume/`. On a successful PAGE response transition to `PAGE_REDIRECTING` and call the existing safe `submitAlipayForm`. On any error transition to `CREATE_ERROR`; never call `close()` from the method-selection path.

- [ ] **Step 5: Render QR without mutating business status on failure**

Implement:

```javascript
async showQrOrder(order) {
    this.currentOrder = {...order, status: order.status || 'CREATED'};
    this.transition(PaymentCenterCore.UI.QR_READY, {
        orderStatus: this.currentOrder.status,
        renderError: ''
    });
    try {
        const canvas = this.root.querySelector('#paymentQrCanvas');
        await PaymentCenterCore.renderQr(window.QRCode, canvas, order.qrCode, {
            width: 232,
            margin: 1,
            errorCorrectionLevel: 'M',
            color: {dark: '#101114', light: '#ffffff'}
        });
        this.startPolling();
    } catch (error) {
        this.state = PaymentCenterCore.withRenderFailure(this.state, error.message);
        this.render();
        this.startCountdownOnly();
    }
}
```

In `QR_RENDER_ERROR`, keep order number, amount, countdown and query action visible. Show the open-link action only when `PaymentCenterCore.isTrustedQrUrl(order.qrCode)` is true.

- [ ] **Step 6: Implement pending list and resume**

`loadPending()` fetches the list, updates both panel and nav counts, and renders rows with product, amount, method, readiness, expiry and a “继续支付” button. For `paymentReady=false`, label the row “二维码待生成”. `resume(orderNo)` calls:

```javascript
fetch(`/api/payments/orders/${encodeURIComponent(orderNo)}/resume/`, {method: 'POST'})
```

Then:

1. `PAID`: transition to `PAID`, refresh user info and counts.
2. `CLOSED`: transition to `EXPIRED`, refresh pending list.
3. QR + `CREATED` + `paymentReady=true`: call `showQrOrder` with the stored QR response.
4. QR + `CREATED` + `paymentReady=false`: keep the same order selected and show retry/cashier actions; never create a replacement QR order.
5. PAGE + `CREATED`: validate and submit the returned form.

Implement `switchCurrentQrToPage()` using only:

```javascript
fetch(`/api/payments/orders/${encodeURIComponent(this.currentOrder.orderNo)}/switch-to-page/`, {
    method: 'POST'
})
```

Disable the action while pending. Submit the returned PAGE form only when the server confirms `paymentMethod='PAGE'` and `paymentReady=true`. If the original QR order became `PAID`, show success instead. For query/cancel uncertainty, keep the original order and display the server's “请勿重复支付” message. Once a QR order exists, the browser must never call the generic create endpoint to switch payment methods.

Reading `?paymentCenter=pending` on load must open the panel and select the pending tab. Prevent the base order link's default navigation only when already on `/membership/`.

- [ ] **Step 7: Implement polling lifecycle**

Use one 5-second interval and one 1-second countdown. Stop both on close, `PAID`, `CLOSED` and `EXPIRED`. On `document.hidden`, stop polling; when visible and the panel has a `CREATED` current order, call one immediate sync and restart polling.

When sync returns `PAID`, call:

```javascript
if (typeof window.loadUserCredits === 'function') window.loadUserCredits();
if (typeof window.loadPendingPaymentCount === 'function') window.loadPendingPaymentCount();
this.loadPending();
```

- [ ] **Step 8: Preserve the safe PAGE form contract**

Move the existing `submitAlipayForm` unchanged in behavior: require exactly one POST form; require HTTPS; allow only exact hosts `openapi.alipay.com` and `openapi-sandbox.dl.alipaydev.com`; require `/gateway.do`; rebuild hidden inputs instead of assigning raw HTML.

- [ ] **Step 9: Run all frontend-focused tests**

Run:

```bash
cd frontend
node --test static/js/payment-center-core.test.js
python3 manage.py test apps.series.test_payment_qr apps.series.tests
```

Expected: all payment and series frontend tests pass; no assertion references the removed modal IDs.

- [ ] **Step 10: Commit**

```bash
git add frontend/static/js/payment-center.js \
        frontend/templates/payments/membership.html frontend/templates/base.html \
        frontend/apps/series/test_payment_qr.py frontend/apps/series/tests.py
git commit -m "feat: add recoverable alipay payment center"
```

---

### Task 9: Verify Static Asset Collection and Browser Behavior

**Files:**
- Modify: `frontend/apps/series/test_payment_qr.py`
- Generated only: `frontend/staticfiles/` (do not commit)

- [ ] **Step 1: Add final static asset assertions**

Assert all versioned local assets are discoverable and the template contains no CDN URLs:

```python
for asset in (
    'vendor/qrcode/qrcode.min.js',
    'js/payment-center-core.js',
    'js/payment-center.js',
    'css/payment-center.css',
):
    self.assertIsNotNone(finders.find(asset), asset)

self.assertNotIn('cdn.jsdelivr.net', template)
self.assertNotIn('unpkg.com', template)
```

- [ ] **Step 2: Run static collection**

Run:

```bash
cd frontend
python3 manage.py collectstatic --noinput
```

Expected: command exits 0 and `staticfiles/vendor/qrcode/qrcode.min.js` exists.

- [ ] **Step 3: Start local services**

From the repository root, use dedicated temporary logs and PID files so existing project PID files stay untouched:

```bash
BACKEND_PORT=8081
FRONTEND_PORT=8001
if lsof -nP -iTCP:${BACKEND_PORT} -sTCP:LISTEN >/dev/null; then BACKEND_PORT=18081; fi
if lsof -nP -iTCP:${FRONTEND_PORT} -sTCP:LISTEN >/dev/null; then FRONTEND_PORT=18001; fi

(cd backend && mvn -q -DskipTests spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=${BACKEND_PORT}") \
  >/tmp/aiwork-payment-backend.log 2>&1 &
echo $! >/tmp/aiwork-payment-backend.pid

(cd frontend && BACKEND_API_URL="http://127.0.0.1:${BACKEND_PORT}/api" \
  python3 manage.py runserver "127.0.0.1:${FRONTEND_PORT}" --noreload) \
  >/tmp/aiwork-payment-frontend.log 2>&1 &
echo $! >/tmp/aiwork-payment-frontend.pid

sleep 8
tail -n 60 /tmp/aiwork-payment-backend.log
tail -n 30 /tmp/aiwork-payment-frontend.log
curl -fsSI "http://127.0.0.1:${FRONTEND_PORT}/static/vendor/qrcode/qrcode.min.js"
curl -fsSI "http://127.0.0.1:${FRONTEND_PORT}/static/js/payment-center.js"
printf 'Frontend URL: http://127.0.0.1:%s/membership/\n' "${FRONTEND_PORT}"
```

Expected: backend reaches `Started MangaAiApplication`; frontend and both payment assets return `200`. If the configured database is unavailable, stop the backend process, record the exact database error, run browser checks with mocked Django responses, and do not claim a live sandbox end-to-end result.

- [ ] **Step 4: Browser-test desktop and mobile**

At `1280x720` and `390x844`, verify:

1. The nav order icon and count do not overlap the membership badge or avatar.
2. Selecting QR immediately opens and keeps the payment center visible.
3. The `CREATING` state prevents duplicate clicks.
4. A mocked/stubbed successful QR response paints nonblank Canvas pixels.
5. Blocking the qrcode asset produces `QR_RENDER_ERROR` while order status remains `CREATED` and recovery actions remain visible.
6. Closing and reopening through the order icon restores a pending order.
7. Mobile uses full screen and all labels fit without horizontal overflow.
8. Console has no uncaught errors.

After browser checks, stop only the processes started above:

```bash
kill "$(cat /tmp/aiwork-payment-frontend.pid)" 2>/dev/null || true
kill "$(cat /tmp/aiwork-payment-backend.pid)" 2>/dev/null || true
rm -f /tmp/aiwork-payment-frontend.pid /tmp/aiwork-payment-backend.pid
```

- [ ] **Step 5: Commit test hardening**

```bash
git add frontend/apps/series/test_payment_qr.py
git commit -m "test: enforce payment static deployment contract"
```

---

### Task 10: Full Regression, Security Review, and Delivery

**Files:**
- Review all files changed in Tasks 1-9.

- [ ] **Step 1: Run the full backend suite**

Run:

```bash
cd backend
mvn -q test
```

Expected: all backend tests pass with 0 failures and 0 errors.

- [ ] **Step 2: Run the full frontend suite**

Run:

```bash
cd frontend
node --test static/js/payment-center-core.test.js
python3 manage.py test apps.auth.tests apps.role.tests apps.series.tests apps.series.test_payment_qr
```

Expected: Node and all discovered Django tests pass.

- [ ] **Step 3: Run repository checks**

Run:

```bash
git diff --check
git status --short
git diff --stat origin/dev_20260629_pay...HEAD
```

Expected: no whitespace errors; only intended source, test and documentation changes plus known untracked `.claude/`, `.superpowers/` and PID files.

- [ ] **Step 4: Scan tracked implementation content for secrets and QR leakage**

Run:

```bash
git grep -nE \
  '(ghp_[A-Za-z0-9]{20,}|BEGIN (RSA )?PRIVATE KEY|MIIEv[A-Za-z0-9+/=]{40,}|支付密码)' \
  -- backend frontend
git grep -nE \
  '(log|logger)\.(trace|debug|info|warn|error)[^(]*\([^;]*(qrCode|qrcode|qr_code)' \
  -- backend frontend
git diff origin/dev_20260629_pay...HEAD -- backend frontend | rg \
  '(ghp_[A-Za-z0-9]{20,}|BEGIN (RSA )?PRIVATE KEY|MIIEv[A-Za-z0-9+/=]{40,}|支付密码)'
```

Expected: all three commands produce no matches. The public host literal `https://qr.alipay.com/` is allowed in validation code and synthetic tests, but no runtime QR value may be logged.

- [ ] **Step 5: Resolve any verification failure in its owning task**

Do not create a final catch-all commit. If Steps 1-4 fail, return to the task that owns the affected behavior, add or update its failing regression test, make the minimum fix, rerun that task's explicit verification command, and create a focused commit using that task's listed `git add` paths. Then rerun Tasks 10 Steps 1-4 from the beginning. If no correction is needed, continue without an extra commit.

- [ ] **Step 6: Push the branch**

```bash
git push origin dev_20260629_pay
```

Expected: remote branch advances to the final verified commit. Do not stage `.claude/`, `.superpowers/`, generated `staticfiles/` or PID files.

- [ ] **Step 7: Run the production deployment gate after deployment**

Once the pushed commit has been deployed to `www.yzmovie.cn`, run:

```bash
curl -fsSI https://www.yzmovie.cn/static/vendor/qrcode/qrcode.min.js
curl -fsSI https://www.yzmovie.cn/static/js/payment-center-core.js
curl -fsSI https://www.yzmovie.cn/static/js/payment-center.js
curl -fsS https://www.yzmovie.cn/static/js/payment-center-core.js \
  | rg 'toCanvas\(canvas, text, options\)'
curl -fsS https://www.yzmovie.cn/static/js/payment-center.js \
  | rg 'QR_RENDER_ERROR|/api/payments/orders/pending/|/switch-to-page/'
```

Expected: all three assets return `200`; deployed core contains the Canvas-first call and the controller contains recovery contracts. In an authenticated production browser session, confirm the membership page requests all three assets with `200` and contains no `QRCode.toCanvas(order.qrCode, { canvas:` call. If deployment is outside this task's control, report this gate as pending rather than claiming production verification.

---

## Acceptance Checklist

- [ ] Production and local pages use `QRCode.toCanvas(canvas, text, options)` through the tested core wrapper.
- [ ] Missing QR script leaves a real order recoverable and does not set its business status to failed.
- [ ] Payment center remains open throughout precreate and offers explicit retry/cashier actions on exhaustion.
- [ ] Automatic retry is capped at 3 and reuses the same persisted `out_trade_no` after each transient failure.
- [ ] Pending order count is visible next to the avatar and opens the payment center.
- [ ] QR and PAGE pending orders can be resumed only by their owner.
- [ ] Resume performs an authoritative server query before returning payment material.
- [ ] Switching an existing QR order to PAGE requires an authoritative query/cancel gate and cannot create a duplicate payable order.
- [ ] Callback/query/cancel races cannot grant credits or membership twice.
- [ ] Static assets pass `collectstatic`, local browser checks and the post-deployment HTTP check.
- [ ] Full backend, Node and Django suites pass before push.
