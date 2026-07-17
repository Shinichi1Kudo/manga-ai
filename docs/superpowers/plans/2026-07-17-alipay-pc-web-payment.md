# Alipay PC Web Payment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the in-page Alipay order-code QR flow with a same-tab redirect to the official Alipay PC cashier while keeping memberships as one-time purchases.

**Architecture:** The Java backend will create an immutable local order snapshot and use `AlipayClient.pageExecute(request, "POST")` to return a signed HTML form. The existing Django JSON proxy will forward that form to the membership page, which validates and submits it in the current window. Alipay returns to a dedicated Django result page that uses only `out_trade_no` as a lookup key and confirms payment through the existing authenticated server-side query and settlement path.

**Tech Stack:** Java 17, Spring Boot, Alipay Java SDK, MyBatis-Plus, JUnit 5, Mockito, Python 3, Django, Django TestCase, browser JavaScript.

---

## File Structure

Backend payment boundary:

- Create `backend/src/main/java/com/manga/ai/payment/dto/AlipayPagePayRequest.java`: immutable page-pay request passed to the Alipay client.
- Create `backend/src/main/java/com/manga/ai/payment/dto/AlipayPagePayResult.java`: signed HTML form returned by the Alipay client.
- Delete `backend/src/main/java/com/manga/ai/payment/dto/AlipayPrecreateRequest.java`: order-code request is no longer used.
- Delete `backend/src/main/java/com/manga/ai/payment/dto/AlipayPrecreateResult.java`: order-code result is no longer used.
- Modify `backend/src/main/java/com/manga/ai/payment/client/AlipayPaymentClient.java`: replace `precreate` with `pagePay` while preserving query and notify verification.
- Modify `backend/src/main/java/com/manga/ai/payment/client/DefaultAlipayPaymentClient.java`: build `AlipayTradePagePayRequest` and invoke `pageExecute(alipayRequest, "POST")`.
- Create `backend/src/test/java/com/manga/ai/payment/client/DefaultAlipayPaymentClientTest.java`: verify the SDK request contract without network access.

Backend order/config boundary:

- Modify `backend/src/main/java/com/manga/ai/payment/dto/PaymentOrderCreateResponse.java`: expose `paymentHtml` instead of `qrcode`.
- Modify `backend/src/main/java/com/manga/ai/payment/dto/PaymentOrderStatusResponse.java`: remove the obsolete QR field.
- Modify `backend/src/main/java/com/manga/ai/payment/service/PaymentOrderServiceImpl.java`: generate the form, save the local order, and return the form without storing it.
- Modify `backend/src/main/java/com/manga/ai/payment/config/AlipayProperties.java`: add and validate `returnUrl`.
- Modify `backend/src/main/java/com/manga/ai/payment/service/PaymentProductCatalog.java`: replace subscription wording with one-time purchase wording.
- Modify `backend/src/main/resources/application.yml`: use `.cn` return and notify defaults with environment-variable overrides.
- Modify `backend/src/main/resources/application.yml.example`: document the same non-secret defaults.
- Modify `backend/src/test/java/com/manga/ai/payment/service/PaymentOrderServiceImplTest.java`: assert page form propagation and preserved settlement behavior.
- Modify `backend/src/test/java/com/manga/ai/payment/service/PaymentProductCatalogTest.java`: assert one-time purchase wording.

Frontend payment boundary:

- Modify `frontend/templates/payments/membership.html`: remove the QR modal/library/polling and submit the SDK-generated form.
- Create `frontend/templates/payments/payment_result.html`: query the server for the authoritative result and present paid/pending/failed states.
- Modify `frontend/apps/series/views.py`: render the authenticated result page.
- Modify `frontend/apps/series/urls.py`: add `/membership/payment/result/`.
- Modify `frontend/apps/series/tests.py`: cover proxy payloads, result-page authentication, copy, and form submission contracts.

No database migration is required. The existing nullable `payment_order.qrcode` column remains unused for backward compatibility.

---

