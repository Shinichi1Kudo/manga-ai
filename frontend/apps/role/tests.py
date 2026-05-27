import json
from unittest.mock import Mock, patch

from django.test import TestCase


class RoleCreateTests(TestCase):
    def test_manual_uploaded_role_forwards_uploaded_image_url_to_backend(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()

        backend_client = Mock()
        backend_client.post.return_value = 42
        payload = {
            'roleName': '楚清辞',
            'customPrompt': '白衣剑修，气质清冷',
            'originalPrompt': '白衣剑修，气质清冷',
            'aspectRatio': '16:9',
            'quality': 'hd',
            'styleKey': 'chinese_style',
            'styleKeywords': '古风国漫',
            'detailedView': False,
            'faceCloseupView': False,
            'manualUploadEnabled': True,
            'uploadedImageUrl': 'https://example.com/manual-role.png',
        }

        with patch('apps.role.views.get_client', return_value=backend_client):
            response = self.client.post(
                '/roles/series/12/create/',
                data=json.dumps(payload),
                content_type='application/json',
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {'success': True, 'roleId': 42})
        expected_payload = dict(payload)
        expected_payload['seriesId'] = 12
        backend_client.post.assert_called_once_with('/v1/roles', expected_payload)


class RoleUnlockTests(TestCase):
    def test_role_unlock_returns_immediate_status_payload(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()

        backend_client = Mock()

        with patch('apps.role.views.get_client', return_value=backend_client):
            response = self.client.post('/roles/10/unlock/')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {
            'success': True,
            'roleStatus': 1,
            'roleStatusDesc': '待审核',
            'seriesStatus': 1,
            'seriesStatusDesc': '待审核',
        })
        backend_client.post.assert_called_once_with('/v1/roles/10/unlock')
