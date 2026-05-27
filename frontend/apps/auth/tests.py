from unittest.mock import Mock, patch

from django.test import TestCase
from django.contrib.messages import get_messages

from apps.auth import views as auth_views


class BackendRequestSessionTests(TestCase):
    def test_internal_backend_requests_ignore_environment_proxy(self):
        self.assertFalse(auth_views.BACKEND_SESSION.trust_env)


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

        with patch('apps.auth.views.BACKEND_SESSION.post', return_value=backend_response):
            response = self.client.post('/auth/login/', {
                'email': 'user@example.com',
                'password': 'password123',
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response.headers['Location'], '/')

    def test_login_accepts_phone_or_email_account_field(self):
        backend_response = Mock()
        backend_response.status_code = 200
        backend_response.json.return_value = {
            'code': 200,
            'data': {
                'token': 'token-phone',
                'id': 2,
                'email': 'bound@example.com',
                'phone': '13800138000',
                'nickname': '绑定用户',
                'requiresPhoneBinding': False,
            },
        }

        with patch('apps.auth.views.BACKEND_SESSION.post', return_value=backend_response) as post_mock:
            response = self.client.post('/auth/login/', {
                'account': '13800138000',
                'password': 'password123',
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response.headers['Location'], '/')
        post_mock.assert_called_once()
        self.assertEqual(post_mock.call_args.kwargs['json']['account'], '13800138000')
        self.assertEqual(post_mock.call_args.kwargs['json']['loginType'], 'password')

    def test_legacy_email_login_redirects_to_bind_phone_when_required(self):
        backend_response = Mock()
        backend_response.status_code = 200
        backend_response.json.return_value = {
            'code': 200,
            'data': {
                'token': 'token-legacy',
                'id': 3,
                'email': 'legacy@example.com',
                'nickname': '老用户',
                'requiresPhoneBinding': True,
            },
        }

        with patch('apps.auth.views.BACKEND_SESSION.post', return_value=backend_response):
            response = self.client.post('/auth/login/', {
                'account': 'legacy@example.com',
                'password': 'password123',
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response.headers['Location'], '/auth/bind-phone/')
        self.assertEqual(self.client.session['token'], 'token-legacy')
        self.assertTrue(self.client.session['requires_phone_binding'])

        messages = [str(message) for message in get_messages(response.wsgi_request)]
        self.assertNotIn('登录成功', messages)

    def test_login_page_labels_account_as_phone_or_email(self):
        response = self.client.get('/auth/login/')

        self.assertEqual(response.status_code, 200)
        self.assertContains(response, '手机号或邮箱', html=False)
        self.assertContains(response, 'name="account"', html=False)
        self.assertNotContains(response, 'name="email"', html=False)


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

        with patch('apps.auth.views.BACKEND_SESSION.post', return_value=backend_response):
            response = self.client.post('/auth/register/', {
                'phone': '13800138000',
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

    def test_register_page_uses_phone_instead_of_email(self):
        response = self.client.get('/auth/register/')

        self.assertEqual(response.status_code, 200)
        self.assertContains(response, '手机号', html=False)
        self.assertContains(response, 'name="phone"', html=False)
        self.assertContains(response, "body: JSON.stringify({ phone: phone, type: 'register' })", html=False)
        self.assertNotContains(response, 'your@email.com', html=False)
        self.assertNotContains(response, 'name="email"', html=False)

    def test_send_code_api_forwards_phone(self):
        backend_response = Mock()
        backend_response.status_code = 200
        backend_response.json.return_value = {'code': 200, 'message': '操作成功'}

        with patch('apps.auth.views.BACKEND_SESSION.post', return_value=backend_response) as post_mock:
            response = self.client.post(
                '/auth/send-code/',
                data='{"phone":"13800138000","type":"register"}',
                content_type='application/json',
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['code'], 200)
        self.assertEqual(post_mock.call_args.kwargs['json'], {'phone': '13800138000', 'type': 'register'})


class BindPhoneTests(TestCase):
    def test_bind_phone_requires_logged_in_user(self):
        response = self.client.get('/auth/bind-phone/')

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response.headers['Location'], '/auth/login/')

    def test_bind_phone_page_renders_for_legacy_user(self):
        session = self.client.session
        session['token'] = 'token-legacy'
        session['requires_phone_binding'] = True
        session.save()

        response = self.client.get('/auth/bind-phone/')

        self.assertEqual(response.status_code, 200)
        self.assertContains(response, '绑定手机号', html=False)
        self.assertContains(response, 'name="phone"', html=False)

    def test_bind_phone_success_updates_session_and_redirects_home(self):
        session = self.client.session
        session['token'] = 'token-legacy'
        session['requires_phone_binding'] = True
        session.save()

        backend_response = Mock()
        backend_response.status_code = 200
        backend_response.json.return_value = {
            'code': 200,
            'data': {
                'token': 'token-bound',
                'id': 3,
                'email': 'legacy@example.com',
                'phone': '13800138000',
                'nickname': '老用户',
                'requiresPhoneBinding': False,
            },
        }

        with patch('apps.auth.views.BACKEND_SESSION.post', return_value=backend_response) as post_mock:
            response = self.client.post('/auth/bind-phone/', {
                'phone': '13800138000',
                'code': '123456',
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response.headers['Location'], '/')
        session = self.client.session
        self.assertEqual(session['phone'], '13800138000')
        self.assertFalse(session.get('requires_phone_binding', False))
        self.assertEqual(post_mock.call_args.kwargs['headers']['Authorization'], 'Bearer token-legacy')
