from unittest.mock import Mock, patch

from django.test import TestCase
from django.contrib.messages import get_messages


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


class RegisterRewardTests(TestCase):
    def test_register_success_message_shows_twenty_credit_reward(self):
        backend_response = Mock()
        backend_response.status_code = 200
        backend_response.json.return_value = {
            'code': 200,
            'data': {
                'token': 'token-20',
                'id': 20,
                'email': 'new@example.com',
                'nickname': '新用户',
            },
        }

        with patch('apps.auth.views.requests.post', return_value=backend_response):
            response = self.client.post('/auth/register/', {
                'email': 'new@example.com',
                'code': '123456',
                'password': 'password123',
                'nickname': '新用户',
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response.headers['Location'], '/')
        messages = [str(message) for message in get_messages(response.wsgi_request)]
        self.assertIn('注册成功，已赠送20积分', messages)

    def test_register_page_highlights_twenty_credit_reward(self):
        response = self.client.get('/auth/register/')

        self.assertEqual(response.status_code, 200)
        self.assertContains(response, '新用户注册即送', html=False)
        self.assertContains(response, '<strong class="gift-bonus">20 积分</strong>', html=False)
        self.assertContains(response, 'gift-bonus', html=False)
