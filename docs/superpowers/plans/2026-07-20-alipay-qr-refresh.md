# Alipay QR Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a payment-safe “refresh QR code” flow that closes an unpaid Alipay QR order before creating a replacement, and make pending-order recovery show either a usable QR code or an explicit refresh action.

**Architecture:** Extend the existing payment recovery boundary with one authenticated `refresh-qr` command. The backend remains authoritative: it queries and settles/cancels the old trade under the existing per-order lock, conditionally closes it, clones the server-side product snapshot into a new QR order, and reuses the current precreate pipeline. The frontend adds a separate refresh state and button while preserving the existing canvas-only retry action.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus, JUnit 5, Mockito, Django, Python `unittest`, vanilla JavaScript, qrcode 1.5.3, Node test runner.

---

## File Map

- `backend/src/main/java/com/manga/ai/payment/service/PaymentOrderService.java`: expose the refresh command.
- `backend/src/main/java/com/manga/ai/payment/controller/PaymentController.java`: publish the authenticated REST endpoint.
- `backend/src/main/java/com/manga/ai/payment/service/PaymentOrderServiceImpl.java`: query, cancel, close, clone, and precreate the replacement order.
- `backend/src/test/java/com/manga/ai/payment/controller/PaymentControllerTest.java`: lock the endpoint contract.
- `backend/src/test/java/com/manga/ai/payment/service/PaymentOrderServiceImplTest.java`: verify payment safety, cloning, failure behavior, and duplicate-call rejection.
- `frontend/apps/series/urls.py`: expose the Django refresh proxy route.
- `frontend/apps/series/views.py`: forward the CSRF-protected refresh command.
- `frontend/apps/series/test_payment_qr.py`: test proxy, markup, state-machine, and concurrency contracts.
- `frontend/static/js/payment-center-core.js`: add the stable `REFRESHING_QR` UI state.
- `frontend/static/js/payment-center-core.test.js`: assert the new state is exported.
- `frontend/static/js/payment-center.js`: implement the refresh interaction and missing-QR recovery state.
- `frontend/templates/payments/membership.html`: add the refresh action.
- `frontend/static/css/payment-center.css`: only adjust action layout if the fifth action overflows at tested breakpoints.

### Task 1: Backend API Contract

**Files:**
- Modify: `backend/src/main/java/com/manga/ai/payment/service/PaymentOrderService.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/controller/PaymentController.java`
- Test: `backend/src/test/java/com/manga/ai/payment/controller/PaymentControllerTest.java`

- [ ] **Step 1: Write the failing controller test**

Add this test next to the existing resume and switch tests:

```java
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
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd backend
mvn -q -Dtest=PaymentControllerTest#refreshesQrOrderForAuthenticatedUser test
```

Expected: compilation fails because `refreshQrOrder` and `/refresh-qr` do not exist.

- [ ] **Step 3: Add the service and controller signatures**

Add to `PaymentOrderService`:

```java
PaymentOrderCreateResponse refreshQrOrder(Long userId, String orderNo);
```

Add to `PaymentController`:

```java
@PostMapping("/orders/{orderNo}/refresh-qr")
public Result<PaymentOrderCreateResponse> refreshQrOrder(@PathVariable String orderNo) {
    return Result.success(paymentOrderService.refreshQrOrder(
            UserContextHolder.getUserId(), orderNo));
}
```

- [ ] **Step 4: Add a temporary compiling service implementation**

Add to `PaymentOrderServiceImpl` so Task 1 can compile without implementing behavior early:

```java
@Override
public PaymentOrderCreateResponse refreshQrOrder(Long userId, String orderNo) {
    throw new UnsupportedOperationException("refresh QR not implemented");
}
```

- [ ] **Step 5: Run the controller test and verify GREEN**

Run the command from Step 2. Expected: one test passes.

- [ ] **Step 6: Commit the API contract**

```bash
git add backend/src/main/java/com/manga/ai/payment/service/PaymentOrderService.java \
  backend/src/main/java/com/manga/ai/payment/controller/PaymentController.java \
  backend/src/main/java/com/manga/ai/payment/service/PaymentOrderServiceImpl.java \
  backend/src/test/java/com/manga/ai/payment/controller/PaymentControllerTest.java
git commit -m "feat: expose safe qr refresh endpoint"
```

