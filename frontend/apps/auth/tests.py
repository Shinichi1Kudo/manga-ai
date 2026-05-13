from unittest.mock import Mock, patch

from django.test import TestCase


class LoginRedirectTests(TestCase):
    def test_login_ignores_api_next_url(self):
        session = self.client.session
        session['next'] = '/api/user/info/'
        session.save()

        backend_response = Mock()
        backend_response.status_code = 200
        backend_response.json.return_value = {
            'code': 200,
            'data': {
                'token': 'token-1',
                'id': 1,
                'email': 'user@example.com',
                'nickname': '测试用户',
            },
        }

        with patch('apps.auth.views.requests.post', return_value=backend_response):
            response = self.client.post('/auth/login/', {
                'email': 'user@example.com',
                'password': 'password123',
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response.headers['Location'], '/')
