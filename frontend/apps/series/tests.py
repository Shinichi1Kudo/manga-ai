from unittest.mock import Mock, patch

from django.test import TestCase


class AnonymousPublicEndpointTests(TestCase):
    def test_contact_image_endpoint_is_public(self):
        backend_client = Mock()
        backend_client.get.return_value = {'url': 'https://example.com/contact.jpg'}

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/api/v1/common/contact-image/')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['data']['url'], 'https://example.com/contact.jpg')

    def test_private_api_request_does_not_store_login_next(self):
        response = self.client.get('/api/user/info/')

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()['message'], '请先登录')
        self.assertNotIn('next', self.client.session)