### Task 2: Safe Backend Replacement Flow

**Files:**
- Modify: `backend/src/main/java/com/manga/ai/payment/service/PaymentOrderServiceImpl.java`
- Test: `backend/src/test/java/com/manga/ai/payment/service/PaymentOrderServiceImplTest.java`

- [ ] **Step 1: Write the failing unpaid-order replacement test**

Add a test that uses an `AtomicReference<PaymentOrder>` for the old row, captures the inserted row, and configures Alipay as waiting then successfully cancelled:

```java
@Test
void refreshQrCancelsWaitingTradeAndCreatesReplacementFromServerSnapshot() {
    PaymentOrderMapper mapper = mock(PaymentOrderMapper.class);
    AlipayPaymentClient client = mock(AlipayPaymentClient.class);
    PaymentOrder source = resumableQrOrder("PAY-REFRESH-SOURCE");
    source.setProductCode("month_standard");
    source.setProductName("包月标准会员");
    source.setAmountCents(19900);
    source.setBaseCredits(0);
    source.setBonusCredits(2210);
    source.setTotalCredits(2210);
    source.setMembershipTier("STANDARD");
    source.setMembershipPeriod("MONTH");
    source.setMembershipMonths(1);
    AtomicReference<PaymentOrder> current = new AtomicReference<>(source);
    AtomicReference<PaymentOrder> inserted = new AtomicReference<>();
    when(mapper.selectOne(any())).thenAnswer(invocation -> current.get());
    when(mapper.update(any(), any())).thenAnswer(invocation -> {
        PaymentOrder update = invocation.getArgument(0);
        if (update.getStatus() != null) source.setStatus(update.getStatus());
        if (update.getQrcode() != null && inserted.get() != null) {
            inserted.get().setQrcode(update.getQrcode());
        }
        return 1;
    });
    when(mapper.insert(any())).thenAnswer(invocation -> {
        PaymentOrder replacement = invocation.getArgument(0);
        inserted.set(replacement);
        current.set(replacement);
        return 1;
    });
    when(client.query(source.getOrderNo())).thenReturn(new AlipayQueryResult(
            source.getOrderNo(), null, "WAIT_BUYER_PAY", null));
    when(client.cancel(source.getOrderNo())).thenReturn(
            new AlipayCancelResult(true, false, "close"));
    when(client.precreate(any())).thenReturn(
            new AlipayPrecreateResult("https://qr.alipay.com/refreshed"));
    PaymentOrderServiceImpl service = service(
            mapper, mock(UserMembershipMapper.class), mock(CreditAccountService.class),
            mock(MembershipService.class), client);

    PaymentOrderCreateResponse response = service.refreshQrOrder(7L, source.getOrderNo());

    assertThat(source.getStatus()).isEqualTo(PaymentOrderStatus.CLOSED.name());
    assertThat(response.orderNo()).isNotEqualTo(source.getOrderNo());
    assertThat(response.qrCode()).isEqualTo("https://qr.alipay.com/refreshed");
    assertThat(response.amountCents()).isEqualTo(19900);
    assertThat(response.totalCredits()).isEqualTo(2210);
    assertThat(inserted.get().getMembershipTier()).isEqualTo("STANDARD");
    assertThat(inserted.get().getExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(119));
    verify(client).cancel(source.getOrderNo());
    verify(mapper).insert(any(PaymentOrder.class));
}
```

- [ ] **Step 2: Run the replacement test and verify RED**

Run:

```bash
cd backend
mvn -q -Dtest=PaymentOrderServiceImplTest#refreshQrCancelsWaitingTradeAndCreatesReplacementFromServerSnapshot test
```

Expected: the temporary implementation throws `UnsupportedOperationException`.

- [ ] **Step 3: Implement the minimal safe refresh flow**

Replace the temporary implementation with:

```java
@Override
public PaymentOrderCreateResponse refreshQrOrder(Long userId, String orderNo) {
    return withOrderOperationLock(orderNo, () -> doRefreshQrOrder(userId, orderNo));
}

private PaymentOrderCreateResponse doRefreshQrOrder(Long userId, String orderNo) {
    PaymentOrder source = findOwnedOrder(userId, orderNo);
    if (!PaymentOrderStatus.CREATED.name().equals(source.getStatus())) {
        throw new BusinessException("该订单当前状态不支持刷新二维码");
    }
    if (!PaymentMethod.QR.name().equals(source.getPaymentMethod())) {
        throw new BusinessException("仅扫码支付订单可刷新二维码");
    }

    AlipayQueryResult queryResult = queryForSafePaymentAction(orderNo);
    if (queryResult != null && queryResult.paid()) {
        settleFromQuery(source, queryResult, "refresh-query");
        return toCreateResponseWithoutPaymentPayload(findOrder(orderNo));
    }

    if (waitingForBuyer(queryResult)) {
        AlipayCancelResult cancelResult = alipayPaymentClient.cancel(orderNo);
        if (!definitivelyCancelled(cancelResult)) {
            throw new BusinessException(PAYMENT_STATE_UNCERTAIN_MESSAGE);
        }
    } else if (!tradeClosed(queryResult) && !notFound(queryResult)) {
        throw new BusinessException(PAYMENT_STATE_UNCERTAIN_MESSAGE);
    }

    int closedRows = closeCreatedOrder(orderNo);
    PaymentOrder current = findOrder(orderNo);
    if (PaymentOrderStatus.PAID.name().equals(current.getStatus())) {
        return toCreateResponseWithoutPaymentPayload(current);
    }
    if (closedRows == 0 || !PaymentOrderStatus.CLOSED.name().equals(current.getStatus())) {
        throw new BusinessException(PAYMENT_STATE_UNCERTAIN_MESSAGE);
    }
    return preparePayAndInsert(cloneAsPaymentOrder(source, PaymentMethod.QR));
}
```

Replace `cloneAsPageOrder` with a shared clone that preserves only server-authoritative product fields:

```java
private PaymentOrder cloneAsPageOrder(PaymentOrder source) {
    return cloneAsPaymentOrder(source, PaymentMethod.PAGE);
}

private PaymentOrder cloneAsPaymentOrder(PaymentOrder source, PaymentMethod method) {
    PaymentOrder replacement = baseOrder(
            source.getUserId(), PaymentOrderType.valueOf(source.getOrderType()),
            source.getProductCode(), source.getProductName(), safe(source.getAmountCents()), method);
    replacement.setBaseCredits(safe(source.getBaseCredits()));
    replacement.setBonusCredits(safe(source.getBonusCredits()));
    replacement.setTotalCredits(safe(source.getTotalCredits()));
    replacement.setMembershipTier(source.getMembershipTier());
    replacement.setMembershipPeriod(source.getMembershipPeriod());
    replacement.setMembershipMonths(source.getMembershipMonths());
    return replacement;
}
```

- [ ] **Step 4: Run the replacement test and verify GREEN**

Run the Step 2 command. Expected: one test passes.

- [ ] **Step 5: Add failing safety tests**

Add six independent test methods, each with a fresh mapper/client fixture:

1. `refreshQrSettlesPaidTradeWithoutCreatingReplacement`: query returns `TRADE_SUCCESS`; assert `PAID`, one credit/membership settlement, and no cancel, precreate, or insert.
2. `refreshQrRejectsUnknownTradeStateWithoutCancellingOrReplacing`: query returns an unknown status; assert `PAYMENT_STATE_UNCERTAIN_MESSAGE` and no cancel, precreate, update, or insert.
3. `refreshQrRejectsRetryRequiredCancelAndKeepsSourceCreated`: query returns `WAIT_BUYER_PAY`, cancel returns `success=true, retryRequired=true`; assert the uncertainty message, source remains `CREATED`, and no update or insert.
4. `refreshQrRejectsOrderOwnedByAnotherUserBeforeCallingAlipay`: call as user 8 for a user-7 order; assert `订单不存在` and no Alipay interaction.
5. `refreshQrRejectsPageOrderBeforeCallingAlipay`: set the source method to `PAGE`; assert `仅扫码支付订单可刷新二维码` and no Alipay interaction.
6. `refreshQrRejectsSecondCallAfterSourceWasClosed`: set the old source to `CLOSED`; assert `该订单当前状态不支持刷新二维码` and no Alipay interaction or insert.

