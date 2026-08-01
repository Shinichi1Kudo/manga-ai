# Alipay Minimal QR Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a normal Alipay QR payment show only the product, amount, QR code, expiry, and order number while payment-status polling remains silent in the background.

**Architecture:** Keep the existing payment state machine, five-second polling, backend sync endpoint, and settlement paths unchanged. Add one parent DOM hook for the QR actions, let `render()` hide status and actions during `QR_READY`/`QUERYING`, and reveal only the status line for terminal or error states. Preserve all existing action elements and backend recovery endpoints so the change remains presentation-only.

**Tech Stack:** Django templates and tests, vanilla JavaScript, Django staticfiles.

---

### Task 1: Specify the minimal QR presentation contract

**Files:**
- Modify: `frontend/apps/series/test_payment_qr.py`
- Test: `frontend/apps/series/test_payment_qr.py`

- [ ] **Step 1: Write the failing template contract test**

Extend `test_payment_center_current_view_has_stable_state_and_action_hooks` after the existing element-ID assertions:

```python
        status_start = template.index('<p id="paymentQrStatus"')
        status_tag = template[status_start:template.index('>', status_start) + 1]
        self.assertIn(' hidden', status_tag)

        actions_start = template.index('<div id="paymentQrActions"')
        actions_tag = template[actions_start:template.index('>', actions_start) + 1]
        self.assertIn(' hidden', actions_tag)

        order_start = template.index('<div class="payment-center-order-number"')
        order_tag = template[order_start:template.index('>', order_start) + 1]
        self.assertNotIn(' hidden', order_tag)
```

Add `paymentQrActions` to the method's element-ID tuple so the test also requires a stable controller hook.

- [ ] **Step 2: Write the failing controller contract test**

Add this test to `MembershipQrPaymentTests`:

```python
    def test_payment_center_keeps_background_queries_visually_silent(self):
        controller = (
            Path(settings.BASE_DIR) / 'static/js/payment-center.js'
        ).read_text(encoding='utf-8')

        self.assertIn("qrActions: byId('paymentQrActions')", controller)
        self.assertIn('const showQrStatus =', controller)
        self.assertIn('PaymentCenterCore.UI.PAID', controller)
        self.assertIn('PaymentCenterCore.UI.EXPIRED', controller)
        self.assertIn('PaymentCenterCore.UI.QR_RENDER_ERROR', controller)
        self.assertIn('PaymentCenterCore.UI.CREATE_ERROR', controller)
        self.assertIn('this.elements.qrStatus.hidden = !showQrStatus;', controller)
        self.assertIn('this.elements.qrActions.hidden = true;', controller)
        self.assertNotIn('正在查询支付状态', controller)
        self.assertIn(
            'setInterval(() => this.syncCurrentOrder(false), 5000)',
            controller,
        )
        self.assertIn('/sync/`', controller)
```

- [ ] **Step 3: Run the focused tests and verify RED**

Run:

```bash
cd frontend
python manage.py test \
  apps.series.test_payment_qr.MembershipQrPaymentTests.test_payment_center_current_view_has_stable_state_and_action_hooks \
  apps.series.test_payment_qr.MembershipQrPaymentTests.test_payment_center_keeps_background_queries_visually_silent
```

Expected: FAIL because `paymentQrActions`, the initial `hidden` attributes, the render visibility contract, and silent query copy do not exist yet.

- [ ] **Step 4: Commit the failing tests**

```bash
git add frontend/apps/series/test_payment_qr.py
git commit -m "test: specify minimal alipay qr display"
```

### Task 2: Implement state-aware minimal QR rendering

**Files:**
- Modify: `frontend/templates/payments/membership.html:368-380`
- Modify: `frontend/static/js/payment-center.js:103-110,895-1013`
- Test: `frontend/apps/series/test_payment_qr.py`

- [ ] **Step 1: Add stable hidden hooks to the template**

Change the QR status and actions opening tags to:

```html
                <p id="paymentQrStatus" class="payment-center-order-status" role="status"
                   aria-live="polite" hidden>等待扫码支付</p>
```

```html
                <div id="paymentQrActions" class="payment-center-actions" hidden>
```

Do not add `hidden` to `.payment-center-order-number`.

- [ ] **Step 2: Map the action container in the controller**

Add the parent hook next to the existing QR elements:

```javascript
                qrActions: byId('paymentQrActions'),
```

- [ ] **Step 3: Render status only for terminal and error states**

At the beginning of the existing `if (order) { ... }` block in `render()`, after computing QR visibility and before calling `updateOrderStatus()`, add:

```javascript
                const showQrStatus = ui === PaymentCenterCore.UI.PAID
                    || ui === PaymentCenterCore.UI.EXPIRED
                    || ui === PaymentCenterCore.UI.QR_RENDER_ERROR
                    || ui === PaymentCenterCore.UI.CREATE_ERROR;
                this.elements.qrStatus.hidden = !showQrStatus;
                this.elements.qrActions.hidden = true;
```

Keep `qrContainer.hidden = !showQrCanvas;` unchanged so the QR stays visible during `QUERYING`.

- [ ] **Step 4: Remove visible query-progress copy**

In `updateOrderStatus()`, delete:

```javascript
            if (ui === PaymentCenterCore.UI.QUERYING) text = '正在查询支付状态';
```

The default value remains `等待扫码支付`, but the status row stays hidden for both `QR_READY` and `QUERYING`.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run:

```bash
cd frontend
python manage.py test \
  apps.series.test_payment_qr.MembershipQrPaymentTests.test_payment_center_current_view_has_stable_state_and_action_hooks \
  apps.series.test_payment_qr.MembershipQrPaymentTests.test_payment_center_keeps_background_queries_visually_silent
```

Expected: both tests PASS.

- [ ] **Step 6: Run the full QR payment test module**

Run:

```bash
cd frontend
python manage.py test apps.series.test_payment_qr
```

Expected: all tests PASS with no failures or errors.

- [ ] **Step 7: Commit the implementation**

```bash
git add frontend/templates/payments/membership.html \
        frontend/static/js/payment-center.js
git commit -m "fix: simplify alipay qr payment display"
```

### Task 3: Publish and verify static assets

**Files:**
- Modify: `frontend/staticfiles/js/payment-center.js` (generated by collectstatic)
- Verify: `frontend/static/js/payment-center.js`
- Verify: `frontend/staticfiles/js/payment-center.js`

- [ ] **Step 1: Collect static files**

Run:

```bash
cd frontend
python manage.py collectstatic --noinput
```

Expected: command exits with status 0 and copies the updated payment-center controller into `staticfiles`.

- [ ] **Step 2: Verify source and deployed controller match**

Run:

```bash
cmp frontend/static/js/payment-center.js frontend/staticfiles/js/payment-center.js
```

Expected: exit status 0 with no output.

- [ ] **Step 3: Run final payment regressions**

Run:

```bash
cd frontend
python manage.py test apps.series.test_payment_qr
```

Expected: all tests PASS with no failures or errors.

- [ ] **Step 4: Check the final diff**

Run:

```bash
git diff --check HEAD~2..HEAD
git status --short
```

Expected: no whitespace errors; only known pre-existing untracked runtime files remain.

- [ ] **Step 5: Commit collected static output when tracked**

If `git status --short frontend/staticfiles/js/payment-center.js` reports a tracked modification, run:

```bash
git add frontend/staticfiles/js/payment-center.js
git commit -m "build: refresh payment center static asset"
```

If it reports no change because `staticfiles` is ignored or untracked, do not force-add generated output.
