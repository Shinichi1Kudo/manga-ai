from pathlib import Path
from unittest.mock import Mock, patch

from django.conf import settings
from django.test import TestCase

from api.backend_client import BackendAPIError


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

    def test_showcase_assets_endpoint_is_public(self):
        backend_client = Mock()
        backend_client.get.return_value = {
            'beforeVideoUrl': 'https://oss.example.com/before.mp4',
            'referenceImageUrl': 'https://oss.example.com/reference.png',
            'afterVideoUrl': 'https://oss.example.com/after.mp4',
        }

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/api/v1/common/showcase-assets/')

        self.assertEqual(response.status_code, 200)
        data = response.json()['data']
        self.assertEqual(data['beforeVideoUrl'], 'https://oss.example.com/before.mp4')
        self.assertEqual(data['referenceImageUrl'], 'https://oss.example.com/reference.png')
        self.assertEqual(data['afterVideoUrl'], 'https://oss.example.com/after.mp4')


class SubjectReplacementDeleteTests(TestCase):
    def test_delete_uses_canonical_task_endpoint(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()

        backend_client = Mock()

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.delete('/api/v1/subject-replacements/5/')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['code'], 200)
        backend_client.delete.assert_called_once_with('/v1/subject-replacements/5')

    def test_delete_treats_missing_task_as_success(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()

        backend_client = Mock()
        backend_client.delete.side_effect = BackendAPIError(404, '任务不存在', {'code': 404})

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.delete('/api/v1/subject-replacements/5/delete/')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['code'], 200)


class SeriesListWebSocketTests(TestCase):
    def test_websocket_uses_current_site_proxy_path(self):
        template_path = Path(settings.BASE_DIR) / 'templates/series/series_list.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn("new SockJS('/api/ws')", template)
        self.assertNotIn('localhost:8081/api/ws', template)


class SiteBrandingTests(TestCase):
    def test_visible_templates_use_haidai_site_name(self):
        template_root = Path(settings.BASE_DIR) / 'templates'
        templates = [
            template_root / 'base.html',
            template_root / 'series/series_list.html',
            template_root / 'auth/login.html',
            template_root / 'auth/register.html',
            template_root / 'subject_replacement/index.html',
            template_root / 'credits/credit_records.html',
            template_root / 'asset/library.html',
            template_root / 'user/settings.html',
        ]
        combined = '\n'.join(path.read_text(encoding='utf-8') for path in templates)

        self.assertIn('海带 AI 智能短剧制作系统', combined)
        self.assertNotIn('Manga AI', combined)
        self.assertNotIn('>AI 智能短剧制作系统<', combined)


class SubjectReplacementCreditButtonTests(TestCase):
    def test_submit_button_shows_estimated_credit_deduction(self):
        template_path = Path(settings.BASE_DIR) / 'templates/subject_replacement/index.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('id="submitCreditText"', template)
        self.assertIn('扣除160积分', template)
        self.assertIn("document.getElementById('submitCreditText').textContent = `扣除${estimatedCredits}积分`", template)