Use the existing switch-to-page fixtures for complete mock wiring. For the paid fixture, make `mapper.update` mutate the in-memory source to `PAID`; for cancellation fixtures, mutate it to `CLOSED` only when the conditional close update is called. Use the repository's existing `PAYMENT_STATE_UNCERTAIN_MESSAGE` text in assertions rather than introducing a second wording.

- [ ] **Step 6: Run the safety tests and verify RED, then GREEN**

Run each new test by method name before adjusting implementation. The paid case should initially expose any mapper state setup gap; fix only the refresh implementation or fixture state transition required by the failing assertion. Then run:

```bash
cd backend
mvn -q -Dtest=PaymentOrderServiceImplTest test
```

Expected: all `PaymentOrderServiceImplTest` tests pass.

- [ ] **Step 7: Commit the backend behavior**

```bash
git add backend/src/main/java/com/manga/ai/payment/service/PaymentOrderServiceImpl.java \
  backend/src/test/java/com/manga/ai/payment/service/PaymentOrderServiceImplTest.java
git commit -m "feat: safely replace stale alipay qr orders"
```

### Task 3: Django Refresh Proxy

**Files:**
- Modify: `frontend/apps/series/urls.py`
- Modify: `frontend/apps/series/views.py`
- Test: `frontend/apps/series/test_payment_qr.py`

- [ ] **Step 1: Write failing route, forwarding, CSRF, and safe-error tests**

Add the refresh path to the existing proxy test cases and this focused forwarding test:

```python
def test_pending_order_refresh_qr_proxy_forwards_request(self):
    session = self.client.session
    session['token'] = 'token-pay'
    session.save()
    backend_client = Mock()
    backend_client.post.return_value = {
        'orderNo': 'PAY-REFRESHED',
        'paymentMethod': 'QR',
        'qrCode': 'https://qr.alipay.com/refreshed',
    }

    with patch('apps.series.views.BackendClient', return_value=backend_client):
        response = self.client.post(
            '/api/payments/orders/PAY-PENDING/refresh-qr/'
        )

    self.assertEqual(
        resolve('/api/payments/orders/PAY-PENDING/refresh-qr/').url_name,
        'payment_order_refresh_qr',
    )
    self.assertEqual(response.status_code, 200)
    self.assertEqual(response.json()['data']['orderNo'], 'PAY-REFRESHED')
    backend_client.post.assert_called_once_with(
        '/v1/payments/orders/PAY-PENDING/refresh-qr'
    )
```

Extend missing-CSRF, matching-CSRF, and safe backend status tables with the refresh route.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
cd frontend
/tmp/aiwork-frontend-venv/bin/python manage.py test \
  apps.series.test_payment_qr.MembershipQrPaymentTests.test_pending_order_refresh_qr_proxy_forwards_request
```

Expected: 404 because the route does not exist.

- [ ] **Step 3: Implement the route and proxy**

Add to `urls.py` before the generic order status route:

```python
path(
    'api/payments/orders/<str:order_no>/refresh-qr/',
    views.payment_order_refresh_qr,
    name='payment_order_refresh_qr',
),
```

Add to `views.py`:

```python
@require_http_methods(["POST"])
def payment_order_refresh_qr(request, order_no):
    """安全关闭旧扫码订单并生成新二维码。"""
    client = get_client(request)
    try:
        result = client.post(f'/v1/payments/orders/{order_no}/refresh-qr')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return _payment_error_response(e.status_code, e.message)
```

- [ ] **Step 4: Run proxy tests and verify GREEN**

Run:

```bash
cd frontend
/tmp/aiwork-frontend-venv/bin/python manage.py test apps.series.test_payment_qr
```

Expected: all module tests pass.

- [ ] **Step 5: Commit the proxy**

```bash
git add frontend/apps/series/urls.py frontend/apps/series/views.py \
  frontend/apps/series/test_payment_qr.py
