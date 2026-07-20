import json
from pathlib import Path
from unittest.mock import Mock, patch

from django.conf import settings
from django.contrib.staticfiles import finders
from django.test import Client, TestCase
from django.urls import resolve

from api.backend_client import BackendAPIError


class MembershipQrPaymentTests(TestCase):
    def test_membership_page_sets_csrf_cookie(self):
        client = Client(enforce_csrf_checks=True)

        response = client.get('/membership/')

        self.assertEqual(response.status_code, 200)
        self.assertIn('csrftoken', response.cookies)

    def test_local_qrcode_vendor_is_pinned_and_licensed(self):
        vendor_dir = Path(settings.BASE_DIR) / 'static/vendor/qrcode'
        self.assertTrue((vendor_dir / 'package.json').is_file())
        package = json.loads((vendor_dir / 'package.json').read_text(encoding='utf-8'))

        self.assertEqual(package['name'], 'qrcode')
        self.assertEqual(package['version'], '1.5.3')
        self.assertTrue((vendor_dir / 'qrcode.min.js').is_file())
        self.assertIn('MIT License', (vendor_dir / 'LICENSE').read_text(encoding='utf-8'))

    def test_payment_qr_runtime_assets_are_discoverable(self):
        for asset in (
            'vendor/qrcode/qrcode.min.js',
            'js/payment-center-core.js',
            'js/payment-center.js',
            'css/payment-center.css',
        ):
            self.assertIsNotNone(finders.find(asset), asset)

        template = (
            Path(settings.BASE_DIR) / 'templates/payments/membership.html'
        ).read_text(encoding='utf-8')
        self.assertNotIn('cdn.jsdelivr.net', template)
        self.assertNotIn('unpkg.com', template)

    def test_payment_center_controller_has_authoritative_state_contract(self):
        controller_path = Path(settings.BASE_DIR) / 'static/js/payment-center.js'
        self.assertTrue(controller_path.is_file())
        controller = controller_path.read_text(encoding='utf-8')

        for state in (
            'CHOOSING', 'CREATING', 'CREATE_ERROR', 'QR_READY',
            'QR_RENDER_ERROR', 'REFRESHING_QR', 'QUERYING', 'PAID', 'EXPIRED',
            'PAGE_REDIRECTING',
        ):
            self.assertIn(f'PaymentCenterCore.UI.{state}', controller)

        self.assertIn('class PaymentCenter {', controller)
        self.assertIn('window.paymentCenter = new PaymentCenter(', controller)
        self.assertIn(
            'PaymentCenterCore.renderQr(window.QRCode, offscreenCanvas, order.qrCode',
            controller,
        )
        self.assertIn('PaymentCenterCore.withRenderFailure(', controller)
        self.assertNotIn("currentQrOrder.status = 'FAILED'", controller)
        self.assertNotIn("this.currentOrder.status = 'FAILED'", controller)

    def test_payment_center_recovery_uses_same_order_and_safe_switch_endpoint(self):
        controller = (
            Path(settings.BASE_DIR) / 'static/js/payment-center.js'
        ).read_text(encoding='utf-8')

        self.assertIn("fetch('/api/payments/orders/pending/')", controller)
        self.assertIn("/${encodeURIComponent(orderNo)}/resume/`", controller)
        self.assertIn(
            "/${encodeURIComponent(this.currentOrder.orderNo)}/switch-to-page/`",
            controller,
        )
        self.assertIn('order.paymentReady', controller)
        self.assertIn('order.qrCode', controller)
        self.assertIn('await this.showQrOrder(order);', controller)

        self.assertIn(
            "/${encodeURIComponent(sourceOrderNo)}/refresh-qr/`",
            controller,
        )

        resume_start = controller.index('async resume(orderNo)')
        resume_end = controller.index('async switchCurrentQrToPage()', resume_start)
        resume_source = controller[resume_start:resume_end]
        self.assertNotIn('/api/payments/membership/orders/', resume_source)
        self.assertNotIn('/api/payments/recharge/orders/', resume_source)

        switch_start = controller.index('async switchCurrentQrToPage()')
        switch_end = controller.index('async syncCurrentOrder(', switch_start)
        switch_source = controller[switch_start:switch_end]
        self.assertIn('/switch-to-page/', switch_source)
        self.assertNotIn('/api/payments/membership/orders/', switch_source)
        self.assertNotIn('/api/payments/recharge/orders/', switch_source)

    def test_payment_center_posts_share_csrf_and_safe_page_form_contracts(self):
        controller = (
            Path(settings.BASE_DIR) / 'static/js/payment-center.js'
        ).read_text(encoding='utf-8')

        self.assertIn("getCookie('csrftoken')", controller)
        self.assertIn("'X-CSRFToken': csrfToken", controller)
        for method_call in (
            'this.postJson(endpoint, payload)',
            'this.postJson(`/api/payments/orders/${encodeURIComponent(orderNo)}/resume/`)',
            'this.postJson(`/api/payments/orders/${encodeURIComponent(this.currentOrder.orderNo)}/switch-to-page/`)',
            'this.postJson(`/api/payments/orders/${encodeURIComponent(sourceOrderNo)}/refresh-qr/`)',
            'this.postJson(`/api/payments/orders/${encodeURIComponent(orderNo)}/sync/`)',
        ):
            self.assertIn(method_call, controller)

        for cashier_contract in (
            'new DOMParser().parseFromString(paymentHtml,',
            "sourceForms.length !== 1",
            "sourceForm.method.toUpperCase() !== 'POST'",
            "action.protocol !== 'https:'",
            "action.pathname !== '/gateway.do'",
            "'openapi.alipay.com'",
            "'openapi-sandbox.dl.alipaydev.com'",
            "sourceForm.querySelectorAll('input[name]')",
            "document.createElement('form')",
            "document.createElement('input')",
            "input.type = 'hidden'",
            "form.target = '_self'",
            'HTMLFormElement.prototype.submit.call(form)',
        ):
            self.assertIn(cashier_contract, controller)
        self.assertNotIn('innerHTML = paymentHtml', controller)
        self.assertNotIn('insertAdjacentHTML', controller)
        self.assertNotIn('document.write', controller)

    def test_payment_center_pending_dom_and_lifecycle_are_recoverable(self):
        controller = (
            Path(settings.BASE_DIR) / 'static/js/payment-center.js'
        ).read_text(encoding='utf-8')

        render_start = controller.index('renderPending(orders)')
        render_end = controller.index('async resume(orderNo)', render_start)
        render_source = controller[render_start:render_end]
        self.assertIn('document.createElement(', render_source)
        self.assertIn('.textContent =', render_source)
        self.assertIn('replaceChildren(', render_source)
        self.assertNotIn('.innerHTML', render_source)

        for lifecycle_contract in (
            'this.root.hidden = false;',
            "this.root.removeAttribute('inert');",
            "this.root.setAttribute('aria-hidden', 'false');",
            'this.root.hidden = true;',
            "this.root.setAttribute('inert', '');",
            "this.root.setAttribute('aria-hidden', 'true');",
            "document.body.classList.add('payment-center-open')",
            "document.body.classList.remove('payment-center-open')",
            "event.key !== 'Tab'",
            'this.focusReturn.focus()',
            "document.addEventListener('visibilitychange'",
            'setInterval(() => this.syncCurrentOrder(false), 5000)',
            'setInterval(() => this.updateCountdown(), 1000)',
            'window.loadUserCredits',
            'window.loadPendingPaymentCount',
        ):
            self.assertIn(lifecycle_contract, controller)

        self.assertIn("params.get('paymentCenter') === 'pending'", controller)
        self.assertIn("window.location.pathname !== '/membership/'", controller)
        self.assertIn('window.closeMobileNav()', controller)

    def test_payment_center_refreshes_qr_without_closing_current_panel(self):
        controller = (
            Path(settings.BASE_DIR) / 'static/js/payment-center.js'
        ).read_text(encoding='utf-8')

        self.assertIn('this.refreshInFlight = false;', controller)
        self.assertIn("qrRefresh: byId('paymentQrRefresh')", controller)
        self.assertIn(
            "this.elements.qrRefresh.addEventListener('click', () => this.refreshCurrentQr());",
            controller,
        )

        refresh_start = controller.index('async refreshCurrentQr()')
        refresh_end = controller.index('async switchCurrentQrToPage()', refresh_start)
        refresh_source = controller[refresh_start:refresh_end]
        self.assertIn("window.confirm('刷新后旧二维码将失效，是否继续？')", refresh_source)
        self.assertIn('const sourceOrderNo = this.currentOrder.orderNo;', refresh_source)
        self.assertLess(
            refresh_source.index('this.stopPolling();'),
            refresh_source.index('const operation = ++this.operationSequence;'),
        )
        self.assertIn('this.refreshInFlight = true;', refresh_source)
        self.assertIn('this.invalidateQrDisplay();', refresh_source)
        self.assertIn('PaymentCenterCore.UI.REFRESHING_QR', refresh_source)
        self.assertIn(
            'this.postJson(`/api/payments/orders/${encodeURIComponent(sourceOrderNo)}/refresh-qr/`)',
            refresh_source,
        )
        self.assertIn('this.setCurrentOrder(order);', refresh_source)
        self.assertIn('await this.handleOrderResponse(order);', refresh_source)
        self.assertIn('await this.loadPending();', refresh_source)
        self.assertIn('window.loadPendingPaymentCount()', refresh_source)
        self.assertIn('this.restoreCurrentQrLifecycle();', refresh_source)
        self.assertIn('this.refreshInFlight = false;', refresh_source)
        self.assertNotIn('this.close()', refresh_source)

        for method_name, next_method in (
            ('async resume(orderNo)', 'async refreshCurrentQr()'),
            ('async switchCurrentQrToPage()', 'async syncCurrentOrder('),
            ('async syncCurrentOrder(', 'restoredUiAfterSync(previousUi)'),
        ):
            method_start = controller.index(method_name)
            method_end = controller.index(next_method, method_start)
            self.assertIn('this.refreshInFlight', controller[method_start:method_end])

        render_start = controller.index('render() {')
        render_end = controller.index('updateOrderStatus() {', render_start)
        render_source = controller[render_start:render_end]
        self.assertIn('PaymentCenterCore.UI.REFRESHING_QR', render_source)
        self.assertIn('this.elements.qrRefresh.hidden = !(', render_source)
        self.assertIn('this.elements.qrRefresh.disabled = this.refreshInFlight', render_source)
        self.assertIn('ui !== PaymentCenterCore.UI.REFRESHING_QR', render_source)

    def test_payment_center_close_invalidates_late_redirects_and_traps_visible_focus(self):
        controller = (
            Path(settings.BASE_DIR) / 'static/js/payment-center.js'
        ).read_text(encoding='utf-8')

        self.assertIn('this.operationSequence = 0;', controller)
        close_start = controller.index('close() {')
        close_end = controller.index('handleKeydown(event)', close_start)
        self.assertIn('this.operationSequence += 1;', controller[close_start:close_end])
        self.assertIn('const operation = ++this.operationSequence;', controller)
        self.assertIn(
            'if (operation !== this.operationSequence || this.root.hidden) return;',
            controller,
        )
        self.assertIn(
            "element.closest('[hidden], [inert], [aria-hidden=\"true\"]')",
            controller,
        )

    def test_payment_center_purchase_and_cashier_actions_are_not_duplicated(self):
        controller = (
            Path(settings.BASE_DIR) / 'static/js/payment-center.js'
        ).read_text(encoding='utf-8')

        purchase_start = controller.index('this.currentPurchase = {')
        purchase_end = controller.index('\n            };', purchase_start)
        purchase_source = controller[purchase_start:purchase_end]
        self.assertEqual(purchase_source.count('orderType:'), 1)

        cashier_start = controller.index('submitAlipayForm(paymentHtml)')
        cashier_end = controller.index('normalizeOrder(rawOrder', cashier_start)
        cashier_source = controller[cashier_start:cashier_end]
        self.assertNotIn('form.submit()', cashier_source)
        self.assertEqual(
            cashier_source.count('HTMLFormElement.prototype.submit.call(form)'),
            1,
        )

    def test_payment_center_tab_switch_does_not_cancel_payment_operations(self):
        controller = (
            Path(settings.BASE_DIR) / 'static/js/payment-center.js'
        ).read_text(encoding='utf-8')

        bind_start = controller.index('bind() {')
        pending_listener_start = controller.index(
            'this.elements.pendingTab.addEventListener', bind_start
        )
        pending_listener_end = controller.index(
            'this.elements.methodQr.addEventListener', pending_listener_start
        )
        pending_listener = controller[pending_listener_start:pending_listener_end]
        self.assertIn('this.showPending()', pending_listener)
        self.assertNotIn('operationSequence', pending_listener)

        open_pending_start = controller.index('openPending(trigger = null)')
        open_pending_end = controller.index('showCurrent()', open_pending_start)
        self.assertNotIn(
            'operationSequence',
            controller[open_pending_start:open_pending_end],
        )

    def test_payment_center_entry_restores_created_order_unless_deep_link_forces_pending(self):
        controller = (
            Path(settings.BASE_DIR) / 'static/js/payment-center.js'
        ).read_text(encoding='utf-8')

        click_start = controller.index('handleDocumentClick(event)')
        click_end = controller.index('ensureAuthenticated()', click_start)
        click_source = controller[click_start:click_end]
        self.assertIn('this.openFromEntry(pendingButton);', click_source)
        self.assertNotIn('this.openPending(pendingButton);', click_source)
        self.assertIn('window.closeMobileNav()', click_source)

        entry_start = controller.index('openFromEntry(trigger = null)')
        entry_end = controller.index('openPurchase(purchase, trigger)', entry_start)
        entry_source = controller[entry_start:entry_end]
        self.assertIn("this.currentOrder.status === 'CREATED'", entry_source)
        self.assertIn('this.open(trigger);', entry_source)
        self.assertIn('this.showCurrent();', entry_source)
        self.assertIn('this.restoreCurrentQrLifecycle();', entry_source)
        self.assertIn('this.syncCurrentOrder(false);', entry_source)
        self.assertIn('this.openPending(trigger);', entry_source)

        deep_link_start = controller.index("params.get('paymentCenter') === 'pending'")
        deep_link_source = controller[deep_link_start:]
        self.assertIn(
            "window.paymentCenter.openPending(document.getElementById('pendingPaymentButton'))",
            deep_link_source,
        )

    def test_payment_center_visibility_restores_render_error_polling(self):
        controller = (
            Path(settings.BASE_DIR) / 'static/js/payment-center.js'
        ).read_text(encoding='utf-8')

        visibility_start = controller.index('handleVisibilityChange()')
        visibility_end = controller.index('render() {', visibility_start)
        visibility_source = controller[visibility_start:visibility_end]
        self.assertIn('this.resumePollingOnVisibility = true;', visibility_source)
        self.assertIn('this.syncCurrentOrder(false);', visibility_source)

        sync_start = controller.index('async syncCurrentOrder(')
        sync_end = controller.index('submitAlipayForm(paymentHtml)', sync_start)
        sync_source = controller[sync_start:sync_end]
        self.assertIn('PaymentCenterCore.UI.QR_RENDER_ERROR', sync_source)
        self.assertIn('this.resumePollingAfterVisibility();', sync_source)
        self.assertIn('this.startPolling();', controller)

    def test_payment_center_resume_is_deduplicated_per_order(self):
        controller = (
            Path(settings.BASE_DIR) / 'static/js/payment-center.js'
        ).read_text(encoding='utf-8')

        self.assertIn('this.resumeInFlight = new Set();', controller)
        resume_start = controller.index('async resume(orderNo)')
        resume_end = controller.index('async switchCurrentQrToPage()', resume_start)
        resume_source = controller[resume_start:resume_end]
        self.assertIn('this.resumeInFlight.has(orderNo)', resume_source)
        self.assertIn('this.resumeInFlight.add(orderNo);', resume_source)
        self.assertIn('this.resumeInFlight.delete(orderNo);', resume_source)
        self.assertIn('button.disabled = true;', resume_source)
        self.assertIn('button.disabled = false;', resume_source)

    def test_payment_center_resume_and_switch_serialize_with_polling(self):
        controller = (
            Path(settings.BASE_DIR) / 'static/js/payment-center.js'
        ).read_text(encoding='utf-8')

        resume_start = controller.index('async resume(orderNo)')
        resume_end = controller.index('async switchCurrentQrToPage()', resume_start)
        resume_source = controller[resume_start:resume_end]
        self.assertLess(
            resume_source.index('this.stopPolling();'),
            resume_source.index('const operation = ++this.operationSequence;'),
        )
        self.assertIn('this.restoreCurrentQrLifecycle();', resume_source)

        switch_start = controller.index('async switchCurrentQrToPage()')
        switch_end = controller.index('async syncCurrentOrder(', switch_start)
        switch_source = controller[switch_start:switch_end]
        self.assertLess(
            switch_source.index('this.stopPolling();'),
            switch_source.index('const operation = ++this.operationSequence;'),
        )
        self.assertIn('this.restoreCurrentQrLifecycle();', switch_source)

        sync_start = switch_end
        sync_end = controller.index('submitAlipayForm(paymentHtml)', sync_start)
        sync_source = controller[sync_start:sync_end]
        self.assertIn('this.switchInFlight', sync_source)
        self.assertIn(
            'this.resumeInFlight.has(this.currentOrder.orderNo)',
            sync_source,
        )

        restore_start = controller.index('restoreCurrentQrLifecycle() {')
        restore_end = controller.index('submitAlipayForm(paymentHtml)', restore_start)
        restore_source = controller[restore_start:restore_end]
        self.assertIn('this.root.hidden', restore_source)
        self.assertIn("this.currentOrder.status !== 'CREATED'", restore_source)
        self.assertIn('this.startPolling();', restore_source)
        self.assertIn('this.startCountdownOnly();', restore_source)

    def test_payment_center_waits_for_authoritative_expiry_and_reuses_poll_timer(self):
        controller = (
            Path(settings.BASE_DIR) / 'static/js/payment-center.js'
        ).read_text(encoding='utf-8')

        polling_start = controller.index('startPolling() {')
        polling_end = controller.index('startCountdownOnly() {', polling_start)
        polling_source = controller[polling_start:polling_end]
        self.assertIn('PaymentCenterCore.UI.PAID', polling_source)
        self.assertIn('PaymentCenterCore.UI.EXPIRED', polling_source)

        countdown_start = polling_end
        countdown_end = controller.index('stopPolling() {', countdown_start)
        countdown_source = controller[countdown_start:countdown_end]
        self.assertIn('PaymentCenterCore.UI.PAID', countdown_source)
        self.assertIn('PaymentCenterCore.UI.EXPIRED', countdown_source)

        update_start = controller.index('updateCountdown() {')
        update_end = controller.index('handleVisibilityChange()', update_start)
        update_source = controller[update_start:update_end]
        paid_guard = update_source.index('PaymentCenterCore.UI.PAID')
        expiry_start = update_source.index('if (remaining <= 0)')
        self.assertLess(paid_guard, expiry_start)
        expiry_source = update_source[expiry_start:]
        self.assertNotIn('this.transition(PaymentCenterCore.UI.EXPIRED', expiry_source)
        self.assertIn('this.stopCountdown();', expiry_source)
        self.assertIn(
            "this.elements.qrCountdown.textContent = '正在确认订单最终状态，请勿重复支付';",
            expiry_source,
        )
        self.assertIn('this.ensurePollingInterval();', expiry_source)
        self.assertIn('this.expirySyncRequested !== orderNo', expiry_source)
        self.assertIn('this.syncCurrentOrder(false);', expiry_source)
        self.assertIn(
            'if (shouldSync && !document.hidden && !this.root.hidden)',
            expiry_source,
        )
        self.assertNotIn('if (shouldSync) this.syncCurrentOrder(false);', expiry_source)
        self.assertNotIn('setInterval(', expiry_source)

        ensure_start = controller.index('ensurePollingInterval() {')
        ensure_end = controller.index('startCountdownOnly() {', ensure_start)
        ensure_source = controller[ensure_start:ensure_end]
        self.assertIn('if (this.pollTimer', ensure_source)
        self.assertEqual(ensure_source.count('setInterval('), 1)
        self.assertIn('this.ensurePollingInterval();', polling_source)
        self.assertIn('this.expirySyncRequested = null;', controller)

        response_start = controller.index('async handleOrderResponse(order)')
        response_end = controller.index('async showQrOrder(order)', response_start)
        response_source = controller[response_start:response_end]
        create_error = response_source.index('PaymentCenterCore.UI.CREATE_ERROR')
        countdown = response_source.index('this.startCountdownOnly()', create_error)
        self.assertLess(create_error, countdown)

        closed_start = response_source.index("if (order.status === 'CLOSED')")
        closed_end = response_source.index("if (order.paymentMethod === 'QR')", closed_start)
        closed_source = response_source[closed_start:closed_end]
        expired_transition = closed_source.index('PaymentCenterCore.UI.EXPIRED')
        pending_refresh = closed_source.index('await this.loadPending();')
        self.assertLess(expired_transition, pending_refresh)

    def test_payment_center_terminal_orders_invalidate_and_hide_qr_canvas(self):
        controller = (
            Path(settings.BASE_DIR) / 'static/js/payment-center.js'
        ).read_text(encoding='utf-8')

        helper_start = controller.index('invalidateQrDisplay() {')
        helper_end = controller.index('async showQrOrder(order)', helper_start)
        helper_source = controller[helper_start:helper_end]
        self.assertIn('this.renderSequence += 1;', helper_source)
        self.assertIn('this.clearVisibleQrCanvas();', helper_source)

        complete_start = controller.index('async completePaid(order)')
        complete_end = controller.index('startPolling() {', complete_start)
        complete_source = controller[complete_start:complete_end]
        self.assertLess(
            complete_source.index('this.invalidateQrDisplay();'),
            complete_source.index('this.transition(PaymentCenterCore.UI.PAID'),
        )

        response_start = controller.index('async handleOrderResponse(order)')
        response_end = controller.index('async showQrOrder(order)', response_start)
        response_source = controller[response_start:response_end]
        response_closed_start = response_source.index("if (order.status === 'CLOSED')")
        response_closed_end = response_source.index(
            "if (order.paymentMethod === 'QR')", response_closed_start
        )
        response_closed = response_source[response_closed_start:response_closed_end]
        self.assertLess(
            response_closed.index('this.invalidateQrDisplay();'),
            response_closed.index('this.transition(PaymentCenterCore.UI.EXPIRED'),
        )

        sync_start = controller.index('async syncCurrentOrder(')
        sync_end = controller.index('restoredUiAfterSync(previousUi)', sync_start)
        sync_source = controller[sync_start:sync_end]
        sync_closed_start = sync_source.index("if (order.status === 'CLOSED')")
        sync_closed = sync_source[sync_closed_start:]
        self.assertLess(
            sync_closed.index('this.invalidateQrDisplay();'),
            sync_closed.index('this.transition(PaymentCenterCore.UI.EXPIRED'),
        )

        render_start = controller.index('render() {')
        render_end = controller.index('updateOrderStatus() {', render_start)
        render_source = controller[render_start:render_end]
        self.assertIn("order.status === 'CREATED'", render_source)
        self.assertIn('ui !== PaymentCenterCore.UI.QR_RENDER_ERROR', render_source)
        self.assertIn('ui !== PaymentCenterCore.UI.PAID', render_source)
        self.assertIn('ui !== PaymentCenterCore.UI.EXPIRED', render_source)
        self.assertIn('qrContainer.hidden = !showQrCanvas;', render_source)

    def test_payment_center_clears_stale_canvas_and_hides_it_on_render_failure(self):
        controller = (
            Path(settings.BASE_DIR) / 'static/js/payment-center.js'
        ).read_text(encoding='utf-8')

        show_start = controller.index('async showQrOrder(order)')
        show_end = controller.index('renderPending(orders)', show_start)
        show_source = controller[show_start:show_end]
        self.assertIn("document.createElement('canvas')", show_source)
        self.assertIn('offscreenCanvas.width = canvas.width;', show_source)
        self.assertIn('offscreenCanvas.height = canvas.height;', show_source)
        self.assertIn(
            'PaymentCenterCore.renderQr(window.QRCode, offscreenCanvas, order.qrCode',
            show_source,
        )
        self.assertNotIn(
            'PaymentCenterCore.renderQr(window.QRCode, canvas, order.qrCode',
            show_source,
        )
        render_position = show_source.index('PaymentCenterCore.renderQr(')
        stale_guard_position = show_source.index(
            'if (sequence !== this.renderSequence || this.root.hidden) return;',
            render_position,
        )
        clear_position = show_source.index('context.clearRect(', stale_guard_position)
        draw_position = show_source.index(
            'context.drawImage(offscreenCanvas, 0, 0);', clear_position
        )
        self.assertLess(render_position, stale_guard_position)
        self.assertLess(stale_guard_position, clear_position)
        self.assertLess(clear_position, draw_position)

        render_catch_start = show_source.index('} catch (error) {', draw_position)
        render_catch = show_source[render_catch_start:]
        self.assertIn('this.startCountdownOnly();', render_catch)
        self.assertNotIn('this.startPolling();', render_catch)

        purchase_start = controller.index('openPurchase(purchase, trigger)')
        purchase_end = controller.index('openPending(trigger = null)', purchase_start)
        purchase_source = controller[purchase_start:purchase_end]
        self.assertIn('this.renderSequence += 1;', purchase_source)
        self.assertIn('this.clearVisibleQrCanvas();', purchase_source)

        clear_helper_start = controller.index('clearVisibleQrCanvas() {')
        clear_helper_end = controller.index('async showQrOrder(order)', clear_helper_start)
        clear_helper = controller[clear_helper_start:clear_helper_end]
        self.assertIn('context.clearRect(', clear_helper)

        render_start = controller.index('render() {')
        render_end = controller.index('updateOrderStatus() {', render_start)
        render_source = controller[render_start:render_end]
        self.assertIn('ui === PaymentCenterCore.UI.QR_RENDER_ERROR', render_source)
        self.assertIn('qrContainer.hidden =', render_source)
        self.assertIn('this.elements.qrView.hidden = !showQrDetails;', render_source)
        self.assertIn('this.elements.qrOrderNo.textContent = order.orderNo;', render_source)
        self.assertIn('this.updateCountdown();', render_source)
        self.assertIn("this.elements.qrSync.hidden = order.status !== 'CREATED';", render_source)
        self.assertIn(
            'this.elements.qrRetryRender.hidden = ui !== PaymentCenterCore.UI.QR_RENDER_ERROR;',
            render_source,
        )
        self.assertIn(
            'this.elements.qrOpenLink.hidden = !(ui === PaymentCenterCore.UI.QR_RENDER_ERROR && trustedQr);',
            render_source,
        )
        self.assertIn('this.elements.qrCashier.hidden = !(', render_source)

    def test_base_nav_has_independent_pending_payment_entry(self):
        base_template = (
            Path(settings.BASE_DIR) / 'templates/base.html'
        ).read_text(encoding='utf-8')

        self.assertIn('id="pendingPaymentButton"', base_template)
        self.assertIn('href="/membership/?paymentCenter=pending"', base_template)
        self.assertIn('aria-label="待支付订单"', base_template)
        self.assertIn('title="待支付订单"', base_template)
        self.assertIn('data-lucide="receipt-text"', base_template)
        self.assertIn('id="pendingPaymentCount"', base_template)
        self.assertIn('class="pending-payment-count" hidden>0</span>', base_template)

        membership_pos = base_template.index('id="membershipBadgeButton"')
        pending_pos = base_template.index('id="pendingPaymentButton"')
        menu_pos = base_template.index('id="userMenuToggle"')
        self.assertLess(membership_pos, pending_pos)
        self.assertLess(pending_pos, menu_pos)

        membership_button = base_template[membership_pos:pending_pos]
        pending_button = base_template[pending_pos:menu_pos]
        self.assertNotIn('id="pendingPaymentButton"', membership_button)
        self.assertNotIn('id="userMenuToggle"', pending_button)

        pending_anchor_start = base_template.rfind('<a ', 0, pending_pos)
        pending_anchor_end = base_template.index('</a>', pending_pos) + len('</a>')
        pending_anchor = base_template[pending_anchor_start:pending_anchor_end]
        self.assertNotIn('<button', pending_anchor)
        self.assertEqual(pending_anchor.count('<a '), 1)
        self.assertIn('aria-live="polite"', pending_anchor)
        self.assertIn('aria-atomic="true"', pending_anchor)

    def test_pending_payment_entry_has_stable_responsive_styles(self):
        base_template = (
            Path(settings.BASE_DIR) / 'templates/base.html'
        ).read_text(encoding='utf-8')

        self.assertIn('.pending-payment-button {', base_template)
        self.assertIn('width: 40px;', base_template)
        self.assertIn('height: 40px;', base_template)
        self.assertIn('flex: 0 0 40px;', base_template)
        self.assertIn('.pending-payment-button:focus-visible {', base_template)
        self.assertIn('.pending-payment-count {', base_template)
        self.assertIn('position: absolute;', base_template)
        self.assertIn(
            'grid-template-columns: auto auto 40px minmax(0, 1fr);',
            base_template,
        )
        self.assertIn("if (action.id === 'pendingPaymentButton') return;", base_template)

    def test_pending_payment_count_loader_is_authenticated_and_refreshable(self):
        base_template = (
            Path(settings.BASE_DIR) / 'templates/base.html'
        ).read_text(encoding='utf-8')

        self.assertIn('function loadPendingPaymentCount()', base_template)
        self.assertIn("fetch('/api/payments/orders/pending/')", base_template)
        self.assertIn('response.ok', base_template)
        self.assertIn(
            'if (!payload || payload.code !== 200 || !Array.isArray(payload.data)) {',
            base_template,
        )
        self.assertIn("throw new Error('Invalid pending payment response');", base_template)
        self.assertIn('const count = payload.data.length;', base_template)
        self.assertIn("count > 99 ? '99+' : String(count)", base_template)
        self.assertIn('badge.hidden = count === 0;', base_template)
        self.assertIn(
            "const ariaLabel = count === 0 ? '待支付订单' : `待支付订单，${count} 笔`;",
            base_template,
        )
        self.assertIn("button.setAttribute('aria-label', ariaLabel);", base_template)
        self.assertIn('window.loadPendingPaymentCount = loadPendingPaymentCount;', base_template)

    def test_pending_payment_count_failure_preserves_last_successful_state(self):
        base_template = (
            Path(settings.BASE_DIR) / 'templates/base.html'
        ).read_text(encoding='utf-8')

        loader_start = base_template.index('function loadPendingPaymentCount()')
        loader_end = base_template.index(
            'window.loadPendingPaymentCount = loadPendingPaymentCount;',
            loader_start,
        )
        loader_source = base_template[loader_start:loader_end]
        catch_start = loader_source.index('.catch(')
        catch_source = loader_source[catch_start:]

        self.assertIn('.catch(() => null);', catch_source)
        self.assertNotIn("badge.textContent = '0'", catch_source)
        self.assertNotIn('badge.hidden = true', catch_source)
        self.assertNotIn("button.setAttribute('aria-label'", catch_source)

    def test_authenticated_pending_count_initializes_after_fetch_interceptor(self):
        base_template = (
            Path(settings.BASE_DIR) / 'templates/base.html'
        ).read_text(encoding='utf-8')

        interceptor_start = base_template.index('window.fetch = function(...args) {')
        interceptor_end = base_template.index('\n        };', interceptor_start)
        self.assertNotIn('loadUserCredits();', base_template[:interceptor_end])
        self.assertNotIn('loadPendingPaymentCount();', base_template[:interceptor_end])
        authenticated_initialization = (
            "{% if request.session.token %}\n"
            "        loadUserCredits();\n"
            "        loadPendingPaymentCount();\n"
            "        {% endif %}"
        )
        initialization_start = base_template.find(authenticated_initialization)
        self.assertGreater(initialization_start, interceptor_end)

    def test_membership_page_has_unified_payment_center_shell(self):
        template = (
            Path(settings.BASE_DIR) / 'templates/payments/membership.html'
        ).read_text(encoding='utf-8')

        for element_id in (
            'paymentCenterOverlay',
            'paymentCenterPanel',
            'paymentCenterClose',
            'paymentCenterCurrentTab',
            'paymentCenterPendingTab',
            'paymentCenterPendingCount',
            'paymentCenterCurrentView',
            'paymentCenterPendingView',
            'paymentPendingList',
            'paymentQrCanvas',
        ):
            self.assertIn(f'id="{element_id}"', template)

        self.assertNotIn('id="paymentMethodModal"', template)
        self.assertNotIn('id="paymentQrModal"', template)
        self.assertIn("{% static 'css/payment-center.css' %}", template)

        panel_start = template.index('<aside id="paymentCenterPanel"')
        panel_tag = template[panel_start:template.index('>', panel_start) + 1]
        self.assertIn('aria-hidden="true"', panel_tag)
        self.assertIn(' hidden', panel_tag)
        self.assertIn(' inert', panel_tag)

    def test_payment_center_current_view_has_stable_state_and_action_hooks(self):
        template = (
            Path(settings.BASE_DIR) / 'templates/payments/membership.html'
        ).read_text(encoding='utf-8')

        for element_id in (
            'paymentCurrentProduct',
            'paymentCurrentAmount',
            'paymentMethodSelection',
            'paymentMethodQr',
            'paymentMethodPage',
            'paymentCreatingState',
            'paymentCreateError',
            'paymentQrView',
            'paymentQrOrderNo',
            'paymentQrCountdown',
            'paymentQrStatus',
            'paymentQrSync',
            'paymentQrRefresh',
            'paymentQrRetryRender',
            'paymentQrOpenLink',
            'paymentQrCashier',
        ):
            self.assertIn(f'id="{element_id}"', template)

        for icon in ('x', 'qr-code', 'external-link', 'refresh-cw', 'rotate-cw'):
            self.assertIn(f'data-lucide="{icon}"', template)
        self.assertIn('aria-label="生成新的支付宝二维码"', template)
        self.assertIn('<span>刷新二维码</span>', template)
        self.assertIn('data-payment-purchase', template)
        self.assertIn('data-payment-order-type="MEMBERSHIP"', template)
        self.assertIn('data-payment-order-type="RECHARGE"', template)

    def test_payment_center_css_has_desktop_mobile_and_accessibility_contracts(self):
        css_path = Path(settings.BASE_DIR) / 'static/css/payment-center.css'
        self.assertTrue(css_path.is_file())
        css = css_path.read_text(encoding='utf-8')

        self.assertIn('.payment-center {', css)
        self.assertIn('width: min(520px, 92vw);', css)
        self.assertIn('.payment-center[aria-hidden="true"] {', css)
        self.assertIn('transform: translateX(100%);', css)
        self.assertIn('.payment-center[hidden] {', css)
        hidden_rule = css.split('.payment-center[hidden] {', 1)[1].split('}', 1)[0]
        self.assertIn('display: none !important;', hidden_rule)
        self.assertIn('.payment-center-qr {', css)
        self.assertIn('width: 232px;', css)
        self.assertIn('height: 232px;', css)
        self.assertIn('@media (max-width: 768px)', css)
        self.assertIn('width: 100%;', css)
        self.assertIn('max-width: none;', css)
        self.assertIn('@media (prefers-reduced-motion: reduce)', css)
        self.assertIn('.payment-center-actions {', css)
        self.assertIn('grid-template-columns: repeat(2, minmax(0, 1fr));', css)

    def test_membership_page_loads_versioned_local_payment_assets_in_order(self):
        template = (
            Path(settings.BASE_DIR) / 'templates/payments/membership.html'
        ).read_text(encoding='utf-8')

        assets = (
            "{% static 'vendor/qrcode/qrcode.min.js' %}?v=1.5.3",
            "{% static 'js/payment-center-core.js' %}?v=20260720-refresh1",
            "{% static 'js/payment-center.js' %}?v=20260720-refresh1",
        )
        for asset in assets:
            self.assertIn(asset, template)
        positions = [template.index(asset) for asset in assets]

        self.assertEqual(positions, sorted(positions))
        self.assertNotIn('cdn.jsdelivr.net', template)
        self.assertNotIn('unpkg.com', template)
        self.assertNotIn('QRCode.toCanvas', template)

    def test_membership_catalog_values_are_escaped_before_html_interpolation(self):
        template = (
            Path(settings.BASE_DIR) / 'templates/payments/membership.html'
        ).read_text(encoding='utf-8')

        for escape_contract in (
            'function escapeHtml(value) {',
            "String(value ?? '')",
            ".replace(/&/g, '&amp;')",
            ".replace(/</g, '&lt;')",
            ".replace(/>/g, '&gt;')",
            ".replace(/\"/g, '&quot;')",
            ".replace(/'/g, '&#39;')",
        ):
            self.assertIn(escape_contract, template)

        for safe_interpolation in (
            'const productCode = escapeHtml(product.code);',
            'const productTitle = escapeHtml(product.title);',
            "const buttonLabel = escapeHtml(product.buttonText || '购买');",
            'const productName = escapeHtml(',
            'const featureLabel = escapeHtml(featureText);',
            'data-payment-product-code="${productCode}"',
            'data-payment-product-name="${productName}"',
            '${featureLabel}</span>',
            'data-payment-amount-cents="${priceCents}"',
            'data-payment-amount-cents="${amountCents}"',
        ):
            self.assertIn(safe_interpolation, template)

        for unsafe_interpolation in (
            '${product.title}',
            'data-payment-product-code="${product.code}"',
            'data-payment-product-name="${String(product.buttonText || product.title)',
            "${product.buttonText || '购买'}</button>",
            '${feature}</span>',
            'data-payment-amount-cents="${product.priceCents}"',
            'data-payment-amount-cents="${item.amountCents}"',
        ):
            self.assertNotIn(unsafe_interpolation, template)

    def test_recharge_proxy_forwards_qr_payment_method(self):
        session = self.client.session
        session['token'] = 'token-pay'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = {
            'orderNo': 'PAY202607170099',
            'paymentMethod': 'QR',
            'qrCode': 'https://qr.alipay.com/example',
            'expiresAt': '2026-07-17T18:00:00',
        }

        with patch('apps.series.views.BackendClient', return_value=backend_client):
            response = self.client.post(
                '/api/payments/recharge/orders/',
                data=json.dumps({'amountCents': 5000, 'paymentMethod': 'QR'}),
                content_type='application/json',
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['data']['paymentMethod'], 'QR')
        backend_client.post.assert_called_once_with('/v1/payments/recharge/orders', {
            'amountCents': 5000,
            'paymentMethod': 'QR',
        })

    def test_membership_proxy_defaults_missing_method_to_page(self):
        session = self.client.session
        session['token'] = 'token-pay'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = {'paymentMethod': 'PAGE'}

        with patch('apps.series.views.BackendClient', return_value=backend_client):
            response = self.client.post(
                '/api/payments/membership/orders/',
                data=json.dumps({'productCode': 'month_basic'}),
                content_type='application/json',
            )

        self.assertEqual(response.status_code, 200)
        backend_client.post.assert_called_once_with('/v1/payments/membership/orders', {
            'productCode': 'month_basic',
            'paymentMethod': 'PAGE',
        })

    def test_payment_proxy_rejects_unknown_payment_method(self):
        session = self.client.session
        session['token'] = 'token-pay'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = {}

        with patch('apps.series.views.BackendClient', return_value=backend_client):
            response = self.client.post(
                '/api/payments/recharge/orders/',
                data=json.dumps({'amountCents': 5000, 'paymentMethod': 'CARD'}),
                content_type='application/json',
            )

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()['message'], 'Invalid payment method')
        backend_client.post.assert_not_called()

    def test_pending_orders_proxy_uses_static_route_and_forwards_request(self):
        session = self.client.session
        session['token'] = 'token-pay'
        session.save()
        backend_client = Mock()
        backend_client.get.return_value = [{'orderNo': 'PAY-PENDING'}]

        with patch('apps.series.views.BackendClient', return_value=backend_client):
            response = self.client.get('/api/payments/orders/pending/')

        self.assertEqual(resolve('/api/payments/orders/pending/').url_name, 'payment_pending_orders')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['data'][0]['orderNo'], 'PAY-PENDING')
        backend_client.get.assert_called_once_with('/v1/payments/orders/pending')

    def test_pending_order_resume_proxy_forwards_request(self):
        session = self.client.session
        session['token'] = 'token-pay'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = {'orderNo': 'PAY-PENDING', 'paymentMethod': 'QR'}

        with patch('apps.series.views.BackendClient', return_value=backend_client):
            response = self.client.post('/api/payments/orders/PAY-PENDING/resume/')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['data']['paymentMethod'], 'QR')
        backend_client.post.assert_called_once_with('/v1/payments/orders/PAY-PENDING/resume')

    def test_pending_order_switch_to_page_proxy_forwards_request(self):
        session = self.client.session
        session['token'] = 'token-pay'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = {'orderNo': 'PAY-PAGE', 'paymentMethod': 'PAGE'}

        with patch('apps.series.views.BackendClient', return_value=backend_client):
            response = self.client.post('/api/payments/orders/PAY-PENDING/switch-to-page/')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['data']['paymentMethod'], 'PAGE')
        backend_client.post.assert_called_once_with('/v1/payments/orders/PAY-PENDING/switch-to-page')

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
            response = self.client.post('/api/payments/orders/PAY-PENDING/refresh-qr/')

        self.assertEqual(
            resolve('/api/payments/orders/PAY-PENDING/refresh-qr/').url_name,
            'payment_order_refresh_qr',
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['data']['orderNo'], 'PAY-REFRESHED')
        backend_client.post.assert_called_once_with(
            '/v1/payments/orders/PAY-PENDING/refresh-qr'
        )

    def test_resume_and_switch_reject_missing_csrf_token_before_backend_call(self):
        paths = (
            '/api/payments/orders/PAY-PENDING/resume/',
            '/api/payments/orders/PAY-PENDING/switch-to-page/',
            '/api/payments/orders/PAY-PENDING/refresh-qr/',
        )

        for path in paths:
            with self.subTest(path=path):
                client = Client(enforce_csrf_checks=True)
                session = client.session
                session['token'] = 'token-pay'
                session.save()
                backend_client = Mock()
                backend_client.post.return_value = {}

                with patch('apps.series.views.BackendClient', return_value=backend_client) as client_class:
                    response = client.post(path)

                self.assertEqual(response.status_code, 403)
                client_class.assert_not_called()

    def test_resume_and_switch_accept_matching_csrf_token(self):
        cases = (
            ('/api/payments/orders/PAY-PENDING/resume/', '/v1/payments/orders/PAY-PENDING/resume'),
            (
                '/api/payments/orders/PAY-PENDING/switch-to-page/',
                '/v1/payments/orders/PAY-PENDING/switch-to-page',
            ),
            (
                '/api/payments/orders/PAY-PENDING/refresh-qr/',
                '/v1/payments/orders/PAY-PENDING/refresh-qr',
            ),
        )

        for path, backend_path in cases:
            with self.subTest(path=path):
                client = Client(enforce_csrf_checks=True)
                session = client.session
                session['token'] = 'token-pay'
                session.save()
                csrf_token = 'a' * 32
                client.cookies['csrftoken'] = csrf_token
                backend_client = Mock()
                backend_client.post.return_value = {'orderNo': 'PAY-PENDING'}

                with patch('apps.series.views.BackendClient', return_value=backend_client):
                    response = client.post(path, HTTP_X_CSRFTOKEN=csrf_token)

                self.assertEqual(response.status_code, 200)
                backend_client.post.assert_called_once_with(backend_path)

    def test_payment_create_and_sync_reject_missing_csrf_token(self):
        cases = (
            (
                '/api/payments/membership/orders/',
                {'productCode': 'month_basic', 'paymentMethod': 'QR'},
            ),
            (
                '/api/payments/recharge/orders/',
                {'amountCents': 5000, 'paymentMethod': 'QR'},
            ),
            ('/api/payments/orders/PAY-CSRF/sync/', None),
        )

        for path, payload in cases:
            with self.subTest(path=path):
                client = Client(enforce_csrf_checks=True)
                session = client.session
                session['token'] = 'token-pay'
                session.save()

                with patch('apps.series.views.BackendClient') as client_class:
                    client_class.return_value.post.return_value = {}
                    if payload is None:
                        response = client.post(path)
                    else:
                        response = client.post(
                            path,
                            data=json.dumps(payload),
                            content_type='application/json',
                        )

                self.assertEqual(response.status_code, 403)
                client_class.assert_not_called()

    def test_payment_create_and_sync_accept_matching_csrf_token(self):
        cases = (
            (
                '/api/payments/membership/orders/',
                {'productCode': 'month_basic', 'paymentMethod': 'QR'},
                '/v1/payments/membership/orders',
                {'productCode': 'month_basic', 'paymentMethod': 'QR'},
            ),
            (
                '/api/payments/recharge/orders/',
                {'amountCents': 5000, 'paymentMethod': 'QR'},
                '/v1/payments/recharge/orders',
                {'amountCents': 5000, 'paymentMethod': 'QR'},
            ),
            (
                '/api/payments/orders/PAY-CSRF/sync/',
                None,
                '/v1/payments/orders/PAY-CSRF/sync',
                None,
            ),
        )

        for path, payload, backend_path, backend_payload in cases:
            with self.subTest(path=path):
                client = Client(enforce_csrf_checks=True)
                session = client.session
                session['token'] = 'token-pay'
                session.save()
                csrf_token = 'a' * 32
                client.cookies['csrftoken'] = csrf_token
                backend_client = Mock()
                backend_client.post.return_value = {
                    'orderNo': 'PAY-CSRF',
                    'status': 'CREATED',
                }

                with patch('apps.series.views.BackendClient', return_value=backend_client):
                    if payload is None:
                        response = client.post(path, HTTP_X_CSRFTOKEN=csrf_token)
                    else:
                        response = client.post(
                            path,
                            data=json.dumps(payload),
                            content_type='application/json',
                            HTTP_X_CSRFTOKEN=csrf_token,
                        )

                self.assertEqual(response.status_code, 200)
                if backend_payload is None:
                    backend_client.post.assert_called_once_with(backend_path)
                else:
                    backend_client.post.assert_called_once_with(
                        backend_path,
                        backend_payload,
                    )

    def test_pending_orders_rejects_non_get_without_backend_call(self):
        session = self.client.session
        session['token'] = 'token-pay'
        session.save()

        for method in ('post', 'put', 'delete'):
            with self.subTest(method=method):
                with patch('apps.series.views.BackendClient') as client_class:
                    client_class.return_value.get.return_value = []
                    response = getattr(self.client, method)('/api/payments/orders/pending/')

                self.assertEqual(response.status_code, 405)
                client_class.assert_not_called()

    def test_pending_orders_proxy_returns_auth_error_without_traceback(self):
        with patch('apps.series.views.BackendClient') as client_class:
            response = self.client.get('/api/payments/orders/pending/')

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json(), {
            'code': 401,
            'message': '请先登录',
        })
        self.assertNotIn('traceback', response.content.decode('utf-8').lower())
        client_class.assert_not_called()

    def test_payment_recovery_proxies_preserve_safe_backend_error_statuses(self):
        cases = (
            ('get', '/api/payments/orders/pending/', 401, 401),
            ('post', '/api/payments/orders/PAY-PENDING/resume/', 404, 404),
            ('post', '/api/payments/orders/PAY-PENDING/switch-to-page/', 409, 409),
            ('post', '/api/payments/orders/PAY-PENDING/refresh-qr/', 409, 409),
            ('get', '/api/payments/orders/pending/', 503, 503),
            ('post', '/api/payments/orders/PAY-PENDING/switch-to-page/', 700, 502),
        )

        for method, path, backend_status, expected_status in cases:
            with self.subTest(method=method, path=path, backend_status=backend_status):
                client = Client()
                session = client.session
                session['token'] = 'token-pay'
                session.save()
                backend_client = Mock()
                getattr(backend_client, method).side_effect = BackendAPIError(
                    backend_status,
                    'Payment recovery error',
                    {'traceback': 'sensitive backend traceback'},
                )

                with patch('apps.series.views.BackendClient', return_value=backend_client):
                    response = getattr(client, method)(path)

                self.assertEqual(response.status_code, expected_status)
                self.assertEqual(response.json(), {
                    'code': expected_status,
                    'message': 'Payment recovery error',
                })
                self.assertNotIn('traceback', response.content.decode('utf-8').lower())