### Task 1: Replace the Alipay Client Contract

**Files:**
- Create: `backend/src/main/java/com/manga/ai/payment/dto/AlipayPagePayRequest.java`
- Create: `backend/src/main/java/com/manga/ai/payment/dto/AlipayPagePayResult.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/client/AlipayPaymentClient.java`
- Test: `backend/src/test/java/com/manga/ai/payment/client/DefaultAlipayPaymentClientTest.java`

- [ ] **Step 1: Add the page-pay DTOs and failing client test**

Create the request contract:

```java
package com.manga.ai.payment.dto;

import java.math.BigDecimal;

public record AlipayPagePayRequest(
        String orderNo,
        String subject,
        BigDecimal totalAmount,
        String notifyUrl,
        String returnUrl,
        String timeoutExpress
) {
}
```

Create the result contract:

```java
package com.manga.ai.payment.dto;

public record AlipayPagePayResult(String paymentHtml) {
}
```

Change the interface method to:

```java
AlipayPagePayResult pagePay(AlipayPagePayRequest request);
```

Add a test that constructs `DefaultAlipayPaymentClient` with a mocked SDK client, invokes `pagePay`, captures `AlipayTradePagePayRequest`, and asserts:

```java
assertThat(result.paymentHtml()).contains("<form");
assertThat(captured.getNotifyUrl()).isEqualTo("https://www.yzmovie.cn/api/v1/payments/alipay/notify");
assertThat(captured.getReturnUrl()).isEqualTo("https://www.yzmovie.cn/membership/payment/result/");
assertThat(model.getOutTradeNo()).isEqualTo("PAY202607170001");
assertThat(model.getTotalAmount()).isEqualTo("50.00");
assertThat(model.getSubject()).isEqualTo("积分充值¥50");
assertThat(model.getProductCode()).isEqualTo("FAST_INSTANT_TRADE_PAY");
verify(sdkClient).pageExecute(captured, "POST");
```

- [ ] **Step 2: Run the client test and verify RED**

Run:

```bash
cd backend
mvn -q -Dtest=com.manga.ai.payment.client.DefaultAlipayPaymentClientTest test
```

Expected: compilation or assertion failure because `DefaultAlipayPaymentClient.pagePay` and the overridable SDK-client seam do not exist.

- [ ] **Step 3: Implement page payment with `pageExecute`**

Replace precreate imports with:

```java
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.manga.ai.payment.dto.AlipayPagePayRequest;
import com.manga.ai.payment.dto.AlipayPagePayResult;
```

Implement:

```java
@Override
public AlipayPagePayResult pagePay(AlipayPagePayRequest request) {
    ensureConfigured();
    AlipayTradePagePayRequest alipayRequest = new AlipayTradePagePayRequest();
    alipayRequest.setNotifyUrl(notBlank(request.notifyUrl()) ? request.notifyUrl() : properties.getNotifyUrl());
    alipayRequest.setReturnUrl(notBlank(request.returnUrl()) ? request.returnUrl() : properties.getReturnUrl());

    AlipayTradePagePayModel model = new AlipayTradePagePayModel();
    model.setOutTradeNo(request.orderNo());
    model.setSubject(request.subject());
    model.setTotalAmount(request.totalAmount().toPlainString());
    model.setProductCode("FAST_INSTANT_TRADE_PAY");
    model.setTimeoutExpress(notBlank(request.timeoutExpress()) ? request.timeoutExpress() : properties.getTimeoutExpress());
    alipayRequest.setBizModel(model);

    try {
        AlipayTradePagePayResponse response = client().pageExecute(alipayRequest, "POST");
        if (!notBlank(response.getBody())) {
            log.warn("支付宝电脑网站支付表单生成失败: orderNo={}", request.orderNo());
            throw new BusinessException("支付宝支付页面生成失败，请稍后重试");
        }
        return new AlipayPagePayResult(response.getBody());
    } catch (AlipayApiException e) {
        log.warn("支付宝电脑网站支付表单生成异常: orderNo={}, message={}", request.orderNo(), e.getErrMsg());
        throw new BusinessException("支付宝支付页面生成异常，请稍后重试");
    }
}
```