git commit -m "feat: proxy safe qr refresh requests"
```

### Task 4: Payment Center Refresh Interaction

**Files:**
- Modify: `frontend/static/js/payment-center-core.js`
- Modify: `frontend/static/js/payment-center-core.test.js`
- Modify: `frontend/static/js/payment-center.js`
- Modify: `frontend/templates/payments/membership.html`
- Modify: `frontend/static/css/payment-center.css`
- Test: `frontend/apps/series/test_payment_qr.py`

- [ ] **Step 1: Write failing core and markup contract tests**

Add to `payment-center-core.test.js`:

```javascript
test('exports a dedicated QR refresh UI state', () => {
    assert.equal(core.UI.REFRESHING_QR, 'REFRESHING_QR');
});
```

Extend the Django template contract to require:

```python
self.assertIn('id="paymentQrRefresh"', template)
self.assertIn('aria-label="生成新的支付宝二维码"', template)
self.assertIn('<span>刷新二维码</span>', template)
```

Run Node and the focused Django test. Expected: both fail because the state/button are absent.

- [ ] **Step 2: Add the state and button**

Add `REFRESHING_QR: 'REFRESHING_QR'` to `PaymentCenterCore.UI`.

Add this action between query and canvas retry:

```html
<button id="paymentQrRefresh" class="payment-center-action" type="button"
        aria-label="生成新的支付宝二维码" title="刷新二维码">
    <i data-lucide="refresh-cw" aria-hidden="true"></i>
    <span>刷新二维码</span>
</button>
```

- [ ] **Step 3: Write failing controller-source contracts**

Add assertions that `payment-center.js` contains:

```python
self.assertIn('this.refreshInFlight = false;', controller)
self.assertIn("refresh: byId('paymentQrRefresh')", controller)
self.assertIn("this.elements.qrRefresh.addEventListener('click'", controller)
self.assertIn('async refreshCurrentQr()', controller)
self.assertIn('/refresh-qr/`', controller)
self.assertIn("window.confirm('刷新后旧二维码将失效，是否继续？')", controller)
self.assertIn('PaymentCenterCore.UI.REFRESHING_QR', controller)
```

Slice `refreshCurrentQr` and assert it calls `stopPolling()` before incrementing the operation sequence, uses `postJson`, updates `currentOrder`, calls `handleOrderResponse`, reloads pending orders, and restores the prior QR lifecycle in the error path.

Add render assertions that the canvas is hidden during `REFRESHING_QR`, the refresh action is visible for every `CREATED` QR order, and sync/switch/refresh are disabled while refresh is in flight.

- [ ] **Step 4: Run the controller contract tests and verify RED**

Run:

```bash
cd frontend
/tmp/aiwork-frontend-venv/bin/python manage.py test apps.series.test_payment_qr
```

Expected: failures identify the missing refresh controller behavior.

- [ ] **Step 5: Implement the refresh interaction**

Add `refreshInFlight`, collect/bind `paymentQrRefresh`, and implement:

```javascript
async refreshCurrentQr() {
    if (!this.currentOrder
            || this.currentOrder.status !== 'CREATED'
            || this.currentOrder.paymentMethod !== 'QR'
            || this.refreshInFlight) return;
    if (!window.confirm('刷新后旧二维码将失效，是否继续？')) return;

    const sourceOrderNo = this.currentOrder.orderNo;
    this.stopPolling();
    const operation = ++this.operationSequence;
    this.refreshInFlight = true;
    this.invalidateQrDisplay();
    this.transition(PaymentCenterCore.UI.REFRESHING_QR, {
        error: '',
        renderError: '',
    });
    try {
        const responseOrder = await this.postJson(
            `/api/payments/orders/${encodeURIComponent(sourceOrderNo)}/refresh-qr/`
        );
        if (operation !== this.operationSequence || this.root.hidden) return;
        const order = this.normalizeOrder(responseOrder, this.currentOrder);
        this.setCurrentOrder(order);
        await this.handleOrderResponse(order);
        await this.loadPending();
        if (typeof window.loadPendingPaymentCount === 'function') {
            window.loadPendingPaymentCount();
        }
    } catch (error) {
        if (operation !== this.operationSequence || this.root.hidden) return;
        this.transition(PaymentCenterCore.UI.CREATE_ERROR, {
            orderStatus: this.currentOrder.status,
            error: error.message || '二维码刷新失败，请先查询原订单状态',
        });
        this.restoreCurrentQrLifecycle();
    } finally {
        this.refreshInFlight = false;
        this.render();
    }
}
```

Update guards in `resume`, `syncCurrentOrder`, and `switchCurrentQrToPage` to return while `refreshInFlight` is true. In `render()`:

- include `REFRESHING_QR` in the busy state and set its text to `正在刷新支付宝二维码`;
- exclude `REFRESHING_QR` from `showQrCanvas`;
- show the refresh button for `CREATED` QR orders, including `paymentReady=false`;
- disable all conflicting actions while refreshing;
- set the status text to `正在生成新的支付宝二维码`.

The existing `handleOrderResponse` path already sends a QR response with no `qrCode` to `CREATE_ERROR`; keeping the QR detail view visible and the new refresh button visible removes the blank dead end after “继续支付”.

- [ ] **Step 6: Run Node and Django tests and verify GREEN**

Run:

```bash
cd frontend
node --test static/js/payment-center-core.test.js
/tmp/aiwork-frontend-venv/bin/python manage.py test \
  apps.series.test_payment_qr apps.series.tests
