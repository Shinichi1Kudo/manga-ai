import json
from pathlib import Path
from unittest.mock import Mock, patch

from django.conf import settings
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

    def test_membership_page_offers_choice_before_creating_order(self):
        template = (
            Path(settings.BASE_DIR) / 'templates/payments/membership.html'
        ).read_text(encoding='utf-8')

        self.assertIn('id="paymentMethodModal"', template)
        self.assertIn('id="paymentMethodQr"', template)
        self.assertIn('id="paymentMethodPage"', template)
        self.assertIn('选择支付方式', template)
        self.assertIn("openPaymentMethodModal('MEMBERSHIP'", template)
        self.assertIn("openPaymentMethodModal('RECHARGE'", template)
        self.assertIn("paymentMethod: selectedPaymentMethod", template)
        self.assertNotIn('onclick="purchaseMembership(', template)
        self.assertNotIn('onclick="purchaseRecharge(', template)

    def test_membership_page_uses_local_qrcode_and_polling_lifecycle(self):
        template = (
            Path(settings.BASE_DIR) / 'templates/payments/membership.html'
        ).read_text(encoding='utf-8')

        self.assertIn("{% static 'vendor/qrcode/qrcode.min.js' %}", template)
        self.assertIn('id="paymentQrModal"', template)
        self.assertIn('id="paymentQrCanvas"', template)
        self.assertIn('id="paymentQrCountdown"', template)
        self.assertIn('id="paymentQrSync"', template)
        self.assertIn('id="paymentQrRegenerate"', template)
        self.assertIn('QRCode.toCanvas', template)
        self.assertIn('setInterval(', template)
        self.assertIn('5000', template)
        self.assertIn("document.addEventListener('visibilitychange'", template)
        self.assertIn('document.hidden', template)
        self.assertIn('stopQrPolling()', template)
        self.assertIn("status === 'PAID'", template)
        self.assertIn("status === 'CLOSED' || status === 'FAILED'", template)
        self.assertIn('loadUserCredits()', template)
        self.assertNotIn('cdn.jsdelivr.net/npm/qrcode', template)
        self.assertNotIn('unpkg.com/qrcode', template)

    def test_cashier_flow_keeps_secure_form_reconstruction(self):
        template = (
            Path(settings.BASE_DIR) / 'templates/payments/membership.html'
        ).read_text(encoding='utf-8')

        self.assertIn('submitAlipayForm(data.data.paymentHtml)', template)
        self.assertIn("sourceForm.method.toUpperCase() !== 'POST'", template)
        self.assertIn("action.protocol !== 'https:'", template)
        self.assertIn("action.pathname !== '/gateway.do'", template)
        self.assertIn("sourceForm.querySelectorAll('input[name]')", template)
        self.assertNotIn('innerHTML = paymentHtml', template)
        self.assertNotIn('insertAdjacentHTML', template)
        self.assertNotIn('document.write', template)

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