Make `client()` package-private so the test can override it without changing production construction:

```java
AlipayClient client() {
    return new DefaultAlipayClient(
            properties.getServerUrl(),
            properties.getAppId(),
            properties.getAppPrivateKey(),
            "json",
            properties.getCharset(),
            properties.getAlipayPublicKey(),
            properties.getSignType()
    );
}
```

- [ ] **Step 4: Run the client test and verify GREEN**

Run the command from Step 2.

Expected: `DefaultAlipayPaymentClientTest` passes and Mockito verifies `pageExecute(request, "POST")` exactly once.

- [ ] **Step 5: Commit the client boundary**

```bash
git add backend/src/main/java/com/manga/ai/payment/client \
  backend/src/main/java/com/manga/ai/payment/dto/AlipayPagePayRequest.java \
  backend/src/main/java/com/manga/ai/payment/dto/AlipayPagePayResult.java \
  backend/src/test/java/com/manga/ai/payment/client/DefaultAlipayPaymentClientTest.java
git commit -m "feat: generate alipay pc cashier form"
```

---

### Task 2: Return Payment HTML from Local Orders

**Files:**
- Delete: `backend/src/main/java/com/manga/ai/payment/dto/AlipayPrecreateRequest.java`
- Delete: `backend/src/main/java/com/manga/ai/payment/dto/AlipayPrecreateResult.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/dto/PaymentOrderCreateResponse.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/dto/PaymentOrderStatusResponse.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/service/PaymentOrderServiceImpl.java`
- Test: `backend/src/test/java/com/manga/ai/payment/service/PaymentOrderServiceImplTest.java`

- [ ] **Step 1: Change the service test to expect a payment form**

Update the mocked client and response assertions:

```java
when(alipayPaymentClient.pagePay(any())).thenReturn(new AlipayPagePayResult(
        "<form method=\"post\" action=\"https://openapi-sandbox.dl.alipaydev.com/gateway.do\"></form>"
));

PaymentOrderCreateResponse response = service.createRechargeOrder(7L, 5000);

assertThat(response.paymentHtml()).contains("openapi-sandbox.dl.alipaydev.com");
assertThat(order.getQrcode()).isNull();
verify(paymentOrderMapper).insert(orderCaptor.capture());
```

Capture `AlipayPagePayRequest` and assert the local order snapshot drives the signed request:

```java
assertThat(pagePayRequest.orderNo()).isEqualTo(order.getOrderNo());
assertThat(pagePayRequest.subject()).isEqualTo(order.getProductName());
assertThat(pagePayRequest.totalAmount()).isEqualByComparingTo("50.00");
```

- [ ] **Step 2: Run the service test and verify RED**

```bash
cd backend
mvn -q -Dtest=com.manga.ai.payment.service.PaymentOrderServiceImplTest test
```

Expected: compilation failure because the service still calls `precreate` and the response still exposes `qrcode`.

- [ ] **Step 3: Implement form propagation without storing HTML**

Change the final create-response field to:

```java
String paymentHtml
```

Remove `qrcode` from `PaymentOrderStatusResponse`.

Replace `precreateAndInsert` with:

```java
private PaymentOrderCreateResponse preparePagePayAndInsert(PaymentOrder order) {
    AlipayPagePayResult result = alipayPaymentClient.pagePay(new AlipayPagePayRequest(
            order.getOrderNo(),
            order.getProductName(),
            amountAsYuan(order.getAmountCents()),
            alipayProperties.getNotifyUrl(),
            alipayProperties.getReturnUrl(),
            alipayProperties.getTimeoutExpress()
    ));
    order.setCreatedAt(LocalDateTime.now());
    order.setUpdatedAt(LocalDateTime.now());
    paymentOrderMapper.insert(order);
    return toCreateResponse(order, result.paymentHtml());
}
```