```

Expected: Node and every test in both requested Django modules pass.

- [ ] **Step 7: Stabilize the five-action mobile layout**

Change the action grid to one stable full-width column below 768px while preserving the existing two-column desktop grid:

```css
@media (max-width: 768px) {
    .payment-center-actions {
        grid-template-columns: 1fr;
    }
}
```

Do not change the color system or panel dimensions.

- [ ] **Step 8: Commit the frontend interaction**

```bash
git add frontend/static/js/payment-center-core.js \
  frontend/static/js/payment-center-core.test.js \
  frontend/static/js/payment-center.js \
  frontend/templates/payments/membership.html \
  frontend/static/css/payment-center.css \
  frontend/apps/series/test_payment_qr.py
git commit -m "feat: refresh unavailable alipay qr orders"
```

### Task 5: Integrated Verification and Delivery

**Files:**
- Verify all files changed above.
- Do not add `.claude/`, `.superpowers/`, PID files, local payment configuration, keys, or generated `staticfiles/`.

- [ ] **Step 1: Run full automated verification**

```bash
cd backend && mvn -q test
cd ../frontend && node --test static/js/payment-center-core.test.js
/tmp/aiwork-frontend-venv/bin/python manage.py test \
  apps.series.test_payment_qr apps.series.tests
/tmp/aiwork-frontend-venv/bin/python manage.py collectstatic --noinput
cd .. && git diff --check
```

Expected: Maven exits 0, Node reports zero failures, Django reports zero failures, the four payment static assets are collected, and `git diff --check` is silent.

- [ ] **Step 2: Browser-test with an authenticated payment mock**

At 1280x720 and 390x844 verify:

1. Open a pending QR order whose resume response has a trusted `qrCode`; “继续支付” renders nonblank QR pixels.
2. Open a pending QR order whose resume response has `paymentReady=false`; no empty canvas is shown and “刷新二维码” remains visible.
3. Cancel the confirmation once; verify no network call and unchanged order number.
4. Confirm refresh; verify the panel stays open, conflicting buttons disable, and the old QR disappears during the request.
5. Return a replacement order; verify the order number changes, a nonblank QR renders, polling restarts, and the pending list contains only the replacement.
6. Return `PAID`; verify the success flow refreshes credits, membership, and pending count without creating a second order.
7. Return HTTP 409; verify the old order number and query action remain visible with the uncertainty warning.
8. Check console output for payment errors and verify `scrollWidth === innerWidth` on mobile.

- [ ] **Step 3: Scan outgoing commits for secrets**

```bash
if git log --format= --patch origin/dev_20260629_pay..HEAD \
  | rg -q 'ghp_[A-Za-z0-9]{20,}|MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBK'; then
  printf 'sensitive-patterns-found\n'
  exit 2
else
  printf 'sensitive-patterns-not-found\n'
fi
```

Expected: `sensitive-patterns-not-found`.

- [ ] **Step 4: Review repository state**

```bash
git status --short --branch
git diff --stat origin/dev_20260629_pay..HEAD
```

Expected: only the known unrelated untracked files remain; no local configuration or generated output is staged.

- [ ] **Step 5: Push the current branch**

```bash
git fetch origin dev_20260629_pay
git rev-list --left-right --count origin/dev_20260629_pay...HEAD
git push origin dev_20260629_pay
```

Expected: no remote-only commits before push, and local/remote HEAD match afterward.
