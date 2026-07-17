import json
from pathlib import Path
from unittest.mock import Mock, patch

from django.conf import settings
from django.test import TestCase


class MembershipQrPaymentTests(TestCase):
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