Both create methods must return this method directly after filling the order snapshot:

```java
return preparePagePayAndInsert(order);
```

Change the mapper method to:

```java
private PaymentOrderCreateResponse toCreateResponse(PaymentOrder order, String paymentHtml) {
    return new PaymentOrderCreateResponse(
            order.getOrderNo(), order.getOrderType(), order.getProductName(),
            safe(order.getAmountCents()), safe(order.getBaseCredits()),
            safe(order.getBonusCredits()), safe(order.getTotalCredits()),
            order.getStatus(), paymentHtml
    );
}
```

Remove `order.getQrcode()` from `toStatusResponse` and delete the obsolete precreate DTO files.

- [ ] **Step 4: Run service tests and verify GREEN**

Run the command from Step 2.

Expected: page form assertions and all settlement/idempotency tests pass.

- [ ] **Step 5: Commit order response changes**

```bash
git add backend/src/main/java/com/manga/ai/payment/dto \
  backend/src/main/java/com/manga/ai/payment/service/PaymentOrderServiceImpl.java \
  backend/src/test/java/com/manga/ai/payment/service/PaymentOrderServiceImplTest.java
git commit -m "refactor: return alipay cashier form from orders"
```

---

### Task 3: Add Return URL Configuration and One-Time Purchase Copy

**Files:**
- Modify: `backend/src/main/java/com/manga/ai/payment/config/AlipayProperties.java`
- Modify: `backend/src/main/java/com/manga/ai/payment/service/PaymentProductCatalog.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application.yml.example`
- Test: `backend/src/test/java/com/manga/ai/payment/service/PaymentProductCatalogTest.java`
- Test: `backend/src/test/java/com/manga/ai/payment/client/DefaultAlipayPaymentClientTest.java`

- [ ] **Step 1: Add failing configuration and copy assertions**

Add assertions:

```java
assertThat(properties.getReturnUrl())
        .isEqualTo("https://www.yzmovie.cn/membership/payment/result/");
assertThat(products).allSatisfy(product -> {
    if (product.enabled()) {
        assertThat(product.buttonText()).doesNotContain("订阅");
    }
});
```

- [ ] **Step 2: Run tests and verify RED**

```bash
cd backend
mvn -q -Dtest=com.manga.ai.payment.client.DefaultAlipayPaymentClientTest,com.manga.ai.payment.service.PaymentProductCatalogTest test
```

Expected: missing `returnUrl` accessors and subscription wording assertion failure.

- [ ] **Step 3: Implement configuration and wording**

Add to `AlipayProperties`:

```java
private String returnUrl;

public String getReturnUrl() {
    return returnUrl;
}

public void setReturnUrl(String returnUrl) {
    this.returnUrl = returnUrl;
}
```

Require both callback URLs in `configured()`:

```java
return notBlank(serverUrl)
        && notBlank(appId)
        && notBlank(appPrivateKey)
        && notBlank(alipayPublicKey)
        && notBlank(notifyUrl)
        && notBlank(returnUrl);
```

Use these YAML values in both application files:

```yaml
  notify-url: ${ALIPAY_NOTIFY_URL:https://www.yzmovie.cn/api/v1/payments/alipay/notify}
  return-url: ${ALIPAY_RETURN_URL:https://www.yzmovie.cn/membership/payment/result/}
```

Change enabled membership buttons to:

```java
if (period == MembershipPeriod.SINGLE_MONTH) {
    return "购买" + tier.getDisplayName();
}
return "购买" + period.getLabel() + tier.getDisplayName();
```

- [ ] **Step 4: Run tests and verify GREEN**

Run the command from Step 2.

Expected: both test classes pass.

- [ ] **Step 5: Commit config and copy**

```bash
git add backend/src/main/java/com/manga/ai/payment/config/AlipayProperties.java \
  backend/src/main/java/com/manga/ai/payment/service/PaymentProductCatalog.java \
  backend/src/main/resources/application.yml \
  backend/src/main/resources/application.yml.example \
  backend/src/test/java/com/manga/ai/payment
git commit -m "fix: configure alipay return flow"
```

