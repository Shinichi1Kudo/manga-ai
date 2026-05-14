from unittest.mock import Mock, patch

from django.test import TestCase


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
