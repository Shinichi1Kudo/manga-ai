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
        self.assertIsNotNone(finders.find('vendor/qrcode/qrcode.min.js'))
        self.assertIsNotNone(finders.find('js/payment-center-core.js'))

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
            'paymentQrRetryRender',
            'paymentQrOpenLink',
            'paymentQrCashier',
        ):
            self.assertIn(f'id="{element_id}"', template)

        for icon in ('x', 'qr-code', 'external-link', 'refresh-cw', 'rotate-cw'):
            self.assertIn(f'data-lucide="{icon}"', template)
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
            "{% static 'js/payment-center-core.js' %}?v=20260719",
            "{% static 'js/payment-center.js' %}?v=20260719",
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

    def test_resume_and_switch_reject_missing_csrf_token_before_backend_call(self):
        paths = (
            '/api/payments/orders/PAY-PENDING/resume/',
            '/api/payments/orders/PAY-PENDING/switch-to-page/',
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