---

### Task 4: Replace the Membership QR Modal with Cashier Submission

**Files:**
- Modify: `frontend/apps/series/tests.py`
- Modify: `frontend/templates/payments/membership.html`

- [ ] **Step 1: Add failing template contract tests**

Add tests that read the rendered template and assert:

```python
self.assertContains(response, '前往支付宝支付')
self.assertContains(response, '单次支付，到期自动结束')
self.assertNotContains(response, 'qrcode@1.5.3')
self.assertNotContains(response, 'payQrCanvas')

template = response.content.decode('utf-8')
self.assertIn('data.data.paymentHtml', template)
self.assertIn("form.submit()", template)
self.assertIn("openapi.alipay.com", template)
self.assertIn("openapi-sandbox.dl.alipaydev.com", template)
```

Update the proxy test fixture to return:

```python
{
    'orderNo': 'PAY202607170001',
    'paymentHtml': '<form method="post" action="https://openapi-sandbox.dl.alipaydev.com/gateway.do"></form>',
    'totalCredits': 1150,
}
```

- [ ] **Step 2: Run the frontend tests and verify RED**

```bash
cd frontend
python3 manage.py test apps.series.tests.MembershipPaymentPageTests
```

Expected: assertions fail because the QR modal and QR library still exist and no payment form is submitted.

- [ ] **Step 3: Implement same-tab form submission**

Delete the QR modal markup, modal CSS, QR script, `currentOrderNo`, `pollTimer`, `openPayModal`, `closePayModal`, `renderQr`, `startPolling`, and `syncCurrentOrder`.

Pass the clicked button into purchase methods:

```javascript
onclick="purchaseMembership('${product.code}', this)"
onclick="purchaseRecharge(${item.amountCents}, this)"
```

Use:

```javascript
async function createOrder(url, payload, button) {
    const originalText = button ? button.textContent : '';
    if (button) {
        button.disabled = true;
        button.textContent = '正在前往支付宝';
    }
    try {
        const response = await fetch(url, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
        const data = await response.json();
        if (response.status === 401 || data.code === 401) {
            window.location.href = '/auth/login/';
            return;
        }
        if (data.code !== 200) {
            throw new Error(data.message || '创建订单失败');
        }
        submitAlipayForm(data.data.paymentHtml);
    } catch (error) {
        if (button) {
            button.disabled = false;
            button.textContent = originalText;
        }
        alert(error.message || '创建订单失败');
    }
}
```

Submit only expected Alipay gateway forms:

```javascript
function submitAlipayForm(paymentHtml) {
    if (!paymentHtml) throw new Error('支付宝支付表单为空');
    const container = document.createElement('div');
    container.hidden = true;
    container.innerHTML = paymentHtml;
    const form = container.querySelector('form');
    if (!form) throw new Error('支付宝支付表单无效');

    const action = new URL(form.action, window.location.origin);
    const allowedHosts = ['openapi.alipay.com', 'openapi-sandbox.dl.alipaydev.com'];
    if (action.protocol !== 'https:' || !allowedHosts.includes(action.hostname)) {
        throw new Error('支付宝支付地址无效');
    }

    document.body.appendChild(container);
    form.submit();
}
```

Change card copy to “前往支付宝支付”, “单次支付，到期自动结束”, and “购买” wording.

- [ ] **Step 4: Run the frontend tests and verify GREEN**

Run the command from Step 2.

Expected: all membership payment page tests pass.

- [ ] **Step 5: Commit membership page changes**

```bash
git add frontend/apps/series/tests.py frontend/templates/payments/membership.html
git commit -m "feat: redirect membership payments to alipay cashier"
```

---

### Task 5: Add the Authoritative Payment Result Page

**Files:**
- Create: `frontend/templates/payments/payment_result.html`
- Modify: `frontend/apps/series/views.py`
- Modify: `frontend/apps/series/urls.py`
- Modify: `frontend/apps/series/tests.py`

- [ ] **Step 1: Add failing result-page tests**

Add tests:

```python
def test_payment_result_requires_login_and_preserves_return_path(self):
    response = self.client.get(
        '/membership/payment/result/?out_trade_no=PAY202607170001'
    )
    self.assertRedirects(
        response,
        '/auth/login/',
        fetch_redirect_response=False,
    )
    self.assertEqual(
        self.client.session['next'],
        '/membership/payment/result/?out_trade_no=PAY202607170001',
    )

def test_payment_result_renders_order_sync_contract_for_logged_in_user(self):
    session = self.client.session
    session['token'] = 'token-pay'
    session.save()
    response = self.client.get(
        '/membership/payment/result/?out_trade_no=PAY202607170001'
    )
    self.assertEqual(response.status_code, 200)
    self.assertContains(response, '正在确认支付结果')
    self.assertContains(response, 'PAY202607170001')
    self.assertContains(
        response,
        '/api/payments/orders/${encodeURIComponent(ORDER_NO)}/sync/',
    )
```

- [ ] **Step 2: Run result-page tests and verify RED**

```bash
cd frontend
python3 manage.py test apps.series.tests.MembershipPaymentPageTests
```

Expected: 404 for the result route.

- [ ] **Step 3: Add the authenticated view and route**

Add the route:

```python
path('membership/payment/result/', views.payment_result_page, name='payment_result'),
```

Add the view:

```python
def payment_result_page(request):
    """支付宝同步返回页；最终结果仍由服务端查询确认。"""
    if not request.session.get('token'):
        request.session['next'] = request.get_full_path()
        return redirect('/auth/login/')
    order_no = (request.GET.get('out_trade_no') or '').strip()
    return render(request, 'payments/payment_result.html', {'order_no': order_no})
```

Ensure `redirect` is imported from `django.shortcuts` in `views.py`.

- [ ] **Step 4: Implement result-page state handling**

The template must embed the server-escaped order number:

```javascript
const ORDER_NO = '{{ order_no|escapejs }}';
```

Use the existing sync proxy:

```javascript
async function confirmPayment() {
    if (!ORDER_NO) {
        renderResult('failed', '订单号缺失', '请返回会员页重新选择套餐。');
        return;
    }
    renderResult('loading', '正在确认支付结果', '请稍候，不要重复付款。');
    try {
        const response = await fetch(
            `/api/payments/orders/${encodeURIComponent(ORDER_NO)}/sync/`,
            {method: 'POST'}
        );
        const payload = await response.json();
        if (response.status === 401 || payload.code === 401) {
            window.location.href = '/auth/login/';
            return;
        }
        if (payload.code !== 200 || !payload.data) {
            throw new Error(payload.message || '订单查询失败');
        }
        if (payload.data.status === 'PAID') {
            renderResult('paid', '支付成功', '会员或积分已到账。');
        } else if (['CLOSED', 'FAILED'].includes(payload.data.status)) {
            renderResult('failed', '订单未完成', '本次支付没有完成。');
        } else {
            renderResult('pending', '等待支付确认', '尚未确认到账，可稍后重新查询。');
        }
    } catch (error) {
        renderResult('pending', '暂时无法确认', error.message || '请稍后重新查询。');
    }
}
```

Provide “重新查询” and “返回会员页” commands. Run `confirmPayment()` once on `DOMContentLoaded`.

- [ ] **Step 5: Run result-page tests and verify GREEN**

Run the command from Step 2.

Expected: authentication, preserved return path, and sync-contract tests pass.

- [ ] **Step 6: Commit the result page**

```bash
git add frontend/apps/series/views.py frontend/apps/series/urls.py \
  frontend/apps/series/tests.py frontend/templates/payments/payment_result.html
git commit -m "feat: add alipay payment result page"
```

---

### Task 6: Full Regression and Safety Verification

**Files:**
- Modify only files implicated by a failing verification.

- [ ] **Step 1: Run payment-specific backend tests**

```bash
cd backend
mvn -q -Dtest=com.manga.ai.payment.client.DefaultAlipayPaymentClientTest,com.manga.ai.payment.service.PaymentOrderServiceImplTest,com.manga.ai.payment.service.PaymentProductCatalogTest test
```

Expected: all payment tests pass.

- [ ] **Step 2: Run the full backend suite**

```bash
cd backend
mvn -q test
```

Expected: exit code 0 with no test failures.

- [ ] **Step 3: Run frontend payment and regression tests**

```bash
cd frontend
python3 manage.py test apps.series.tests apps.auth.tests apps.role.tests
```

Expected: all tests pass.

- [ ] **Step 4: Scan for obsolete product code and secret leakage**

```bash
rg -n "AlipayTradePrecreate|alipay\.trade\.precreate|qrcode@1\.5\.3|payQrCanvas|www\.yzmovie\.com" backend frontend
rg -n "ghp_|BEGIN PRIVATE KEY|BEGIN RSA PRIVATE KEY" . \
  --glob '!**/.git/**' --glob '!**/target/**'
```

Expected: no obsolete payment-flow matches and no new secret material. Existing unrelated configuration findings must not be modified as part of this task.

- [ ] **Step 5: Verify generated configuration without printing secrets**

Start the backend only with the required `ALIPAY_*` environment variables supplied through the shell or deployment secret store. Check logs for successful startup without printing variable values.

Expected: application starts and `AlipayProperties.configured()` is true; no private key appears in logs.

- [ ] **Step 6: Browser verification with local services**

Start backend on `8081` and frontend on `8001`. Verify while logged in:

1. `/membership/` shows one-time purchase copy and no QR modal.
2. Clicking a membership or recharge button receives `paymentHtml` and submits the form.
3. Browser navigation targets `openapi-sandbox.dl.alipaydev.com` in sandbox mode.
4. `/membership/payment/result/?out_trade_no=PAY202607170001` renders paid, pending, or failed without trusting URL status fields.
5. Desktop and 390px mobile layouts have no overlapping text or horizontal overflow.

- [ ] **Step 7: Handle any verification failure through its owning task**

If verification exposes a defect, return to the task that owns the failing behavior, add a focused failing test, implement the minimum fix, rerun that task's exact test command, and stage only the files listed in that task. If no files changed, do not create an empty commit.

---

### Task 7: Sandbox End-to-End Payment Check

**Files:**
- No source changes unless a reproducible defect is found through the TDD loop.

- [ ] **Step 1: Generate a sandbox cashier form**

With local services running and sandbox variables set, log in to the application, choose the ¥50 recharge package, and click “前往支付宝支付”.

Expected: the browser displays the official sandbox PC cashier; the page is not a local QR modal.

- [ ] **Step 2: Complete payment with the sandbox buyer**

Use the user-provided sandbox buyer credentials in the sandbox cashier or sandbox wallet. Do not place credentials in source, logs, shell history, screenshots, or test fixtures.

Expected: Alipay accepts the payment and returns to `https://www.yzmovie.cn/membership/payment/result/` when testing through the publicly reachable environment.

- [ ] **Step 3: Confirm authoritative settlement**

Use the result-page query or authenticated sync endpoint for the returned `out_trade_no`.

Expected:

```text
order.status = PAID
user credits increase by the order snapshot total
one credit record is created
repeated query/notify does not grant credits again
```

- [ ] **Step 4: Record production prerequisites**

Before production launch, confirm in the Alipay merchant console:

1. The application is online.
2. The merchant has activated PC website payment.
3. `www.yzmovie.cn` is reachable over HTTPS and has valid ICP/business ownership for the application.
4. The production gateway and production key pair replace sandbox values in the deployment secret store.
5. The public notify URL accepts Alipay POST callbacks without redirects and returns exactly `success` after successful verification and settlement.

Expected: prerequisites are recorded in the deployment checklist; no production secret is added to Git.
