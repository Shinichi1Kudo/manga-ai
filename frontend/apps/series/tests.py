import json
import threading
import time
from pathlib import Path
from unittest.mock import Mock, patch

from django.conf import settings
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import TestCase

from api.backend_client import BackendAPIError, BackendClient
from apps.series.views import (
    COMMERCE_ASPECT_RATIO_OPTIONS,
    COMMERCE_SCENE_TYPES,
    COMMERCE_TRY_ON_MAX_SHOTS,
    COMMERCE_TRY_ON_SHOTS,
    COMMERCE_TRY_ON_SUPPORTED_RESOLUTIONS_BY_MODEL,
    COMMERCE_TRY_ON_VIDEO_DURATIONS,
    COMMERCE_TRY_ON_VIDEO_MODELS,
    COMMERCE_TRY_ON_VIDEO_RESOLUTIONS,
    _enrich_commerce_model,
)


class AnonymousPublicEndpointTests(TestCase):
    def test_backend_client_ignores_environment_proxy(self):
        client = BackendClient()

        self.assertFalse(client.session.trust_env)

    def test_contact_image_endpoint_is_public(self):
        backend_client = Mock()
        backend_client.get.return_value = {'url': 'https://example.com/contact.jpg'}

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/api/v1/common/contact-image/')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['data']['url'], 'https://example.com/contact.jpg')

    def test_site_logo_endpoint_is_public(self):
        backend_client = Mock()
        backend_client.get.return_value = {'url': 'https://oss.example.com/brand/haidai-logo.png'}
        logo_response = Mock()
        logo_response.status_code = 200
        logo_response.content = b'png-bytes'
        logo_response.headers = {'Content-Type': 'image/png'}

        with patch('apps.series.views.BackendClient', return_value=backend_client), \
                patch('apps.series.views.requests.get', return_value=logo_response) as requests_get:
            response = self.client.get('/api/v1/common/site-logo/')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.content, b'png-bytes')
        self.assertEqual(response['Content-Type'], 'image/png')
        self.assertEqual(response['Content-Disposition'], 'inline; filename="site-logo.png"')
        self.assertEqual(response['Cache-Control'], 'public, max-age=86400')
        backend_client.get.assert_called_once_with('/v1/common/site-logo')
        requests_get.assert_called_once_with('https://oss.example.com/brand/haidai-logo.png', timeout=15)

    def test_site_logo_png_endpoint_is_public(self):
        backend_client = Mock()
        backend_client.get.return_value = {'url': 'https://oss.example.com/brand/haidai-logo.png'}
        logo_response = Mock()
        logo_response.status_code = 200
        logo_response.content = b'png-bytes'
        logo_response.headers = {'Content-Type': 'image/png'}

        with patch('apps.series.views.BackendClient', return_value=backend_client), \
                patch('apps.series.views.requests.get', return_value=logo_response):
            response = self.client.get('/site-logo.png?v=20260515')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.content, b'png-bytes')

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


class SeriesListIsolationTests(TestCase):
    def test_series_list_api_forwards_session_token_to_backend(self):
        session = self.client.session
        session['token'] = 'token-7'
        session.save()

        backend_client = Mock()
        backend_client.get.return_value = {
            'list': [{'id': 1, 'seriesName': '自己的系列'}],
            'total': 1,
            'page': 1,
            'pageSize': 9,
        }

        with patch('apps.series.views.BackendClient', return_value=backend_client) as backend_client_cls:
            response = self.client.get('/api/series/list/?page=1&pageSize=9')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['data']['total'], 1)
        backend_client_cls.assert_called_once_with(token='token-7')
        backend_client.get.assert_called_once_with('/v1/series/list?page=1&pageSize=9')

    def test_series_list_api_returns_401_when_backend_reports_unauthenticated(self):
        session = self.client.session
        session['token'] = 'expired-token'
        session.save()

        backend_client = Mock()
        backend_client.get.side_effect = BackendAPIError(401, '请先登录', {'code': 401})

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/api/series/list/?page=1&pageSize=9')

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()['message'], '请先登录')

    def test_home_template_handles_series_list_unauthenticated_state(self):
        template_path = Path(settings.BASE_DIR) / 'templates/series/series_list.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('response.status === 401', template)
        self.assertIn('登录后查看我的系列', template)
        self.assertIn('前往登录', template)


class MembershipPaymentPageTests(TestCase):
    def test_membership_page_is_public(self):
        response = self.client.get('/membership/')

        self.assertEqual(response.status_code, 200)
        self.assertContains(response, 'member-pricing-app')
        self.assertContains(response, '会员体系')

    def test_membership_page_uses_one_time_alipay_cashier_flow(self):
        response = self.client.get('/membership/')
        template = response.content.decode('utf-8')

        self.assertContains(response, '前往支付宝支付')
        self.assertContains(response, '单次支付，到期自动结束', count=1)

        for legacy_qr_marker in (
            'qrcode@1.5.3',
            'payQrCanvas',
            'payModal',
            'openPayModal',
            'renderQr',
            'startPolling',
            'syncCurrentOrder',
            'currentOrderNo',
            'pollTimer',
        ):
            self.assertNotIn(legacy_qr_marker, template)

        self.assertIn('data.data.paymentHtml', template)
        self.assertIn('form.submit()', template)
        self.assertIn('openapi.alipay.com', template)
        self.assertIn('openapi-sandbox.dl.alipaydev.com', template)

        for cashier_contract in (
            "button.disabled = true",
            "button.textContent = '正在前往支付宝'",
            'restoreOrderButton(button, originalButtonText)',
            "window.location.href = '/auth/login/'",
            'submitAlipayForm(data.data.paymentHtml)',
            "sourceForms.length !== 1",
            "sourceForm.method.toUpperCase() !== 'POST'",
            "action.protocol !== 'https:'",
            "action.pathname !== '/gateway.do'",
            "sourceForm.querySelectorAll('input[name]')",
            "document.createElement('form')",
            "document.createElement('input')",
            "input.type = 'hidden'",
            "form.target = '_self'",
        ):
            self.assertIn(cashier_contract, template)

        self.assertNotIn('innerHTML = paymentHtml', template)
        self.assertNotIn('insertAdjacentHTML', template)
        self.assertNotIn('document.write', template)

        fallback_products = template.split('const fallbackProducts =', 1)[1].split(
            'const defaultFeatures', 1
        )[0]
        self.assertNotIn('订阅', fallback_products)

    def test_nav_places_membership_link_next_to_contact_button(self):
        template_path = Path(settings.BASE_DIR) / 'templates/base.html'
        template = template_path.read_text(encoding='utf-8')

        contact_index = template.index('showContactModal()')
        membership_index = template.index('href="/membership/"')
        self.assertGreater(membership_index, contact_index)
        self.assertIn('会员充值', template)

    def test_payment_products_proxy_forwards_session_token_to_backend(self):
        session = self.client.session
        session['token'] = 'token-pay'
        session.save()

        backend_client = Mock()
        backend_client.get.return_value = {'membershipProducts': {}, 'rechargePackages': []}

        with patch('apps.series.views.BackendClient', return_value=backend_client) as backend_client_cls:
            response = self.client.get('/api/payments/products/')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['data']['rechargePackages'], [])
        backend_client_cls.assert_called_once_with(token='token-pay')
        backend_client.get.assert_called_once_with('/v1/payments/products')

    def test_create_recharge_order_proxy_posts_json_to_backend(self):
        session = self.client.session
        session['token'] = 'token-pay'
        session.save()

        backend_client = Mock()
        backend_client.post.return_value = {
            'orderNo': 'PAY202607170001',
            'paymentHtml': (
                '<form action="https://openapi-sandbox.dl.alipaydev.com/gateway.do" method="post">'
                '<input type="hidden" name="app_id" value="sandbox-app-id">'
                '</form>'
            ),
            'totalCredits': 1150,
        }

        with patch('apps.series.views.BackendClient', return_value=backend_client) as backend_client_cls:
            response = self.client.post(
                '/api/payments/recharge/orders/',
                data=json.dumps({'amountCents': 5000}),
                content_type='application/json',
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['data']['totalCredits'], 1150)
        self.assertEqual(response.json()['data']['orderNo'], 'PAY202607170001')
        self.assertIn(
            'https://openapi-sandbox.dl.alipaydev.com/gateway.do',
            response.json()['data']['paymentHtml'],
        )
        backend_client_cls.assert_called_once_with(token='token-pay')
        backend_client.post.assert_called_once_with('/v1/payments/recharge/orders', {'amountCents': 5000})


class EpisodeEntryPerformanceTests(TestCase):
    def test_parallel_backend_gets_collects_named_results_with_authenticated_clients(self):
        from apps.series.views import _parallel_backend_gets

        clients = []

        def make_client(token=None):
            client = Mock()
            client.get.side_effect = lambda endpoint: {'token': token, 'endpoint': endpoint}
            clients.append(client)
            return client

        endpoints = {
            'episode': '/v1/episodes/77?basic=true',
            'shots': '/v1/shots/episode/77',
            'scenes': '/v1/scenes/series/88',
        }

        with patch('apps.series.views.BackendClient', side_effect=make_client) as backend_client_cls:
            results = _parallel_backend_gets('token-77', endpoints)

        self.assertEqual(results['episode']['endpoint'], '/v1/episodes/77?basic=true')
        self.assertEqual(results['shots']['endpoint'], '/v1/shots/episode/77')
        self.assertEqual(results['scenes']['endpoint'], '/v1/scenes/series/88')
        self.assertEqual(results['episode']['token'], 'token-77')
        self.assertEqual(backend_client_cls.call_count, 3)
        self.assertEqual(
            sorted(client.get.call_args.args[0] for client in clients),
            sorted(endpoints.values()),
        )

    def test_episode_detail_collects_prop_refs_from_lightweight_shot_props(self):
        session = self.client.session
        session['token'] = 'token-77'
        session['email'] = 'user@example.com'
        session['nickname'] = '测试用户'
        session.save()

        lightweight_shot = {
            'id': 701,
            'episodeId': 77,
            'sceneId': 900,
            'propsJson': None,
            'props': [{'propId': 31, 'propName': '玉佩'}],
            'generationStatus': 0,
            'status': 0,
        }
        linked_unlocked_prop = {
            'id': 31,
            'propName': '玉佩',
            'status': 2,
            'assets': [{'isActive': 1, 'version': 3, 'filePath': 'https://example.com/prop.png'}],
        }
        unrelated_unlocked_prop = {
            'id': 32,
            'propName': '折扇',
            'status': 2,
            'assets': [{'isActive': 1, 'version': 1, 'filePath': 'https://example.com/fan.png'}],
        }

        with patch('apps.series.views._parallel_backend_gets', return_value={
            'series': {'id': 88, 'seriesName': '测试系列'},
            'episode': {'id': 77, 'episodeNumber': 1, 'episodeName': '第一集', 'status': 2},
            'shots': [lightweight_shot],
            'scenes': [{'id': 900, 'sceneName': '茶楼', 'status': 2, 'assets': []}],
            'props': [linked_unlocked_prop, unrelated_unlocked_prop],
            'role_assets': {},
        }):
            response = self.client.get('/88/episodes/77/')

        self.assertEqual(response.status_code, 200)
        prop_names = [prop['propName'] for prop in response.context['props']]
        self.assertIn('玉佩', prop_names)
        self.assertNotIn('折扇', prop_names)

    def test_episode_entry_uses_locked_series_endpoint_without_role_detail_fanout(self):
        session = self.client.session
        session['token'] = 'token-1'
        session['email'] = 'user@example.com'
        session['nickname'] = '测试用户'
        session.save()

        backend_client = Mock()
        backend_client.get.return_value = [
            {
                'id': 79,
                'seriesName': '替嫁狂妃：王爷请自重',
                'status': 2,
                'roleCount': 4,
            }
        ]

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/episodes/')

        self.assertEqual(response.status_code, 200)
        self.assertContains(response, '替嫁狂妃：王爷请自重')
        self.assertContains(response, '4 个角色')
        backend_client.get.assert_called_once_with('/v1/series/locked')


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

        self.assertIn('海带 AI 内容智能创作平台', combined)
        self.assertNotIn('Manga AI', combined)
        self.assertIn('aria-label="海带 AI 内容智能创作平台"', combined)

    def test_haidai_brand_wordmark_has_distinct_styling(self):
        template_root = Path(settings.BASE_DIR) / 'templates'
        combined = '\n'.join([
            (template_root / 'base.html').read_text(encoding='utf-8'),
            (template_root / 'series/series_list.html').read_text(encoding='utf-8'),
            (template_root / 'auth/login.html').read_text(encoding='utf-8'),
            (template_root / 'auth/register.html').read_text(encoding='utf-8'),
        ])

        self.assertIn('brand-wordmark', combined)
        self.assertIn('brand-name">海带</span>', combined)
        self.assertIn('brand-subtitle">AI 内容智能创作平台</span>', combined)

    def test_brand_uses_oss_backed_logo_image(self):
        template_root = Path(settings.BASE_DIR) / 'templates'
        combined = '\n'.join([
            (template_root / 'base.html').read_text(encoding='utf-8'),
            (template_root / 'series/series_list.html').read_text(encoding='utf-8'),
            (template_root / 'auth/login.html').read_text(encoding='utf-8'),
            (template_root / 'auth/register.html').read_text(encoding='utf-8'),
        ])

        self.assertIn('src="/site-logo.png?v=20260515"', combined)
        self.assertIn('rel="icon" type="image/png" href="/site-logo.png?v=20260515"', combined)
        self.assertNotIn('src="/api/v1/common/site-logo/"', combined)
        self.assertIn('class="brand-logo-img', combined)
        nav_brand = combined.split('<a href="/" class="flex items-center gap-3 group">', 1)[1].split('</a>', 1)[0]
        self.assertNotIn('data-lucide=', nav_brand)


class SearchEngineVisibilityTests(TestCase):
    def test_robots_txt_allows_public_pages_and_points_to_sitemap(self):
        response = self.client.get('/robots.txt')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'text/plain; charset=utf-8')
        body = response.content.decode('utf-8')
        self.assertIn('User-agent: *', body)
        self.assertIn('Allow: /$', body)
        self.assertIn('Allow: /subject-replacement/$', body)
        self.assertIn('Allow: /gpt-image2/$', body)
        self.assertIn('Disallow: /api/', body)
        self.assertIn('Disallow: /admin/', body)
        self.assertIn('Disallow: /assets/', body)
        self.assertIn('Disallow: /credits/', body)
        self.assertIn('Disallow: /user/', body)
        self.assertIn('Sitemap: https://www.yzmovie.cn/sitemap.xml', body)

    def test_sitemap_xml_lists_public_landing_pages(self):
        response = self.client.get('/sitemap.xml')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/xml; charset=utf-8')
        body = response.content.decode('utf-8')
        self.assertIn('<loc>https://www.yzmovie.cn/</loc>', body)
        self.assertIn('<loc>https://www.yzmovie.cn/subject-replacement/</loc>', body)
        self.assertIn('<loc>https://www.yzmovie.cn/gpt-image2/</loc>', body)
        self.assertNotIn('/auth/login/', body)
        self.assertNotIn('/assets/library/', body)
        self.assertNotIn('/credits/records/', body)

    def test_home_page_has_search_engine_meta_tags(self):
        response = self.client.get('/')

        self.assertEqual(response.status_code, 200)
        self.assertContains(response, '<meta name="description"', html=False)
        self.assertContains(response, 'AI短剧创作、分镜生成、角色资产、主体替换、GPT-Image2生图', html=False)
        self.assertContains(response, '<meta name="keywords"', html=False)
        self.assertContains(response, '<meta name="robots" content="index,follow">', html=False)
        self.assertContains(response, '<link rel="canonical" href="https://www.yzmovie.cn/">', html=False)
        self.assertContains(response, '<meta property="og:title" content="海带 AI 内容智能创作平台">', html=False)
        self.assertContains(response, '<meta property="og:url" content="https://www.yzmovie.cn/">', html=False)

    def test_public_tool_landing_pages_are_crawlable(self):
        expected = {
            '/subject-replacement/': 'https://www.yzmovie.cn/subject-replacement/',
            '/gpt-image2/': 'https://www.yzmovie.cn/gpt-image2/',
        }

        for path, canonical_url in expected.items():
            with self.subTest(path=path):
                response = self.client.get(path)

                self.assertEqual(response.status_code, 200)
                self.assertContains(response, '<meta name="robots" content="index,follow">', html=False)
                self.assertContains(response, f'<link rel="canonical" href="{canonical_url}">', html=False)

    def test_private_pages_are_marked_noindex(self):
        response = self.client.get('/auth/login/')

        self.assertEqual(response.status_code, 200)
        self.assertContains(response, '<meta name="robots" content="noindex,nofollow">', html=False)
        self.assertContains(response, '<link rel="canonical" href="https://www.yzmovie.cn/auth/login/">', html=False)


class SubjectReplacementCreditButtonTests(TestCase):
    def test_submit_button_shows_estimated_credit_deduction(self):
        template_path = Path(settings.BASE_DIR) / 'templates/subject_replacement/index.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('id="submitCreditText"', template)
        self.assertIn('扣除300积分', template)
        self.assertIn('const subjectReplacementCreditsPerSecond = 60;', template)
        self.assertIn("document.getElementById('submitCreditText').textContent = `扣除${estimatedCredits}积分`", template)


class EpisodeVideoCreditDisplayTests(TestCase):
    def test_episode_template_uses_current_video_credit_rates(self):
        template_path = Path(settings.BASE_DIR) / 'templates/episode/episode_detail.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn("if (videoModel === 'kling-v3-omni')", template)
        self.assertIn("creditsPerSecond = resolution === '1080p' ? 16 : 15;", template)
        self.assertIn("creditsPerSecond = 67;", template)
        self.assertIn("creditsPerSecond = isVipModel ? 16 : 11;", template)
        self.assertIn("creditsPerSecond = isVipModel ? 27 : 22;", template)


class EpisodeAssetSelectionModalTests(TestCase):
    def test_asset_selection_modal_has_prominent_selection_warning(self):
        template_path = Path(settings.BASE_DIR) / 'templates/episode/episode_detail.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('id="assetSelectionCriticalNotice"', template)
        self.assertIn('asset-selection-critical-warning', template)
        self.assertIn('sticky top-0 z-20', template)
        self.assertIn('必读', template)
        self.assertIn('请先选择需要的场景和道具', template)
        self.assertIn('后面集数用不到的道具或场景，不需要勾选生成', template)
        self.assertIn('alert-triangle', template)

    def test_asset_selection_select_all_stays_synced_with_individual_checks(self):
        template_path = Path(settings.BASE_DIR) / 'templates/episode/episode_detail.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('function getSelectableAssetCheckboxes(type)', template)
        self.assertIn('function syncAssetSelectAllState(type)', template)
        self.assertIn('function syncAllAssetSelectAllStates()', template)
        self.assertIn('function setAssetGroupChecked(type, checked)', template)
        self.assertIn('master.indeterminate = selectedCount > 0 && selectedCount < totalCount;', template)
        self.assertIn("setAssetGroupChecked('scene', this.checked);", template)
        self.assertIn("setAssetGroupChecked('prop', this.checked);", template)
        self.assertIn("syncAssetSelectAllState('scene');", template)
        self.assertIn("syncAssetSelectAllState('prop');", template)
        self.assertIn('syncAllAssetSelectAllStates();', template)


class EpisodeCreateRoleNameHintTests(TestCase):
    def test_episode_create_has_prominent_role_name_usage_hint(self):
        template_path = Path(settings.BASE_DIR) / 'templates/episode/episode_create.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('episode-role-name-hint', template)
        self.assertIn('已有角色（请在剧本中使用下方名字作为剧本里出现的人物角色名）', template)
        self.assertIn('border-amber-400/60', template)
        self.assertIn('bg-amber-400/15', template)
        self.assertIn('alert-triangle', template)
        self.assertNotIn('已有角色（请在剧本中使用这些角色名）', template)


class EpisodeDescriptionAssetRenderTests(TestCase):
    def test_auto_asset_rendering_does_not_replace_inside_generated_thumbnail_html(self):
        template_path = Path(settings.BASE_DIR) / 'templates/episode/episode_detail.html'
        template = template_path.read_text(encoding='utf-8')
        render_body = template.split('function renderDescriptionWithAutoAssets(editor)', 1)[1].split('// 渲染场景输入框', 1)[0]

        self.assertIn('function collectAutoAssetCandidates(assets)', render_body)
        self.assertIn('function renderPlainTextWithAutoThumbs(text, assets)', render_body)
        self.assertIn('function renderFormattedDescriptionWithAutoThumbs(text, assets)', render_body)
        self.assertIn("if (text.startsWith('剧情【', index))", render_body)
        self.assertIn('fragments.push(renderPlainTextWithAutoThumbs(dramaText, assets));', render_body)
        self.assertIn('renderFormattedDescriptionWithAutoThumbs(processedDescription, allAssets)', render_body)
        self.assertIn('fragments.push(escapeHtml(text.slice(lastIndex, match.index)));', render_body)
        self.assertIn("fragments.push(escapeHtml(matchText), createThumbHtml(asset, 'auto'));", render_body)
        self.assertIn("return fragments.join('');", render_body)
        self.assertNotIn('result = result.replace(regex, asset.name + createThumbHtml', render_body)


class SeriesReviewAssetGateTests(TestCase):
    def test_series_review_requires_successful_role_assets_before_confirm_or_lock(self):
        template_path = Path(settings.BASE_DIR) / 'templates/series/series_review.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('data-has-usable-asset="{{ role.has_usable_asset|yesno:\'true,false\' }}"', template)
        self.assertIn('{% if role.status >= 2 or not role.has_usable_asset %}disabled{% endif %}', template)
        self.assertIn('function roleHasUsableAsset(card)', template)
        self.assertIn('let confirmable = 0;', template)
        self.assertIn('if (roleHasUsableAsset(card)) {', template)
        self.assertIn('nextBtn.title = missingAssets > 0 ? `还有 ${missingAssets} 个角色图片未生成成功，不能进入下一步` : \'\';', template)
        self.assertIn("showToast('请先重新生成失败的角色图片，再确认角色', 'warning');", template)
        self.assertIn("showToast('还有角色图片未生成成功，不能进入下一步', 'warning');", template)
        self.assertIn("const buttons = document.querySelectorAll('.confirm-btn:not([disabled])');", template)

    def test_new_clothing_uses_role_style_and_requires_clothing_prompt(self):
        template_path = Path(settings.BASE_DIR) / 'templates/series/series_review.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('data-role-style-keywords="{{ role.styleKeywords|default:\'\' }}"', template)
        self.assertIn('data-role-style-key="{{ role.styleKey|default:\'\' }}"', template)
        self.assertIn('服装描述 <span class="text-red-400">*</span>', template)
        self.assertIn('id="newClothingPrompt"', template)
        self.assertIn('required data-required-message="请填写服装描述"', template)
        self.assertIn('id="newClothingStyle"', template)
        self.assertIn('disabled', template.split('id="newClothingStyle"', 1)[1].split('</select>', 1)[0])
        self.assertIn('该风格来自角色创建时的图片风格，生成新服装时保持一致', template)
        self.assertIn('applyLockedNewClothingStyle(roleCard)', template)
        new_clothing_style_block = template.split('function applyLockedNewClothingStyle(roleCard)', 1)[1].split('// 为没有资产的角色生成默认服装', 1)[0]
        self.assertIn('const activeSettings = getCreationSettingsFromTab(activeTab, roleCard);', new_clothing_style_block)
        self.assertIn('const roleStyleKey = roleCard?.dataset?.roleStyleKey', new_clothing_style_block)
        self.assertIn("resolveStyleKey('', roleCard?.dataset?.roleStyleKeywords || '')", new_clothing_style_block)
        self.assertNotIn('seriesStyle', new_clothing_style_block)
        self.assertIn("showToast('请填写服装描述', 'error');", template)
        self.assertNotIn('服装描述（可选）', template)

    def test_series_review_role_cards_show_asset_creation_settings(self):
        template_path = Path(settings.BASE_DIR) / 'templates/series/series_review.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('role-creation-settings', template)
        self.assertIn('创建配置', template)
        self.assertIn('系列风格', template)
        self.assertIn('data-style-key="{{ role.assets.0.styleKey|default:role.styleKey|default:\'\' }}"', template)
        self.assertIn('data-style-keywords="{{ role.assets.0.styleKeywords|default:role.styleKeywords|default:\'\' }}"', template)
        self.assertIn('data-aspect-ratio="{{ asset.aspectRatio|default:role.aspectRatio|default:\'\' }}"', template)
        self.assertIn('data-quality="{{ asset.quality|default:role.quality|default:\'\' }}"', template)
        self.assertIn('data-style-key="{{ asset.styleKey|default:role.styleKey|default:\'\' }}"', template)
        self.assertIn('data-style-keywords="{{ asset.styleKeywords|default:role.styleKeywords|default:\'\' }}"', template)
        self.assertIn('data-face-closeup-view="{{ asset.faceCloseupView|yesno:\'true,false\' }}"', template)
        self.assertIn('role-creation-style', template)
        self.assertIn('initRoleCreationSettingsDisplay', template)
        self.assertIn('applyCreationSettingsFromTab', template)
        self.assertIn('getCreationSettingsFromTab', template)
        self.assertIn('resolveStyleKey', template)
        self.assertNotIn('{{ role.styleKeywords|default:"-" }}', template)
        self.assertIn('人物比例', template)
        self.assertIn('{{ role.assets.0.aspectRatio|default:role.aspectRatio|default:"-" }}', template)
        self.assertIn('清晰度', template)
        self.assertIn('role-creation-quality', template)
        self.assertIn('QUALITY_NAMES', template)
        self.assertIn('高级方式', template)
        self.assertIn('{% if role.assets.0.detailedView or role.assets.0.faceCloseupView or role.detailedView or role.faceCloseupView %}', template)
        self.assertIn('已启用', template)
        self.assertIn('未启用', template)
        self.assertIn('精细三视图', template)
        self.assertIn('大头特写+三视图', template)
        self.assertIn('applyCreationSettingsFromTab(roleCard, tab);', template)
        self.assertIn('applyVersionSettingsToTab(tab, versionSettings);', template)
        self.assertIn('styleKey: styleKey', template)
        self.assertGreater(template.index('<!-- Prompt Info -->'), template.index('role-creation-settings'))
        settings_to_prompt = template.split('role-creation-settings', 1)[1].split('<!-- Prompt Info -->', 1)[0]
        self.assertNotIn('lg:grid-cols-5', settings_to_prompt)

    def test_series_review_default_role_prompt_prefers_original_prompt_over_system_prompt(self):
        template_path = Path(settings.BASE_DIR) / 'templates/series/series_review.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('{% if role.assets.0.clothingId == 1 %}', template)
        self.assertIn("role.originalPrompt|default:role.assets.0.clothingPrompt|default:'暂无提示词'", template)
        self.assertNotIn("role.assets.0.clothingPrompt|default:role.originalPrompt|default:role.customPrompt", template)


class RoleNameGuidanceTests(TestCase):
    def test_create_series_and_review_add_role_show_name_only_warning(self):
        template_root = Path(settings.BASE_DIR) / 'templates/series'
        init_template = (template_root / 'series_init.html').read_text(encoding='utf-8')
        review_template = (template_root / 'series_review.html').read_text(encoding='utf-8')

        for template in (init_template, review_template):
            self.assertIn('role-name-only-warning', template)
            self.assertIn('角色名称只填人名', template)
            self.assertIn('只输入人名，例如：李强、周也', template)
            self.assertIn('不要填写主播、演员、程序员、女主等职业身份', template)

    def test_create_series_and_review_add_role_require_role_name(self):
        template_root = Path(settings.BASE_DIR) / 'templates/series'
        init_template = (template_root / 'series_init.html').read_text(encoding='utf-8')
        review_template = (template_root / 'series_review.html').read_text(encoding='utf-8')

        self.assertIn('class="input-modern text-base font-medium character-name w-full"', init_template)
        self.assertIn('required data-required-message="请填写角色名称"', init_template)
        self.assertIn("if (!name) {", init_template)
        self.assertIn("focusInvalidCharacterField(nameInput, '请填写角色名称');", init_template)
        self.assertIn('function focusInvalidCharacterField(field, message)', init_template)

        self.assertIn('id="newRoleName"', review_template)
        self.assertIn('required data-required-message="请填写角色名称"', review_template)
        self.assertIn("focusRequiredRoleField(document.getElementById('newRoleName'), '请填写角色名称');", review_template)
        self.assertIn('function focusRequiredRoleField(field, message)', review_template)

    def test_role_prompt_placeholder_asks_for_character_traits_not_three_view_terms(self):
        template_root = Path(settings.BASE_DIR) / 'templates/series'
        init_template = (template_root / 'series_init.html').read_text(encoding='utf-8')
        review_template = (template_root / 'series_review.html').read_text(encoding='utf-8')

        prompt_placeholder = (
            'placeholder="只描述角色特点、外貌、性格，例如：黑色短发，眼神冷静，穿深色风衣，做事果断沉稳。'
            '不需要写生成三视图、角色设定板等话术，系统会自动处理。"'
        )
        self.assertIn(prompt_placeholder, init_template)
        self.assertIn(prompt_placeholder, review_template)
        self.assertIn('不需要写生成三视图、角色设定板等话术，系统会自动处理', init_template)
        self.assertIn('不需要写生成三视图、角色设定板等话术，系统会自动处理', review_template)
        self.assertNotIn('placeholder="输入角色描述，用于生成三视图图片。', review_template)
        self.assertNotIn('placeholder="输入角色描述，用于生成三视图图片。例如：', init_template)

    def test_role_forms_offer_manual_full_body_upload_with_crop_and_seedance_warning(self):
        template_root = Path(settings.BASE_DIR) / 'templates/series'
        init_template = (template_root / 'series_init.html').read_text(encoding='utf-8')
        review_template = (template_root / 'series_review.html').read_text(encoding='utf-8')

        for template in (init_template, review_template):
            self.assertIn('自行上传人物全身图', template)
            self.assertIn('推荐三视图或者更精细的图', template)
            self.assertIn('role-manual-upload-checkbox', template)
            self.assertIn('roleCropModal', template)
            self.assertIn('manualRoleUploadWarning', template)
            self.assertIn('即梦生成', template)
            self.assertIn('Seedance 2.0', template)
            self.assertIn('真人风格兼容性提示', template)
            self.assertIn('上传非即梦生成的人物全身图时，Seedance 2.0 系列模型在分镜视频生成环节可能触发真人隐私校验。', template)
            self.assertIn('建议改用平台生成的角色三视图，以提升后续视频生成稳定性。', template)
            self.assertNotIn('如果用户上传的人物全身图不是即梦生成的', template)
            self.assertIn("formData.append('type', 'role')", template)
            self.assertIn('uploadedImageUrl', template)
            self.assertIn('toggleManualRoleUpload', template)

        self.assertIn('id="addRoleCreditBadge"', review_template)
        self.assertIn('id="addRoleFreeBadge"', review_template)
        self.assertIn('不扣积分', review_template)
        self.assertIn('function updateAddRoleSubmitCreditDisplay()', review_template)
        self.assertIn("creditBadge?.classList.toggle('hidden', manualUploadEnabled);", review_template)
        self.assertIn("freeBadge?.classList.toggle('hidden', !manualUploadEnabled);", review_template)

    def test_role_layout_options_are_marked_as_optional_advanced_choices(self):
        template_root = Path(settings.BASE_DIR) / 'templates/series'
        init_template = (template_root / 'series_init.html').read_text(encoding='utf-8')
        review_template = (template_root / 'series_review.html').read_text(encoding='utf-8')

        for template in (init_template, review_template):
            self.assertIn('role-generation-mode-guide', template)
            self.assertIn('推荐使用默认生成', template)
            self.assertIn('系统会根据角色描述自动生成角色三视图，适合大多数创作场景。', template)
            self.assertIn('如需指定版式或使用本地人物图，可启用下方高级方式。', template)
            self.assertIn('role-advanced-options', template)
            self.assertIn('role-advanced-options-panel hidden', template)
            self.assertIn('role-advanced-options-toggle', template)
            self.assertIn('启用高级方式', template)
            self.assertIn('默认不启用', template)
            self.assertIn('默认收起，系统将按角色描述生成角色三视图。', template)
            self.assertIn('toggleRoleAdvancedOptions', template)
            self.assertIn('可选', template)
            self.assertIn('高级方式', template)

        self.assertIn('角色名称 <span class="text-red-400">*</span>', init_template)
        self.assertIn('角色名称 <span class="text-red-400">*</span>', review_template)


class GptImage2HomeTests(TestCase):
    def test_home_page_has_recent_update_announcement(self):
        template_path = Path(settings.BASE_DIR) / 'templates/series/series_list.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('home-hero', template)
        self.assertIn('home-announcement', template)
        self.assertIn('home-announcement-pinned', template)
        self.assertIn('home-announcement-pinned-label', template)
        self.assertIn('home-announcement-pinned-title', template)
        self.assertIn('home-announcement-scroll', template)
        self.assertIn('home-announcement-story', template)
        self.assertIn('home-announcement-index', template)
        self.assertIn('announcement-scroll-y', template)
        self.assertIn('animation: announcement-scroll-y 12s linear infinite;', template)
        self.assertIn('top: -12px;', template)
        self.assertIn('left: -40px;', template)
        self.assertIn('width: 400px;', template)
        self.assertIn('home-announcement-signal', template)
        self.assertIn('home-announcement-meta', template)
        self.assertIn('home-announcement-chip-row', template)
        self.assertIn('backdrop-filter: blur(18px);', template)
        self.assertIn('置顶通知', template)
        self.assertIn('积分价格全面下调', template)
        self.assertIn('视频和图片生成积分价格今日起全面下调。', template)
        self.assertIn('覆盖 Seedance 2.0、Fast、Kling v3 Omni、GPT-Image2，具体消耗以生成页面显示为准。', template)
        self.assertIn('height: 286px;', template)
        self.assertIn('height: 112px;', template)
        self.assertIn('近期更新', template)
        self.assertIn('{% now "Y.m.d" %}', template)
        self.assertNotIn('<div class="home-announcement-date">2026.05.16</div>', template)
        self.assertIn('电商试穿升级 / 模特复用 / 场景库上线', template)
        self.assertIn('AI 模特支持复用，已锁定模特可从模特库沿用到新系列，不用重复生成。', template)
        self.assertIn('商品支持多图参考，可上传主图、细节图、上身参考，帮助保留颜色、版型和图案细节。', template)
        self.assertIn('场景库上线，支持街拍、棚拍、直播间、商场试衣间、海边旅拍、城市夜景等出片场景。', template)
        self.assertIn('试穿视频表现升级，支持走动、转身、细节展示、表情互动和镜头变化，不再只是站着展示。', template)
        self.assertIn('视频生成体验优化，生成中状态可恢复，完成后支持大窗播放和下载。', template)
        self.assertIn('当前重点开放试穿展示视频能力，适合电商种草、服装展示和模特穿搭内容制作。', template)
        self.assertIn('Seedance 2.0', template)
        self.assertIn('真人人脸', template)
        self.assertIn('Kling v3 Omni', template)
        self.assertIn('分镜解析', template)
        self.assertIn('角色创建', template)
        self.assertIn('资产审核', template)
        self.assertNotIn('后续我们会继续补充更多视频模型', template)

    def test_home_page_links_to_gpt_image2_workspace(self):
        template_path = Path(settings.BASE_DIR) / 'templates/series/series_list.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('href="/gpt-image2/"', template)
        self.assertIn('<span>GPT-Image2 生图</span>\n                    <span class="home-new-badge">NEW</span>', template)
        self.assertIn('AI 工具', template)
        self.assertIn('基础工作台', template)
        self.assertIn('资产与账户', template)
        self.assertNotIn('id="gptImage2Modal"', template)
        self.assertNotIn('function openGptImage2Modal()', template)

    def test_home_page_has_ecommerce_entries_under_workbench(self):
        template_path = Path(settings.BASE_DIR) / 'templates/series/series_list.html'
        template = template_path.read_text(encoding='utf-8')

        workbench_block = template.split('基础工作台', 1)[1].split('AI 工具', 1)[0]
        self.assertIn('电商带货', workbench_block)
        self.assertIn('创建电商系列', workbench_block)
        self.assertIn('href="/commerce/"', workbench_block)
        self.assertIn('href="/commerce/series/create/"', workbench_block)
        self.assertIn('<div class="home-action-subgroup-label">短剧工作台</div>', workbench_block)
        self.assertIn('home-commerce-launch', workbench_block)
        self.assertIn('home-commerce-label', workbench_block)
        self.assertIn('<span>电商工作台</span>', workbench_block)
        self.assertIn('home-commerce-launch-badge', workbench_block)
        self.assertIn('data-lucide="zap"', workbench_block)
        self.assertIn('重磅上新', workbench_block)
        ecommerce_create_block = workbench_block.split('href="/commerce/series/create/"', 1)[1].split('</a>', 1)[0]
        self.assertIn('data-lucide="plus"', ecommerce_create_block)
        self.assertIn('home-action-commerce-primary', ecommerce_create_block)
        self.assertIn('<span>创建电商系列</span>', ecommerce_create_block)
        self.assertNotIn('home-action-copy', ecommerce_create_block)
        self.assertNotIn('<small>', ecommerce_create_block)
        self.assertNotIn('data-lucide="shopping-bag"', ecommerce_create_block)
        self.assertIn('.home-commerce-launch .home-action {', template)
        self.assertIn('justify-content: center;', template.split('.home-commerce-launch .home-action {', 1)[1].split('}', 1)[0])
        self.assertNotIn('home-action-disabled', workbench_block)
        self.assertNotIn('等待上线', workbench_block)
        self.assertNotIn('功能研制中', workbench_block)
        self.assertIn('<div class="home-action-group">\n            <div class="home-action-label">基础工作台</div>', template)
        self.assertIn('<div class="home-action-group home-action-group-accent">\n            <div class="home-action-label">AI 工具</div>', template)
        self.assertIn('<div class="home-action-group home-action-group-account">\n            <div class="home-action-label">资产与账户</div>', template)
        action_board_style = template.split('.home-action-board {', 1)[1].split('}', 1)[0]
        self.assertIn('display: flex;', action_board_style)
        self.assertIn('align-items: flex-start;', action_board_style)
        action_group_style = template.split('.home-action-group {', 1)[1].split('}', 1)[0]
        self.assertIn('align-self: flex-start;', action_group_style)
        self.assertIn('height: auto;', action_group_style)
        self.assertIn('flex: 1.25 1 0;', action_group_style)
        self.assertIn('min-width: 0;', action_group_style)
        account_group_style = template.split('.home-action-group-account {', 1)[1].split('}', 1)[0]
        self.assertIn('flex-grow: 0.72;', account_group_style)
        self.assertIn('min-width: 210px;', account_group_style)
        commerce_launch_style = template.split('.home-commerce-launch {', 1)[1].split('}', 1)[0]
        self.assertIn('position: relative;', commerce_launch_style)
        self.assertIn('overflow: hidden;', commerce_launch_style)
        self.assertIn('border-color: rgba(251, 191, 36, 0.38);', commerce_launch_style)

    def test_credit_records_home_button_has_no_leading_icon(self):
        template_path = Path(settings.BASE_DIR) / 'templates/series/series_list.html'
        template = template_path.read_text(encoding='utf-8')
        credit_button = template.split('href="/credits/records/"', 1)[1].split('</a>', 1)[0]

        self.assertIn('<span>积分记录</span>', credit_button)
        self.assertNotIn('data-lucide=', credit_button)

    def test_gpt_image2_page_contains_generator_and_recent_tasks(self):
        template_path = Path(settings.BASE_DIR) / 'templates/series/gpt_image2.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('id="gptImage2Form"', template)
        self.assertIn('GPT-Image2 生图', template)
        self.assertIn('最近任务', template)
        self.assertIn('id="gptImage2TaskList"', template)
        self.assertIn('/api/v1/gpt-image2/generate/', template)
        self.assertIn('/api/v1/gpt-image2/upload-reference/', template)
        self.assertIn('/api/v1/gpt-image2/?limit=50', template)
        self.assertIn('pollGptImage2Task', template)
        self.assertIn('loadGptImage2Tasks', template)
        self.assertIn('referenceImageUrl', template)
        self.assertIn('id="gptImage2Resolution"', template)
        self.assertIn('<option value="1k">1K</option>', template)
        self.assertIn('<option value="2k" selected>2K</option>', template)
        self.assertIn('<option value="4k">4K</option>', template)
        self.assertIn('gpt-image-aspect-frame', template)
        self.assertIn('getGptImage2AspectStyle(task.aspectRatio)', template)
        self.assertIn('清晰度：${escapeGptImage2Html(formatGptImage2Resolution(task.resolution))}', template)
        self.assertIn('const GPT_IMAGE2_CREDIT_COST = 6;', template)
        self.assertIn('6 积分/张', template)
        self.assertIn('扣除6积分', template)
        self.assertIn('失败自动返还', template)
        self.assertIn('gpt-image-model-badge', template)
        self.assertIn('formatGptImage2ModelLabel(task.model)', template)
        self.assertIn('getGptImage2CreditCost(task)', template)
        self.assertIn('const GPT_IMAGE2_SUPPORTED_ASPECTS_BY_RESOLUTION', template)
        self.assertIn('function refreshGptImage2AspectOptions()', template)
        self.assertIn("resolutionSelect?.addEventListener('change', refreshGptImage2AspectOptions);", template)
        self.assertIn("['16:9', '9:16', '2:1', '1:2', '21:9']", template)
        self.assertNotIn('value="9:21"', template)

    def test_gpt_image2_generate_forwards_to_backend(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()

        backend_client = Mock()
        backend_client.post.return_value = {'id': 12, 'status': 'pending', 'progressPercent': 5}

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post(
                '/api/v1/gpt-image2/generate/',
                data=json.dumps({
                    'prompt': '古风少女海报',
                    'aspectRatio': '1:1',
                    'resolution': '4k',
                    'referenceImageUrl': 'https://oss.example.com/ref.png',
                }),
                content_type='application/json',
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['data']['id'], 12)
        self.assertEqual(response.json()['data']['status'], 'pending')
        backend_client.post.assert_called_once_with('/v1/gpt-image2/generate', {
            'prompt': '古风少女海报',
            'aspectRatio': '1:1',
            'resolution': '4k',
            'referenceImageUrl': 'https://oss.example.com/ref.png',
        })

    def test_gpt_image2_task_detail_and_latest_forward_to_backend(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()

        backend_client = Mock()
        backend_client.get.side_effect = [
            {'id': 12, 'status': 'running'},
            {'id': 12, 'status': 'succeeded', 'imageUrl': 'https://oss.example.com/result.png'},
        ]

        with patch('apps.series.views.get_client', return_value=backend_client):
            detail_response = self.client.get('/api/v1/gpt-image2/12/')
            latest_response = self.client.get('/api/v1/gpt-image2/latest/')

        self.assertEqual(detail_response.status_code, 200)
        self.assertEqual(detail_response.json()['data']['status'], 'running')
        self.assertEqual(latest_response.status_code, 200)
        self.assertEqual(latest_response.json()['data']['imageUrl'], 'https://oss.example.com/result.png')
        backend_client.get.assert_any_call('/v1/gpt-image2/12')
        backend_client.get.assert_any_call('/v1/gpt-image2/latest')

    def test_gpt_image2_task_list_forwards_to_backend(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()

        backend_client = Mock()
        backend_client.get.return_value = [
            {'id': 12, 'status': 'running'},
            {'id': 11, 'status': 'succeeded', 'imageUrl': 'https://oss.example.com/result.png'},
        ]

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/api/v1/gpt-image2/?limit=50')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.json()['data']), 2)
        backend_client.get.assert_called_once_with('/v1/gpt-image2?limit=50')

    def test_gpt_image2_running_task_restores_from_local_storage(self):
        template_path = Path(settings.BASE_DIR) / 'templates/series/gpt_image2.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn("GPT_IMAGE2_TASK_STORAGE_KEY = 'gptImage2LatestTask'", template)
        self.assertIn('function restoreGptImage2TaskFromStorage()', template)
        self.assertIn('persistGptImage2TaskState(task)', template)
        self.assertIn("if (e.key === GPT_IMAGE2_TASK_STORAGE_KEY", template)

    def test_gpt_image2_submit_does_not_block_while_background_generates(self):
        template_path = Path(settings.BASE_DIR) / 'templates/series/gpt_image2.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('提交中...', template)
        self.assertNotIn('已提交后台生成', template)
        self.assertIn("setGptImage2Busy(false);", template)
        self.assertIn('startGptImage2Polling();', template)
        self.assertIn('const runningTasks = gptImage2Tasks.filter(isGptImage2Running);', template)
        self.assertIn('restoreGptImage2TaskFromStorage();\n    loadGptImage2Tasks();', template)

    def test_gpt_image2_result_preview_supports_zoom_and_download(self):
        template_path = Path(settings.BASE_DIR) / 'templates/series/gpt_image2.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('查看大图', template)
        self.assertIn('downloadGptImage2Image(imageUrl)', template)
        self.assertIn('function setGptImage2PreviewScale(scale)', template)
        self.assertIn('function zoomGptImage2Preview(delta)', template)
        self.assertIn('function toggleGptImage2PreviewZoom()', template)
        self.assertIn('id="gptImage2ImagePreviewDownload"', template)
        self.assertIn("document.getElementById('gptImage2ImagePreviewLarge')?.addEventListener('click', toggleGptImage2PreviewZoom);", template)
        self.assertIn('<button type="button" class="gpt-image-preview-btn gpt-image-aspect-frame', template)
        self.assertIn('<img src="${escapeGptImage2Html(imageUrl)}" class="max-h-full max-w-full object-contain transition-transform group-hover:scale-[1.02]" alt="生成结果">', template)
        self.assertNotIn('查看结果', template)
        self.assertNotIn('打开原图', template)

    def test_gpt_image2_recent_tasks_are_paginated_three_per_page(self):
        template_path = Path(settings.BASE_DIR) / 'templates/series/gpt_image2.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('const GPT_IMAGE2_TASKS_PER_PAGE = 3;', template)
        self.assertIn('let gptImage2CurrentPage = 1;', template)
        self.assertIn('const pageTasks = gptImage2Tasks.slice(startIndex, startIndex + GPT_IMAGE2_TASKS_PER_PAGE);', template)
        self.assertIn('id="gptImage2Pagination"', template)
        self.assertIn('id="gptImage2PrevPage"', template)
        self.assertIn('id="gptImage2NextPage"', template)
        self.assertIn('function renderGptImage2Pagination()', template)
        self.assertIn('function changeGptImage2Page(delta)', template)
        self.assertIn('一页最多 3 条', template)


class EpisodeDetailShotCreditTests(TestCase):
    def test_pending_shot_generation_button_is_not_gated_by_episode_status(self):
        template_path = Path(settings.BASE_DIR) / 'templates/episode/episode_detail.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('{% if shot.generationStatus == 0 and shot.status != 1 %}', template)
        self.assertNotIn('{% if shot.generationStatus == 0 and episode.status == 2 %}', template)

        render_actions_body = template.split('function renderPendingShotActions(card)', 1)[1].split('function applyPendingShotCardState(card)', 1)[0]
        self.assertIn('if (status === 0) {', render_actions_body)
        self.assertNotIn('if (status === 0 && episodeStatus === 2)', render_actions_body)

    def test_unlocked_shot_rebinds_model_select_and_refreshes_regenerate_credit(self):
        template_path = Path(settings.BASE_DIR) / 'templates/episode/episode_detail.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('function bindShotSelectControls(card)', template)
        apply_pending_body = template.split('function applyPendingShotCardState(card)', 1)[1].split('function splitShotListsByReviewStatus()', 1)[0]
        self.assertIn('bindShotSelectControls(card);', apply_pending_body)
        self.assertIn('updateShotCreditDisplay(card);', apply_pending_body)

        bind_body = template.split('function bindShotSelectControls(card)', 1)[1].split('// 初始化内联编辑', 1)[0]
        self.assertIn("videoModelSelect.dataset.creditBound = 'true';", bind_body)
        self.assertIn('updateResolutionOptions(card, videoModelSelect.value);', bind_body)
        self.assertIn('updateShotCreditDisplay(card);', bind_body)
        self.assertIn("saveShotSettings(shotId, ['videoModel', 'resolution']);", bind_body)

    def test_regenerate_saves_current_reference_images_before_submit(self):
        template_path = Path(settings.BASE_DIR) / 'templates/episode/episode_detail.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('function collectShotReferenceImages(card)', template)
        submit_body = template.split('async function submitShotGeneration(button)', 1)[1].split('async function handleShotRegenerateClick()', 1)[0]
        self.assertIn('const referenceImages = collectShotReferenceImages(card);', submit_body)
        self.assertIn('const payload = { referenceUrls, referenceImages, shotUpdate, generationStartTime };', submit_body)
        self.assertIn('body: JSON.stringify(payload)', submit_body)

        regenerate_body = template.split('async function handleShotRegenerateClick()', 1)[1].split('async function uploadShotVideoFile()', 1)[0]
        self.assertIn('return submitShotGeneration(this);', regenerate_body)

    def test_generate_uses_same_reference_image_submit_flow_as_regenerate(self):
        template_path = Path(settings.BASE_DIR) / 'templates/episode/episode_detail.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('async function submitShotGeneration(button)', template)
        submit_body = template.split('async function submitShotGeneration(button)', 1)[1].split('async function handleShotRegenerateClick()', 1)[0]
        self.assertIn('const shotUpdate = buildShotGenerationUpdatePayload(card);', submit_body)
        self.assertIn('const referenceImages = collectShotReferenceImages(card);', submit_body)
        self.assertIn('const payload = { referenceUrls, referenceImages, shotUpdate, generationStartTime };', submit_body)
        self.assertIn('body: JSON.stringify(payload)', submit_body)

        generate_bind_body = template.split('// 单个生成', 1)[1].split('// 重新生成', 1)[0]
        self.assertIn("btn.addEventListener('click', function() {", generate_bind_body)
        self.assertIn('submitShotGeneration(this);', generate_bind_body)

    def test_generation_enters_generating_immediately_and_posts_start_time(self):
        template_path = Path(settings.BASE_DIR) / 'templates/episode/episode_detail.html'
        template = template_path.read_text(encoding='utf-8')
        submit_body = template.split('async function submitShotGeneration(button)', 1)[1].split('async function handleShotRegenerateClick()', 1)[0]
        before_prepare_fetch = submit_body.split("const prepareResponse = await fetch(`/api/v1/shots/${shotId}/generation/prepare`", 1)[0]
        after_result_success = submit_body.split('if (!(data.success || data.code === 200))', 1)[1]
        new_submit_body = before_prepare_fetch.split("button.disabled = true;", 1)[1]

        self.assertNotIn('startTimer(button);', submit_body)
        self.assertNotIn('updateCardToSubmitting(card, button);', submit_body)
        self.assertNotIn('startSubmittingTimer(button);', submit_body)
        self.assertNotIn('stopSubmittingTimer(button);', submit_body)
        self.assertNotIn('await saveShotInline(shotId);', submit_body)
        self.assertNotIn('await persistShotReferenceImagesForGeneration(shotId, card);', submit_body)
        self.assertIn('const generationStartTime = formatBackendLocalDateTime(new Date());', submit_body)
        self.assertIn('const shotUpdate = buildShotGenerationUpdatePayload(card);', submit_body)
        self.assertIn('updateCardToGenerating(card, button, generationStartTime);', new_submit_body)
        self.assertIn('startShotGenerationPolling();', new_submit_body)
        self.assertIn('const payload = { referenceUrls, referenceImages, shotUpdate, generationStartTime };', submit_body)
        self.assertIn('body: JSON.stringify(payload)', submit_body)
        self.assertIn("fetch(`/api/v1/shots/${shotId}/generation/start`", after_result_success)
        self.assertIn('markShotGenerationUpdateSaved(card, shotUpdate);', submit_body)

    def test_generation_timer_resyncs_when_backend_start_time_changes(self):
        template_path = Path(settings.BASE_DIR) / 'templates/episode/episode_detail.html'
        template = template_path.read_text(encoding='utf-8')
        timer_body = template.split('function startGeneratingTimers()', 1)[1].split('// Helper functions for form modals', 1)[0]
        show_actions_body = template.split('function showGeneratingActions(card, startTimeValue = null)', 1)[1].split('function restoreGeneratingActions(card)', 1)[0]

        self.assertIn("const startTimeStr = timer.dataset.startTime || timer.dataset.resolvedStartTime;", timer_body)
        self.assertIn('timer.dataset.resolvedStartTime = startTime.toISOString();', timer_body)
        self.assertIn('if (timer.dataset.timerActive ===', timer_body)
        self.assertIn('updateGeneratingTimer(timer);', timer_body)
        self.assertIn('return;', timer_body)
        self.assertIn('function updateGeneratingTimer(timer)', template)
        self.assertNotIn("if (timer.dataset.timerActive === 'true') return;", timer_body)
        self.assertIn("placeholder.dataset.startTime = parseGenerationStartTime(startTimeValue).toISOString();", show_actions_body)
        self.assertNotIn("placeholder.dataset.timerActive = 'false';", show_actions_body)

    def test_shot_generate_proxy_forwards_reference_images(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()

        backend_client = Mock()
        payload = {
            'referenceUrls': ['https://oss.example.com/scene.png'],
            'generationStartTime': '2026-05-16T05:30:00',
            'shotUpdate': {
                'duration': 8,
                'resolution': '720p',
                'aspectRatio': '16:9',
                'videoModel': 'kling-v3-omni',
            },
            'referenceImages': [
                {
                    'imageType': 'role',
                    'referenceId': 148,
                    'referenceName': '沈清欢',
                    'imageUrl': 'https://oss.example.com/role.png',
                    'displayOrder': 0,
                    'isUserAdded': True,
                }
            ],
        }

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post(
                '/api/v1/shots/629/generate-with-references/',
                data=json.dumps(payload),
                content_type='application/json',
            )

        self.assertEqual(response.status_code, 200)
        backend_client.post.assert_called_once_with('/v1/shots/629/generate-with-references', payload)

    def test_shot_generation_prepare_proxy_forwards_payload(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()

        backend_client = Mock()
        backend_client.post.return_value = {
            'id': 633,
            'generationStatus': 1,
            'generationStartTime': '2026-05-16T18:40:00',
        }
        payload = {
            'referenceUrls': ['https://oss.example.com/scene.png'],
            'referenceImages': [
                {'imageType': 'role', 'imageUrl': 'https://oss.example.com/role.png'}
            ],
            'shotUpdate': {
                'duration': 8,
                'resolution': '720p',
                'aspectRatio': '16:9',
                'videoModel': 'kling-v3-omni',
            },
            'generationStartTime': '2026-05-16T18:40:00',
        }

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post(
                '/api/v1/shots/633/generation/prepare',
                data=json.dumps(payload),
                content_type='application/json',
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['data']['generationStatus'], 1)
        backend_client.post.assert_called_once_with('/v1/shots/633/generation/prepare', payload)

    def test_shot_generation_start_proxy_forwards_payload(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()

        backend_client = Mock()
        payload = {'referenceUrls': ['https://oss.example.com/scene.png']}

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post(
                '/api/v1/shots/633/generation/start',
                data=json.dumps(payload),
                content_type='application/json',
            )

        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json()['success'])
        backend_client.post.assert_called_once_with('/v1/shots/633/generation/start', payload)

    def test_episode_page_prices_kling_v3_omni_at_current_rates(self):
        template_path = Path(settings.BASE_DIR) / 'templates/episode/episode_detail.html'
        template = template_path.read_text()

        self.assertIn("if (videoModel === 'kling-v3-omni')", template)
        self.assertIn("creditsPerSecond = resolution === '1080p' ? 16 : 15;", template)


class CreditAdminDashboardTests(TestCase):
    def test_credit_admin_page_is_only_visible_to_kudo_shinichi(self):
        response = self.client.get('/admin/credits/')
        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], '/auth/login/')

        session = self.client.session
        session['token'] = 'token-2'
        session['nickname'] = '普通用户'
        session['email'] = 'creator@example.com'
        session.save()

        response = self.client.get('/admin/credits/')
        self.assertEqual(response.status_code, 403)

        session = self.client.session
        session['token'] = 'token-1'
        session['nickname'] = '工藤新一'
        session['email'] = '1198693014@qq.com'
        session.save()

        response = self.client.get('/admin/credits/')
        self.assertEqual(response.status_code, 200)
        self.assertContains(response, '积分管理后台')

    def test_credit_admin_dashboard_api_forwards_to_backend(self):
        session = self.client.session
        session['token'] = 'token-1'
        session['nickname'] = '工藤新一'
        session['email'] = '1198693014@qq.com'
        session.save()

        backend_client = Mock()
        backend_client.get.return_value = {
            'totalUsers': 2,
            'totalBalance': 10928,
            'hourlyUsage': [],
            'recentRecords': [],
        }

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/api/admin/credits/dashboard/?hours=24&recordPage=2&recordPageSize=15&nickname=工藤')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['data']['totalBalance'], 10928)
        backend_client.get.assert_called_once_with('/v1/admin/credits/dashboard?hours=24&recordPage=2&recordPageSize=15&nickname=%E5%B7%A5%E8%97%A4')

    def test_credit_admin_template_contains_chart_and_tables(self):
        template_path = Path(settings.BASE_DIR) / 'templates/credits/admin_dashboard.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('id="creditUsageChart"', template)
        self.assertIn('drawCreditUsageChart', template)
        self.assertIn('用户积分余额', template)
        self.assertIn('最近积分流水', template)
        self.assertIn('近 3 天', template)
        self.assertIn('id="creditRecordPagination"', template)
        self.assertIn('id="creditRecordNicknameSearch"', template)
        self.assertIn('searchCreditRecordsByNickname', template)
        self.assertIn('clearCreditRecordNicknameSearch', template)
        self.assertIn('nickname=${encodeURIComponent(creditRecordNicknameKeyword)}', template)
        self.assertIn('function changeCreditRecordPage(delta)', template)
        self.assertIn('changeCreditRecordPage(1)', template)
        self.assertIn('renderTodayDeductedDetails', template)
        self.assertIn('id="todayDeductedDetails"', template)

    def test_credit_admin_chart_is_responsive_and_labeled(self):
        template_path = Path(settings.BASE_DIR) / 'templates/credits/admin_dashboard.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('resizeCreditUsageCanvas', template)
        self.assertIn('window.devicePixelRatio', template)
        self.assertIn('drawYAxisLabels', template)
        self.assertIn('drawXAxisLabels', template)
        self.assertIn('window.addEventListener(\'resize\'', template)


class CommerceFlowTests(TestCase):
    def test_commerce_create_forwards_selected_ratio_and_fixed_style(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = {'id': 10}

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/series/create/', {
                'name': '小雅女装种草号',
                'default_aspect_ratio': '1:1',
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], '/commerce/series/10/')
        backend_client.post.assert_called_once_with('/v1/commerce/series', {
            'name': '小雅女装种草号',
            'category': 'clothing',
            'visualStyle': 'realistic',
            'defaultAspectRatio': '1:1',
            'enabledContentTypes': ['talking', 'try_on'],
        })

    def test_commerce_create_defaults_to_douyin_vertical_ratio(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = {'id': 10}

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/series/create/', {'name': '小雅女装种草号'})

        self.assertEqual(response.status_code, 302)
        backend_client.post.assert_called_once_with('/v1/commerce/series', {
            'name': '小雅女装种草号',
            'category': 'clothing',
            'visualStyle': 'realistic',
            'defaultAspectRatio': '9:16',
            'enabledContentTypes': ['talking', 'try_on'],
        })

    def test_commerce_create_template_exposes_ratio_picker_defaulting_to_nine_sixteen(self):
        template_path = Path(settings.BASE_DIR) / 'templates/commerce/series_create.html'
        template = template_path.read_text(encoding='utf-8')

        header_block = template.split('<div class="glass-card', 1)[0]
        self.assertIn('href="/"', header_block)
        self.assertIn('返回首页', header_block)
        self.assertNotIn('返回电商系列', header_block)
        self.assertIn('name="default_aspect_ratio"', template)
        self.assertIn('value="9:16"', template)
        self.assertIn('selected', template)
        self.assertIn('抖音', template)
        self.assertNotIn('默认画幅：3:4', template)

    def test_model_detail_template_does_not_expose_clothing_version_features(self):
        template_path = Path(settings.BASE_DIR) / 'templates/commerce/model_detail.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('商品列表', template)
        self.assertIn('生成口播', template)
        self.assertIn('创建试穿视频', template)
        header_block = template.split('<div class="space-y-2.5">', 1)[0]
        self.assertIn('← 返回模特列表', header_block)
        self.assertIn('href="/commerce/series/{{ series_id }}/"', header_block)
        self.assertNotIn('← 返回电商系列', header_block)
        self.assertNotIn('锁定模特', header_block)
        self.assertNotIn('/commerce/models/{{ model_id }}/confirm/', header_block)
        self.assertNotIn('生成新服装', template)
        self.assertNotIn('服装 Tab', template)

    def test_commerce_product_form_only_collects_name_type_and_uploaded_image(self):
        template_path = Path(settings.BASE_DIR) / 'templates/commerce/model_detail.html'
        template = template_path.read_text(encoding='utf-8')

        form_block = template.split('添加商品</h2>', 1)[1].split('</form>', 1)[0]
        self.assertIn('enctype="multipart/form-data"', form_block)
        self.assertIn('name="name"', form_block)
        self.assertIn('name="product_type"', form_block)
        self.assertIn('type="hidden"', form_block)
        self.assertIn('commerce-product-type-picker', form_block)
        self.assertIn('data-product-type-option', form_block)
        self.assertIn('commerce-product-type-image', form_block)
        self.assertIn('selectCommerceProductType', template)
        self.assertIn('请先选择商品类型', template)
        self.assertIn('data-product-type="其他"', form_block)
        self.assertNotIn('other.webp', form_block)
        self.assertIn('<span>其他</span>', form_block)
        for group in ('上装', '女裙', '女裤', '套装', '外套', '特色女装', '其他'):
            self.assertIn(group, form_block)
        feature_women_block = form_block.split('特色女装', 1)[1].split('</div>\n                        <div>', 1)[0]
        other_group_block = form_block.split('<p class="mb-2 text-xs font-semibold text-white/50">其他</p>', 1)[1]
        self.assertNotIn('data-product-type="其他"', feature_women_block)
        self.assertIn('data-product-type="其他"', other_group_block)
        for product_type in (
                'T恤', '衬衫', '毛衣', '雪纺衫', '马甲', '卫衣',
                '连衣裙', '半身裙', '旗袍',
                '休闲裤', '牛仔裤', '打底裤', '棉裤羽绒裤',
                '时尚套装', '运动套装',
                '短外套', '西装', '风衣', '羽绒服', '毛呢大衣', '皮衣', '皮草', '棉衣棉服',
                '中老年女装', '大码女装', '其他'):
            self.assertIn(f'data-product-type="{product_type}"', form_block)
        self.assertIn('data-commerce-product-image-list', form_block)
        self.assertIn('data-commerce-product-image-row', form_block)
        self.assertIn('data-add-commerce-product-image', form_block)
        self.assertIn('data-remove-commerce-product-image', form_block)
        self.assertIn('name="product_image_usages"', form_block)
        self.assertIn('name="product_image"', form_block)
        self.assertIn('type="file"', form_block)
        self.assertIn('accept="image/jpeg,image/png,image/webp"', form_block)
        self.assertIn('最多 5 张', form_block)
        self.assertIn('commerce-product-upload', form_block)
        self.assertIn('data-commerce-product-upload-input', form_block)
        self.assertIn('data-commerce-product-upload-summary', form_block)
        self.assertIn('data-commerce-product-upload-total', form_block)
        self.assertIn('data-commerce-product-usage-option', form_block)
        self.assertIn('data-commerce-product-image-index', form_block)
        self.assertIn('商品图 1', form_block)
        self.assertIn('data-commerce-product-image-count', form_block)
        self.assertIn('1/5', form_block)
        self.assertIn('选择图片', form_block)
        self.assertIn('继续添加', form_block)
        self.assertIn('支持 JPG、PNG、WEBP', form_block)
        self.assertIn('addCommerceProductImageRow', template)
        self.assertIn('updateCommerceProductImageRows', template)
        self.assertIn("indexLabel.textContent = `商品图 ${index + 1}`;", template)
        self.assertIn("countLabel.textContent = `${index + 1}/5`;", template)
        self.assertIn('selectCommerceProductImageUsage', template)
        self.assertNotIn('renderCommerceProductImageUsageRows', template)
        self.assertIn('type="hidden" name="product_image_usages"', template)
        self.assertNotIn('<select name="product_image_usages"', template)
        self.assertNotIn('bg-slate-950/70', form_block)
        self.assertNotIn('type="radio" name="product_image_usages"', template)
        self.assertLess(form_block.index('name="product_image_usages"'), form_block.index('name="product_image"'))
        for usage in ('主图', '细节图', '上身参考'):
            self.assertIn(usage, template)
        self.assertNotIn('name="product_image" required accept="image/jpeg,image/png,image/webp" class="input-modern w-full"', form_block)
        self.assertNotIn('name="image_url"', form_block)
        self.assertNotIn('name="selling_points"', form_block)
        self.assertNotIn('name="fabric_fit_color_size"', form_block)
        self.assertNotIn('name="target_audience"', form_block)
        self.assertNotIn('name="price_offer"', form_block)
        self.assertNotIn('name="try_on_requirements"', form_block)
        product_card_block = template.split('{% for product in products %}', 1)[1].split('</article>', 1)[0]
        self.assertIn('product.productType', product_card_block)
        self.assertIn('data-commerce-product-list', template)
        self.assertIn('data-commerce-product-category-filters', template)
        self.assertIn('data-commerce-product-subcategory-filters', template)
        self.assertIn('data-commerce-product-filter-tabs', template)
        self.assertIn('data-commerce-product-filter-panel', template)
        self.assertIn('data-commerce-product-filter-summary', template)
        self.assertIn('data-commerce-product-filter-reset', template)
        self.assertIn('当前筛选', template)
        self.assertIn('清空筛选', template)
        self.assertIn('data-commerce-product-filter-empty', template)
        self.assertIn('data-commerce-product-type="{{ product.productType|default:\'其他\' }}"', product_card_block)
        self.assertIn('data-commerce-product-category=""', product_card_block)
        self.assertIn('COMMERCE_PRODUCT_CATEGORY_GROUPS', template)
        self.assertIn("['上装', ['T恤', '衬衫', '毛衣', '雪纺衫', '马甲', '卫衣']]", template)
        self.assertIn("['外套', ['短外套', '西装', '风衣', '羽绒服', '毛呢大衣', '皮衣', '皮草', '棉衣棉服']]", template)
        self.assertIn("['其他', ['其他']]", template)
        self.assertIn('commerceProductCategoryForType', template)
        self.assertIn('updateCommerceProductFilters', template)
        self.assertIn('selectCommerceProductCategoryFilter', template)
        self.assertIn('selectCommerceProductSubcategoryFilter', template)
        self.assertIn('resetCommerceProductFilters', template)
        self.assertIn('renderCommerceProductFilterSummary', template)
        self.assertIn("card.hidden = !visible;", template)
        self.assertIn("button.dataset.commerceProductCategoryCount", template)
        self.assertIn("当前分类下还没有商品", template)
        self.assertIn('data-commerce-product-summary', product_card_block)
        self.assertIn('data-commerce-product-details', product_card_block)
        self.assertIn('hidden', product_card_block.split('data-commerce-product-details', 1)[0][-120:])
        self.assertIn('data-commerce-product-toggle', product_card_block)
        self.assertIn('展开商品', product_card_block)
        self.assertIn('收起商品', product_card_block)
        self.assertIn('selectCommerceProductCardExpanded', template)
        self.assertIn('data-commerce-product-gallery', product_card_block)
        self.assertIn('data-commerce-product-gallery-main', product_card_block)
        self.assertIn('data-commerce-product-gallery-thumb', product_card_block)
        self.assertIn('{{ product.images|length }}/5', product_card_block)
        self.assertIn('{% for image in product.images %}', product_card_block)
        self.assertIn('{{ image.usage|default:"商品图" }}', product_card_block)
        self.assertIn('{{ image.url }}', product_card_block)
        self.assertNotIn('product.sellingPoints', product_card_block)

    def test_commerce_model_detail_renders_all_uploaded_product_images_in_gallery(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()

        def backend_get(endpoint):
            if endpoint == '/v1/commerce/models/66':
                return {
                    'id': 66,
                    'seriesId': 10,
                    'status': 'locked',
                }
            if endpoint == '/v1/commerce/models/66/products':
                return [
                    {
                        'id': 77,
                        'name': '冰丝防晒衬衫',
                        'imageUrl': 'https://oss.example.com/products/main.png',
                        'productType': '衬衫',
                        'images': [
                            {'url': 'https://oss.example.com/products/main.png', 'usage': '主图'},
                            {'url': 'https://oss.example.com/products/detail.png', 'usage': '细节图'},
                        ],
                    },
                    {
                        'id': 78,
                        'name': '羊毛风衣',
                        'imageUrl': 'https://oss.example.com/products/coat.png',
                        'productType': '风衣',
                        'images': [
                            {'url': 'https://oss.example.com/products/coat.png', 'usage': '主图'},
                        ],
                    },
                ]
            if endpoint in (
                '/v1/commerce/products/77/projects?contentType=try_on',
                '/v1/commerce/products/78/projects?contentType=try_on',
            ):
                return []
            if endpoint == '/v1/commerce/series/10/scenes':
                return []
            raise AssertionError(f'unexpected endpoint: {endpoint}')

        backend_client.get.side_effect = backend_get

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/commerce/models/66/')

        self.assertEqual(response.status_code, 200)
        html = response.content.decode('utf-8')
        self.assertIn('data-commerce-product-filters', html)
        self.assertIn('data-commerce-product-category-filters', html)
        self.assertIn('data-commerce-product-subcategory-filters', html)
        self.assertIn('data-commerce-product-type="衬衫"', html)
        self.assertIn('data-commerce-product-type="风衣"', html)
        self.assertIn('data-commerce-product-category=""', html)
        self.assertIn('当前分类下还没有商品', html)
        self.assertIn('data-commerce-product-gallery', html)
        self.assertIn('data-commerce-product-gallery-main', html)
        self.assertIn('data-commerce-product-gallery-thumb', html)
        self.assertIn('data-commerce-product-summary', html)
        self.assertIn('data-commerce-product-details', html)
        self.assertIn('data-commerce-product-toggle', html)
        self.assertIn('展开商品', html)
        self.assertIn('https://oss.example.com/products/main.png', html)
        self.assertIn('https://oss.example.com/products/detail.png', html)
        self.assertIn('2/5', html)
        self.assertIn('主图', html)
        self.assertIn('细节图', html)

    def test_commerce_model_detail_hides_product_image_delete_when_only_one_image_remains(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()

        def backend_get(endpoint):
            if endpoint == '/v1/commerce/models/66':
                return {
                    'id': 66,
                    'seriesId': 10,
                    'status': 'locked',
                }
            if endpoint == '/v1/commerce/models/66/products':
                return [{
                    'id': 77,
                    'name': '冰丝防晒衬衫',
                    'imageUrl': 'https://oss.example.com/products/main.png',
                    'productType': '衬衫',
                    'images': [
                        {'url': 'https://oss.example.com/products/main.png', 'usage': '主图'},
                    ],
                }]
            if endpoint == '/v1/commerce/products/77/projects?contentType=try_on':
                return []
            if endpoint == '/v1/commerce/series/10/scenes':
                return []
            raise AssertionError(f'unexpected endpoint: {endpoint}')

        backend_client.get.side_effect = backend_get

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/commerce/models/66/')

        self.assertEqual(response.status_code, 200)
        html = response.content.decode('utf-8')
        delete_button = html.split('data-commerce-product-gallery-delete', 1)[0].rsplit('<button', 1)[1]
        self.assertIn('hidden', delete_button)

    def test_commerce_product_card_exposes_current_image_replacement(self):
        template_path = Path(settings.BASE_DIR) / 'templates/commerce/model_detail.html'
        template = template_path.read_text(encoding='utf-8')

        product_card_block = template.split('{% for product in products %}', 1)[1].split('</article>', 1)[0]
        self.assertIn('data-commerce-product-replace-form', product_card_block)
        self.assertIn('/commerce/products/{{ product.id }}/images/update/', product_card_block)
        self.assertIn('name="model_id"', product_card_block)
        self.assertIn('name="product_image_urls"', product_card_block)
        self.assertIn('name="product_image_usages"', product_card_block)
        self.assertIn('data-commerce-product-current-replace-input', product_card_block)
        self.assertIn('data-commerce-product-add-input', product_card_block)
        self.assertIn('data-commerce-product-replace-trigger', product_card_block)
        self.assertIn('data-commerce-product-add-trigger', product_card_block)
        self.assertIn('data-commerce-product-gallery-add-row', product_card_block)
        self.assertIn('data-commerce-product-gallery-delete', product_card_block)
        self.assertNotIn('data-commerce-product-thumb-delete', product_card_block)
        self.assertIn('data-commerce-product-gallery-preview', product_card_block)
        self.assertIn('data-commerce-product-thumb-preview', product_card_block)
        self.assertIn('flex flex-wrap gap-1.5', product_card_block)
        self.assertIn('sm:w-48', product_card_block)
        self.assertIn('h-14 w-14 flex-none', product_card_block)
        self.assertNotIn('h-12 w-12', product_card_block)
        self.assertNotIn('overflow-x-auto', product_card_block)
        self.assertNotIn('pb-1" data-commerce-product-gallery-thumbs', product_card_block)
        self.assertIn('data-commerce-product-image-actions', product_card_block)
        self.assertIn('data-commerce-product-replace-usage-input', product_card_block)
        self.assertIn('data-current-usage', product_card_block)
        self.assertIn('data-commerce-product-gallery-prev', product_card_block)
        self.assertIn('data-commerce-product-gallery-next', product_card_block)
        self.assertIn('data-commerce-product-gallery-index', product_card_block)
        self.assertIn('替换当前商品图', product_card_block)
        self.assertIn('删除当前图片', product_card_block)
        image_actions_block = product_card_block.split('data-commerce-product-image-actions', 1)[1].split('</div>', 2)[0]
        self.assertIn('aria-label="替换当前商品图"', image_actions_block)
        self.assertNotIn('data-commerce-product-add-trigger', image_actions_block)
        self.assertIn('whitespace-nowrap', image_actions_block)
        self.assertNotIn('data-commerce-product-delete-trigger', image_actions_block)
        self.assertNotIn('grid grid-cols-3 gap-1.5', product_card_block)
        self.assertIn('updateCommerceProductGalleryActions', template)
        self.assertIn("const deleteButton = gallery?.querySelector('[data-commerce-product-gallery-delete]');", template)
        self.assertIn("deleteButton.hidden = count <= 1;", template)
        self.assertIn('data-commerce-product-replace-modal', template)
        self.assertIn('data-commerce-product-replace-modal-form', template)
        self.assertIn('data-commerce-product-replace-modal-usage', template)
        self.assertIn('data-commerce-product-replace-modal-confirm', template)
        self.assertIn('data-commerce-product-delete-modal', template)
        self.assertIn('data-commerce-product-delete-modal-confirm', template)
        self.assertIn('删除当前图片', template)
        self.assertIn('添加商品图', product_card_block)
        self.assertIn('选择图片用途', template)
        self.assertIn('确定', template)
        self.assertIn('selectCommerceProductGalleryByOffset', template)
        self.assertIn('updateCommerceProductReplacementTarget', template)
        self.assertIn('selectCommerceProductReplacementUsage', template)
        self.assertIn('openCommerceProductReplacementModal', template)
        self.assertIn('closeCommerceProductReplacementModal', template)
        self.assertIn('triggerCommerceProductReplacementUpload', template)
        self.assertIn('openCommerceProductDeleteModal', template)
        self.assertIn('deleteCommerceProductCurrentImage', template)
        self.assertIn('openCommerceProductGalleryPreview', template)
        self.assertIn('data-commerce-product-thumb-select', product_card_block)
        self.assertIn("event.target.closest('[data-commerce-product-thumb-preview]')", template)
        self.assertIn("event.target.closest('[data-commerce-product-gallery-delete]')", template)
        self.assertNotIn("event.target.closest('[data-commerce-product-thumb-delete]')", template)
        self.assertIn('safeCreateIcons();', template)
        self.assertIn("openModal(url)", template)
        self.assertIn('submitCommerceProductImagesForm', template)
        self.assertIn('renderCommerceProductGalleryImages', template)
        self.assertIn('commerceFetchFormJson', template)
        self.assertNotIn("closest('[data-commerce-product-replace-form]')?.submit()", template)
        self.assertNotIn('data-commerce-product-replace-usage-panel', product_card_block)
        self.assertNotIn('data-commerce-product-replace-usage-option', product_card_block)
        self.assertNotIn('data-commerce-product-edit-image-row', product_card_block)
        self.assertNotIn('data-add-commerce-product-edit-image', product_card_block)
        self.assertNotIn('保存商品图', product_card_block)
        self.assertNotIn('createCommerceProductEditImageRow', template)
        self.assertNotIn('updateCommerceProductEditRows', template)
        self.assertNotIn('selectCommerceProductEditImageUsage', template)

    def test_commerce_product_images_update_adds_image_to_existing_group(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        new_image = SimpleUploadedFile('extra.png', b'fake-extra', content_type='image/png')
        backend_client = Mock()
        backend_client.upload.return_value = {'url': 'https://oss.example.com/commerce/products/extra.png'}
        backend_client.post.return_value = {'id': 77, 'modelId': 66}

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/products/77/images/update/', {
                'model_id': '66',
                'product_image_urls': [
                    'https://oss.example.com/commerce/products/main.png',
                    'https://oss.example.com/commerce/products/detail.png',
                    '',
                ],
                'product_image_usages': ['主图', '细节图', '上身参考'],
                'product_image_2': new_image,
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], '/commerce/models/66/')
        backend_client.upload.assert_called_once()
        backend_client.post.assert_called_once_with('/v1/commerce/products/77/images', {
            'images': [
                {'url': 'https://oss.example.com/commerce/products/main.png', 'usage': '主图'},
                {'url': 'https://oss.example.com/commerce/products/detail.png', 'usage': '细节图'},
                {'url': 'https://oss.example.com/commerce/products/extra.png', 'usage': '上身参考'},
            ],
        })

    def test_commerce_product_images_update_deletes_current_image(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = {'id': 77, 'modelId': 66}

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/products/77/images/update/', {
                'model_id': '66',
                'product_image_urls': [
                    'https://oss.example.com/commerce/products/main.png',
                    'https://oss.example.com/commerce/products/worn.png',
                ],
                'product_image_usages': ['主图', '上身参考'],
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], '/commerce/models/66/')
        backend_client.upload.assert_not_called()
        backend_client.post.assert_called_once_with('/v1/commerce/products/77/images', {
            'images': [
                {'url': 'https://oss.example.com/commerce/products/main.png', 'usage': '主图'},
                {'url': 'https://oss.example.com/commerce/products/worn.png', 'usage': '上身参考'},
            ],
        })

    def test_commerce_product_video_forms_collect_content_specific_settings_after_mode_choice(self):
        template_path = Path(settings.BASE_DIR) / 'templates/commerce/model_detail.html'
        template = template_path.read_text(encoding='utf-8')

        product_card_block = template.split('{% for product in products %}', 1)[1].split('</article>', 1)[0]
        self.assertIn('commerce-product-card', product_card_block)
        self.assertIn('commerce-video-mode-choice', product_card_block)
        self.assertIn('data-commerce-video-mode="talking"', product_card_block)
        self.assertIn('disabled', product_card_block)
        self.assertIn('aria-disabled="true"', product_card_block)
        self.assertIn('暂未开放', product_card_block)
        self.assertIn('data-commerce-video-mode="try_on"', product_card_block)
        self.assertIn('data-commerce-try-on-projects', product_card_block)
        self.assertIn('data-commerce-try-on-project-list', product_card_block)
        self.assertIn('data-commerce-try-on-project-count', product_card_block)
        self.assertIn('试穿视频管理', product_card_block)
        self.assertIn('data-commerce-try-on-project-card', product_card_block)
        self.assertIn('data-commerce-try-on-project-id="{{ project.id }}"', product_card_block)
        self.assertIn('data-commerce-try-on-generate-url="/commerce/projects/{{ project.id }}/generation/start/"', product_card_block)
        self.assertIn('data-commerce-try-on-project-ratio', product_card_block)
        self.assertIn('data-commerce-try-on-project-resolution', product_card_block)
        self.assertIn('data-commerce-try-on-project-performance', product_card_block)
        self.assertIn('project.tryOnRequirements', product_card_block)
        self.assertIn('commerceTryOnProjectPerformanceSummary', template)
        self.assertIn('data-commerce-video-empty', product_card_block)
        self.assertIn('data-commerce-video-panel="talking"', product_card_block)
        self.assertIn('data-commerce-video-panel="try_on"', product_card_block)
        self.assertIn('hidden', product_card_block)
        self.assertIn('selectCommerceProductVideoMode', template)
        self.assertNotIn('mt-4 grid grid-cols-2 gap-2', product_card_block)

        talking_form = template.split('value="talking"', 1)[1].split('</form>', 1)[0]
        self.assertIn('name="selling_points"', talking_form)
        self.assertIn('name="target_audience"', talking_form)
        self.assertIn('name="price_offer"', talking_form)
        try_on_form = template.split('value="try_on"', 1)[1].split('</form>', 1)[0]
        self.assertNotIn('name="try_on_requirements"', try_on_form)
        self.assertNotIn('补充要求', try_on_form)
        self.assertIn('视频比例', try_on_form)
        self.assertIn('name="aspect_ratio"', try_on_form)
        self.assertIn('{% for option in try_on_aspect_ratios %}', try_on_form)
        self.assertIn('{% if option.value == try_on_default_aspect_ratio %}selected{% endif %}', try_on_form)
        self.assertIn('name="duration_seconds"', try_on_form)
        self.assertIn('name="resolution"', try_on_form)
        self.assertIn('name="video_model"', try_on_form)
        self.assertIn('data-commerce-try-on-video-model', try_on_form)
        self.assertIn('data-commerce-try-on-resolution', try_on_form)
        self.assertIn('data-commerce-try-on-duration', try_on_form)
        self.assertIn('data-commerce-try-on-credit-cost', try_on_form)
        self.assertIn('data-commerce-try-on-submit-label', try_on_form)
        self.assertIn('data-supported-models=', try_on_form)
        self.assertIn('updateCommerceTryOnResolutionOptions', template)
        self.assertIn('calculateCommerceTryOnCredits', template)
        self.assertIn('updateCommerceTryOnCreditDisplay', template)
        self.assertIn("creditsPerSecond = resolution === '1080p' ? 16 : 15;", template)
        self.assertIn("creditsPerSecond = 67;", template)
        self.assertIn("creditsPerSecond = isVipModel ? 16 : 11;", template)
        self.assertIn("creditsPerSecond = isVipModel ? 27 : 22;", template)
        self.assertIn('{% for duration in try_on_video_durations %}', try_on_form)
        self.assertIn('{% for resolution in try_on_video_resolutions %}', try_on_form)
        self.assertIn('{% for model in try_on_video_models %}', try_on_form)
        self.assertIn('commerce-try-on-shot-options', try_on_form)
        self.assertIn('name="try_on_shots"', try_on_form)
        self.assertIn('最多选 {{ try_on_max_shots }} 个，每个动作约3-5秒', try_on_form)
        self.assertIn('{% for shot in try_on_shots %}', try_on_form)
        self.assertIn('value="{{ shot.value }}"', try_on_form)
        self.assertIn('{{ shot.duration }}', try_on_form)
        self.assertIn('commerce-try-on-performance-style-options', try_on_form)
        self.assertIn('name="try_on_performance_style"', try_on_form)
        self.assertIn('{% for style in try_on_performance_styles %}', try_on_form)
        self.assertIn('commerce-try-on-performance-boost-options', try_on_form)
        self.assertIn('name="try_on_performance_boosts"', try_on_form)
        self.assertIn('{% for boost in try_on_performance_boosts %}', try_on_form)
        self.assertIn('maxTryOnPerformanceBoosts', template)
        self.assertIn("动作表情最多选择 ${maxTryOnPerformanceBoosts} 个", template)
        self.assertIn('maxTryOnShots', template)
        self.assertIn("展示方式最多选择 ${maxTryOnShots} 个", template)
        self.assertIn('commerce-try-on-scene-options', try_on_form)
        self.assertIn('从场景库选择', try_on_form)
        self.assertIn('name="scene_ids"', try_on_form)
        self.assertIn('type="radio" name="scene_ids"', try_on_form)
        self.assertNotIn('type="checkbox" name="scene_ids"', try_on_form)
        self.assertIn('product.availableScenes', try_on_form)
        self.assertIn('try_on_scene_filter_categories', try_on_form)
        self.assertIn('data-commerce-try-on-scene-filter', try_on_form)
        self.assertIn('data-commerce-try-on-scene-card', try_on_form)
        self.assertIn('data-commerce-try-on-scene-option', try_on_form)
        self.assertIn('data-commerce-try-on-scene-group', try_on_form)
        self.assertIn('data-commerce-try-on-scene-page-size="6"', try_on_form)
        self.assertIn('data-commerce-try-on-scene-page-prev', try_on_form)
        self.assertIn('data-commerce-try-on-scene-page-next', try_on_form)
        self.assertIn('data-commerce-try-on-scene-page-status', try_on_form)
        self.assertIn('updateCommerceTryOnScenePicker', template)
        self.assertIn('updateCommerceTryOnSceneSelection', template)
        self.assertIn("sceneCard.dataset.commerceTryOnSceneGroup === activeFilter", template)
        self.assertIn("sceneCard.dataset.commerceTryOnSceneIndex = String(sceneIndex);", template)
        self.assertIn("sceneIndex >= start && sceneIndex < end", template)
        self.assertIn("option.classList.toggle('border-emerald-200/80', selected);", template)
        self.assertIn("option.querySelector('[data-commerce-try-on-scene-selected]')", template)
        self.assertIn('data-commerce-try-on-scene-preview="{{ scene.imageUrl }}"', try_on_form)
        self.assertIn('data-commerce-try-on-scene-preview-button', try_on_form)
        self.assertIn('查看大图', try_on_form)
        self.assertIn('h-24 w-full object-contain', try_on_form)
        self.assertNotIn('block h-auto w-full object-contain', try_on_form)
        self.assertNotIn('h-20 w-full object-cover', try_on_form)
        self.assertIn('生成场景图', try_on_form)
        self.assertNotIn('name="try_on_scenes"', try_on_form)
        self.assertNotIn('name="try_on_custom_scenes"', try_on_form)
        self.assertNotIn('commerce-try-on-first-frame-check', try_on_form)
        self.assertNotIn('data-commerce-first-frame-panel', try_on_form)
        self.assertNotIn('data-commerce-first-frame-button', try_on_form)
        self.assertNotIn('data-commerce-first-frame-prepare-url', try_on_form)
        self.assertNotIn('data-commerce-first-frame-result', try_on_form)
        self.assertNotIn('data-commerce-first-frame-project', try_on_form)
        self.assertNotIn('name="try_on_first_frame_check" value="enabled"', try_on_form)
        self.assertNotIn('type="checkbox" name="try_on_first_frame_check"', try_on_form)
        self.assertNotIn('试穿首帧确认', try_on_form)
        self.assertNotIn('生成首帧确认图', try_on_form)
        self.assertIn('创建试穿视频', try_on_form)
        self.assertNotIn('name="try_on_first_frame_aspect_ratio"', try_on_form)
        self.assertNotIn('name="try_on_first_frame_resolution"', try_on_form)
        self.assertNotIn('先选 1 张场景图，再生成首帧确认图', try_on_form)
        self.assertNotIn('确认商品还原和模特姿态后，再进入视频生成', try_on_form)
        self.assertNotIn('mt-0.5 flex h-8 w-8 shrink-0', try_on_form)
        self.assertNotIn('commerceFirstFrameFetch', template)
        self.assertNotIn('generateCommerceFirstFrame', template)
        self.assertNotIn('pollCommerceFirstFrameProject', template)
        self.assertNotIn('hydrateCommerceFirstFramePanel', template)
        self.assertNotIn('applyCommerceFirstFrameProject', template)
        self.assertNotIn("document.querySelectorAll('[data-commerce-first-frame-panel]').forEach", template)
        self.assertIn("event.target.matches('input[name=\"scene_ids\"]')", template)
        self.assertNotIn("`/commerce/projects/${project.id}/first-frame/start/`", template)
        self.assertNotIn('type="hidden" name="aspect_ratio" value="9:16"', try_on_form)
        self.assertNotIn('name="try_on_first_frame_aspect_ratio"', try_on_form)
        self.assertNotIn('name="try_on_first_frame_resolution"', try_on_form)
        self.assertNotIn('确认颜色对不对、版型对不对、图案有没有丢、衣长袖长是否合理、模特身体有没有变形', try_on_form)
        self.assertNotIn('name="selling_points"', try_on_form)
        self.assertNotIn('name="price_offer"', try_on_form)
        self.assertIn('submitCommerceTryOnProjectForm', template)
        self.assertIn('setCommerceTryOnSubmitState', template)
        self.assertIn("form.dataset.commerceTryOnSubmitting === 'true'", template)
        self.assertIn("form.dataset.commerceTryOnSubmitting = 'true'", template)
        self.assertIn("form.dataset.commerceTryOnSubmitting = 'false'", template)
        self.assertIn('试穿视频创建中...', template)
        self.assertIn('renderCommerceTryOnProject', template)
        self.assertIn('pollCommerceTryOnProject', template)
        self.assertIn('startCommerceTryOnProjectGeneration', template)
        self.assertIn('commerceTryOnProjectEffectiveStatus', template)
        self.assertIn('commerceTryOnShotVideoUrl', template)
        self.assertIn('updateCommerceTryOnProjectCount', template)
        self.assertIn('openCommerceTryOnProjectVideo', template)
        self.assertIn('data-commerce-try-on-project-open', template)
        self.assertIn('data-commerce-try-on-project-cover-button', template)
        self.assertIn('data-commerce-try-on-project-cover', template)
        self.assertIn('data-lucide="play"', template)
        self.assertIn('data-commerce-try-on-video-download', template)
        self.assertIn("downloadLink.href = videoUrl;", template)
        self.assertNotIn('data-commerce-try-on-project-video', template)
        self.assertNotIn('aria-label="打开试穿视频"', template)
        self.assertNotIn('flex h-16 w-24 flex-none items-center justify-center overflow-hidden rounded-md', template)
        self.assertIn('commerceFetchJson(form.action', template)
        self.assertIn('/generation/start/', template)
        self.assertIn('data-commerce-try-on-project-retry', template)

    def test_commerce_model_detail_try_on_scene_picker_uses_fixed_tabs_and_other_group(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()

        def backend_get(endpoint):
            if endpoint == '/v1/commerce/models/66':
                return {
                    'id': 66,
                    'seriesId': 10,
                    'status': 'locked',
                }
            if endpoint == '/v1/commerce/models/66/products':
                return [{
                    'id': 77,
                    'name': '冰丝防晒衬衫',
                    'imageUrl': 'https://oss.example.com/products/shirt.png',
                    'productType': '衬衫',
                    'tryOnProjects': [],
                }]
            if endpoint == '/v1/commerce/products/77/projects?contentType=try_on':
                return []
            if endpoint == '/v1/commerce/series/10/scenes':
                return [
                    {
                        'id': 701,
                        'name': '直播间背景 1',
                        'sceneType': '直播间背景',
                        'imageUrl': 'https://oss.example.com/scenes/live-1.png',
                        'status': 'completed',
                        'aspectRatio': '9:16',
                    },
                    {
                        'id': 702,
                        'name': '教师 1',
                        'sceneType': '自定义',
                        'imageUrl': 'https://oss.example.com/scenes/teacher-1.png',
                        'status': 'completed',
                        'aspectRatio': '3:4',
                    },
                ]
            raise AssertionError(f'unexpected endpoint: {endpoint}')

        backend_client.get.side_effect = backend_get

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/commerce/models/66/')

        self.assertEqual(response.status_code, 200)
        html = response.content.decode('utf-8')
        for category in ('全部', '品牌场景', '直播间背景', '街拍场景', '棚拍场景', '商场试衣间', '室内全身镜', '其他场景'):
            self.assertIn(f'data-commerce-try-on-scene-filter="{category}"', html)
        self.assertNotIn('data-commerce-try-on-scene-filter="教师 1"', html)
        self.assertIn('data-commerce-try-on-scene-category="直播间背景"', html)
        self.assertIn('data-commerce-try-on-scene-category="教师 1"', html)
        self.assertIn('data-commerce-try-on-scene-group="其他场景"', html)
        self.assertIn('type="radio" name="scene_ids" value="701"', html)
        self.assertIn('type="radio" name="scene_ids" value="702"', html)
        self.assertIn('data-commerce-try-on-scene-option', html)
        self.assertIn('data-commerce-try-on-scene-selected', html)
        self.assertIn('已选择', html)
        self.assertIn('data-commerce-try-on-scene-page-size="6"', html)
        self.assertIn('data-commerce-try-on-scene-page-prev', html)
        self.assertIn('data-commerce-try-on-scene-page-next', html)
        self.assertIn('data-commerce-try-on-scene-page-status', html)
        self.assertIn('data-commerce-try-on-scene-preview="https://oss.example.com/scenes/live-1.png"', html)
        self.assertIn('data-commerce-try-on-scene-preview="https://oss.example.com/scenes/teacher-1.png"', html)
        self.assertIn('data-commerce-try-on-scene-preview-button', html)
        self.assertIn('查看大图', html)
        self.assertIn('h-24 w-full object-contain', html)
        self.assertNotIn('block h-auto w-full object-contain', html)
        self.assertNotIn('h-20 w-full object-cover', html)
        for shot in COMMERCE_TRY_ON_SHOTS:
            self.assertIn(f'value="{shot["value"]}"', html)
            self.assertIn(shot['value'], html)
            self.assertIn(shot['duration'], html)
        for duration in COMMERCE_TRY_ON_VIDEO_DURATIONS:
            self.assertIn(f'value="{duration}"', html)
            self.assertIn(f'{duration}秒', html)
        for option in COMMERCE_ASPECT_RATIO_OPTIONS:
            self.assertIn(f'value="{option["value"]}"', html)
            self.assertIn(option['label'], html)
        for resolution in COMMERCE_TRY_ON_VIDEO_RESOLUTIONS:
            self.assertIn(f'value="{resolution["value"]}"', html)
            self.assertIn(resolution['label'], html)
        self.assertIn('data-supported-models="seedance-2.0,kling-v3-omni"', html)
        self.assertIn('data-supported-models="seedance-2.0,seedance-2.0-fast,kling-v3-omni"', html)
        self.assertIn('value="480p" selected data-supported-models="seedance-2.0,seedance-2.0-fast"', html)
        self.assertEqual(
            COMMERCE_TRY_ON_SUPPORTED_RESOLUTIONS_BY_MODEL['seedance-2.0-fast'],
            ('480p', '720p'),
        )
        self.assertEqual(
            COMMERCE_TRY_ON_SUPPORTED_RESOLUTIONS_BY_MODEL['kling-v3-omni'],
            ('720p', '1080p'),
        )
        for model in COMMERCE_TRY_ON_VIDEO_MODELS:
            self.assertIn(f'value="{model["value"]}"', html)
            self.assertIn(model['label'], html)
        self.assertIn('value="seedance-2.0" selected', html)
        self.assertIn('value="9:16" selected', html)
        self.assertIn('value="480p" selected', html)
        self.assertIn('value="8" selected', html)
        self.assertIn('data-commerce-try-on-project-list', html)
        self.assertIn('暂无试穿视频', html)

    def test_commerce_model_detail_ignores_legacy_latest_first_frame_project(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()

        def backend_get(endpoint):
            if endpoint == '/v1/commerce/models/66':
                return {
                    'id': 66,
                    'seriesId': 10,
                    'status': 'locked',
                }
            if endpoint == '/v1/commerce/models/66/products':
                return [{
                    'id': 77,
                    'name': '冰丝防晒衬衫',
                    'imageUrl': 'https://oss.example.com/products/shirt.png',
                    'productType': '衬衫',
                    'latestTryOnFirstFrameProject': {
                        'id': 92,
                        'tryOnFirstFrameStatus': 'generating',
                    },
                }]
            if endpoint == '/v1/commerce/products/77/projects?contentType=try_on':
                return []
            if endpoint == '/v1/commerce/series/10/scenes':
                return []
            raise AssertionError(f'unexpected endpoint: {endpoint}')

        backend_client.get.side_effect = backend_get

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/commerce/models/66/')

        self.assertEqual(response.status_code, 200)
        html = response.content.decode('utf-8')
        self.assertNotIn('data-commerce-first-frame-project', html)
        self.assertNotIn('&quot;tryOnFirstFrameStatus&quot;: &quot;generating&quot;', html)
        self.assertNotIn('hydrateCommerceFirstFramePanel', html)

    def test_commerce_model_detail_renders_product_try_on_project_management(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()

        def backend_get(endpoint):
            if endpoint == '/v1/commerce/models/66':
                return {
                    'id': 66,
                    'seriesId': 10,
                    'status': 'locked',
                }
            if endpoint == '/v1/commerce/models/66/products':
                return [{
                    'id': 77,
                    'name': '冰丝防晒衬衫',
                    'imageUrl': 'https://oss.example.com/products/shirt.png',
                    'productType': '衬衫',
                    'tryOnProjects': [
                        {
                            'id': 92,
                            'contentType': 'try_on',
                            'aspectRatio': '9:16',
                            'status': 'generating',
                            'tryOnRequirements': '展示方式：正面展示；表演风格：自信大片；动作表情：快走急停回看、回头挑眉',
                            'sceneImageUrls': ['https://oss.example.com/scenes/live-1.png'],
                            'shots': [{
                                'id': 501,
                                'title': '正面展示',
                                'generationStatus': 'generating',
                                'durationSeconds': 8,
                                'resolution': '480p',
                                'videoModel': 'seedance-2.0',
                            }],
                        },
                        {
                            'id': 91,
                            'contentType': 'try_on',
                            'aspectRatio': '3:4',
                            'status': 'completed',
                            'shots': [{
                                'id': 500,
                                'title': '正面展示',
                                'generationStatus': 'completed',
                                'videoUrl': 'https://oss.example.com/videos/try-on.mp4',
                            }],
                        },
                        {
                            'id': 90,
                            'contentType': 'try_on',
                            'aspectRatio': '9:16',
                            'status': 'generating',
                            'shots': [{
                                'id': 499,
                                'title': '走动展示',
                                'generationStatus': 'completed',
                                'videoUrl': 'https://oss.example.com/videos/stale-status.mp4',
                                'resolution': '480p',
                            }],
                        },
                        {
                            'id': 89,
                            'contentType': 'try_on',
                            'aspectRatio': '9:16',
                            'status': 'partial_completed',
                            'errorMessage': '走动展示生成失败：输入或生成内容可能涉及版权限制，请更换素材或调整描述后重试。',
                            'shots': [
                                {
                                    'id': 497,
                                    'title': '转身展示',
                                    'generationStatus': 'completed',
                                    'videoUrl': 'https://oss.example.com/videos/partial.mp4',
                                    'resolution': '480p',
                                },
                                {
                                    'id': 498,
                                    'title': '走动展示',
                                    'generationStatus': 'failed',
                                    'errorMessage': '输入或生成内容可能涉及版权限制，请更换素材或调整描述后重试。',
                                    'resolution': '480p',
                                },
                            ],
                        },
                    ],
                }]
            if endpoint == '/v1/commerce/products/77/projects?contentType=try_on':
                return [
                    {
                        'id': 92,
                        'contentType': 'try_on',
                        'aspectRatio': '9:16',
                        'status': 'generating',
                        'tryOnRequirements': '展示方式：正面展示；表演风格：自信大片；动作表情：快走急停回看、回头挑眉',
                        'sceneImageUrls': ['https://oss.example.com/scenes/live-1.png'],
                        'shots': [{
                            'id': 501,
                            'title': '正面展示',
                            'generationStatus': 'generating',
                            'durationSeconds': 8,
                            'resolution': '480p',
                            'videoModel': 'seedance-2.0',
                        }],
                    },
                    {
                        'id': 91,
                        'contentType': 'try_on',
                        'aspectRatio': '3:4',
                        'status': 'completed',
                        'shots': [{
                            'id': 500,
                            'title': '正面展示',
                            'generationStatus': 'completed',
                            'videoUrl': 'https://oss.example.com/videos/try-on.mp4',
                        }],
                    },
                    {
                        'id': 90,
                        'contentType': 'try_on',
                        'aspectRatio': '9:16',
                        'status': 'generating',
                        'shots': [{
                            'id': 499,
                            'title': '走动展示',
                            'generationStatus': 'completed',
                            'videoUrl': 'https://oss.example.com/videos/stale-status.mp4',
                            'resolution': '480p',
                        }],
                    },
                    {
                        'id': 89,
                        'contentType': 'try_on',
                        'aspectRatio': '9:16',
                        'status': 'partial_completed',
                        'errorMessage': '走动展示生成失败：输入或生成内容可能涉及版权限制，请更换素材或调整描述后重试。',
                        'shots': [
                            {
                                'id': 497,
                                'title': '转身展示',
                                'generationStatus': 'completed',
                                'videoUrl': 'https://oss.example.com/videos/partial.mp4',
                                'resolution': '480p',
                            },
                            {
                                'id': 498,
                                'title': '走动展示',
                                'generationStatus': 'failed',
                                'errorMessage': '输入或生成内容可能涉及版权限制，请更换素材或调整描述后重试。',
                                'resolution': '480p',
                            },
                        ],
                    },
                ]
            if endpoint == '/v1/commerce/series/10/scenes':
                return []
            raise AssertionError(f'unexpected endpoint: {endpoint}')

        backend_client.get.side_effect = backend_get

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/commerce/models/66/')

        self.assertEqual(response.status_code, 200)
        html = response.content.decode('utf-8')
        self.assertIn('试穿视频管理', html)
        self.assertIn('data-commerce-try-on-project-id="92"', html)
        self.assertIn('data-commerce-try-on-project-status="generating"', html)
        self.assertIn('data-commerce-try-on-project-performance', html)
        self.assertIn('表演风格：自信大片', html)
        self.assertIn('动作/表情：快走急停回看、回头挑眉', html)
        self.assertIn('生成中', html)
        self.assertIn('data-commerce-try-on-project-id="91"', html)
        self.assertIn('https://oss.example.com/videos/try-on.mp4', html)
        self.assertIn('data-commerce-try-on-project-id="90"', html)
        self.assertIn('https://oss.example.com/videos/stale-status.mp4', html)
        self.assertIn('data-commerce-try-on-project-id="89"', html)
        self.assertIn('data-commerce-try-on-project-status="partial_completed"', html)
        self.assertIn('部分完成', html)
        self.assertIn('https://oss.example.com/videos/partial.mp4', html)
        self.assertIn('走动展示生成失败：输入或生成内容可能涉及版权限制，请更换素材或调整描述后重试。', html)
        self.assertIn('data-commerce-try-on-project-status="completed"', html)
        self.assertIn('data-commerce-try-on-project-count', html)
        self.assertIn('4 条', html)
        self.assertIn('data-commerce-try-on-project-open', html)
        self.assertIn('data-commerce-try-on-project-cover-button', html)
        self.assertIn('data-commerce-try-on-project-cover', html)
        self.assertIn('播放试穿视频', html)
        self.assertNotIn('controls playsinline data-commerce-try-on-project-video', html)
        self.assertNotIn('aria-label="打开试穿视频"', html)
        self.assertIn('重新生成', html)

    def test_commerce_model_detail_fetches_try_on_projects_when_product_payload_is_missing_them(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()

        def backend_get(endpoint):
            if endpoint == '/v1/commerce/models/66':
                return {
                    'id': 66,
                    'seriesId': 10,
                    'status': 'locked',
                }
            if endpoint == '/v1/commerce/models/66/products':
                return [{
                    'id': 77,
                    'name': '冰丝防晒衬衫',
                    'imageUrl': 'https://oss.example.com/products/shirt.png',
                    'productType': '衬衫',
                }]
            if endpoint == '/v1/commerce/products/77/projects?contentType=try_on':
                return [{
                    'id': 92,
                    'contentType': 'try_on',
                    'aspectRatio': '9:16',
                    'status': 'generating',
                    'sceneImageUrls': ['https://oss.example.com/scenes/live-1.png'],
                    'shots': [{
                        'id': 501,
                        'title': '全身站立',
                        'generationStatus': 'generating',
                        'durationSeconds': 8,
                        'resolution': '480p',
                        'videoModel': 'seedance-2.0',
                    }],
                }]
            if endpoint == '/v1/commerce/series/10/scenes':
                return []
            raise AssertionError(f'unexpected endpoint: {endpoint}')

        backend_client.get.side_effect = backend_get

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/commerce/models/66/')

        self.assertEqual(response.status_code, 200)
        html = response.content.decode('utf-8')
        self.assertIn('data-commerce-try-on-project-id="92"', html)
        self.assertIn('data-commerce-try-on-project-status="generating"', html)
        self.assertIn('生成中', html)
        self.assertIn('1 条', html)

    def test_commerce_model_detail_fetches_product_try_on_projects_concurrently(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        active_project_requests = 0
        max_active_project_requests = 0
        lock = threading.Lock()

        def backend_get(endpoint):
            nonlocal active_project_requests, max_active_project_requests
            if endpoint == '/v1/commerce/models/66':
                return {
                    'id': 66,
                    'seriesId': 10,
                    'status': 'locked',
                }
            if endpoint == '/v1/commerce/models/66/products':
                return [
                    {
                        'id': 77,
                        'name': '冰丝防晒衬衫',
                        'imageUrl': 'https://oss.example.com/products/shirt.png',
                        'productType': '衬衫',
                    },
                    {
                        'id': 78,
                        'name': '羊毛风衣',
                        'imageUrl': 'https://oss.example.com/products/coat.png',
                        'productType': '风衣',
                    },
                    {
                        'id': 79,
                        'name': '针织连衣裙',
                        'imageUrl': 'https://oss.example.com/products/dress.png',
                        'productType': '连衣裙',
                    },
                ]
            if endpoint.startswith('/v1/commerce/products/') and endpoint.endswith('/projects?contentType=try_on'):
                product_id = endpoint.split('/')[4]
                with lock:
                    active_project_requests += 1
                    max_active_project_requests = max(max_active_project_requests, active_project_requests)
                time.sleep(0.05)
                with lock:
                    active_project_requests -= 1
                return [{
                    'id': int(product_id),
                    'contentType': 'try_on',
                    'status': 'completed',
                    'shots': [],
                }]
            if endpoint == '/v1/commerce/series/10/scenes':
                return []
            raise AssertionError(f'unexpected endpoint: {endpoint}')

        backend_client.get.side_effect = backend_get

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/commerce/models/66/')

        self.assertEqual(response.status_code, 200)
        self.assertGreaterEqual(max_active_project_requests, 2)
        html = response.content.decode('utf-8')
        self.assertIn('data-commerce-try-on-project-id="77"', html)
        self.assertIn('data-commerce-try-on-project-id="78"', html)
        self.assertIn('data-commerce-try-on-project-id="79"', html)

    def test_commerce_product_create_uploads_images_before_creating_product(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        main_image = SimpleUploadedFile('main.png', b'fake-main', content_type='image/png')
        detail_image = SimpleUploadedFile('detail.png', b'fake-detail', content_type='image/png')
        worn_image = SimpleUploadedFile('worn.png', b'fake-worn', content_type='image/png')
        backend_client = Mock()
        backend_client.upload.side_effect = [
            {'url': 'https://oss.example.com/commerce/products/main.png'},
            {'url': 'https://oss.example.com/commerce/products/detail.png'},
            {'url': 'https://oss.example.com/commerce/products/worn.png'},
        ]

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/models/66/products/create/', {
                'name': '冰丝防晒衬衫',
                'product_type': '上衣',
                'product_image': [main_image, detail_image, worn_image],
                'product_image_usages': ['主图', '细节图', '上身参考'],
                'selling_points': '不应该提前提交',
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], '/commerce/models/66/')
        self.assertEqual(backend_client.upload.call_count, 3)
        self.assertEqual(backend_client.upload.call_args_list[0].args[0], '/v1/commerce/products/upload-image')
        backend_client.post.assert_called_once_with('/v1/commerce/models/66/products', {
            'name': '冰丝防晒衬衫',
            'imageUrl': 'https://oss.example.com/commerce/products/main.png',
            'productType': '上衣',
            'images': [
                {'url': 'https://oss.example.com/commerce/products/main.png', 'usage': '主图'},
                {'url': 'https://oss.example.com/commerce/products/detail.png', 'usage': '细节图'},
                {'url': 'https://oss.example.com/commerce/products/worn.png', 'usage': '上身参考'},
            ],
        })

    def test_commerce_product_create_rejects_more_than_five_images(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        images = [
            SimpleUploadedFile(f'product-{index}.png', b'fake-png', content_type='image/png')
            for index in range(6)
        ]
        backend_client = Mock()

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/models/66/products/create/', {
                'name': '冰丝防晒衬衫',
                'product_type': '上衣',
                'product_image': images,
                'product_image_usages': ['细节图'] * 6,
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], '/commerce/models/66/')
        backend_client.upload.assert_not_called()
        backend_client.post.assert_not_called()

    def test_commerce_product_images_update_uploads_replacements_and_keeps_existing_urls(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        replacement_image = SimpleUploadedFile('new-detail.png', b'fake-new-detail', content_type='image/png')
        backend_client = Mock()
        backend_client.upload.return_value = {'url': 'https://oss.example.com/commerce/products/new-detail.png'}
        backend_client.post.return_value = {'id': 77, 'modelId': 66}

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/products/77/images/update/', {
                'model_id': '66',
                'product_image_urls': [
                    'https://oss.example.com/commerce/products/main.png',
                    '',
                    'https://oss.example.com/commerce/products/worn.png',
                ],
                'product_image_usages': ['主图', '细节图', '上身参考'],
                'product_image_1': replacement_image,
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], '/commerce/models/66/')
        backend_client.upload.assert_called_once()
        self.assertEqual(backend_client.upload.call_args.args[0], '/v1/commerce/products/upload-image')
        backend_client.post.assert_called_once_with('/v1/commerce/products/77/images', {
            'images': [
                {'url': 'https://oss.example.com/commerce/products/main.png', 'usage': '主图'},
                {'url': 'https://oss.example.com/commerce/products/new-detail.png', 'usage': '细节图'},
                {'url': 'https://oss.example.com/commerce/products/worn.png', 'usage': '上身参考'},
            ],
        })

    def test_commerce_product_images_update_ajax_returns_updated_images_without_redirect(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        replacement_image = SimpleUploadedFile('new-detail.png', b'fake-new-detail', content_type='image/png')
        backend_client = Mock()
        backend_client.upload.return_value = {'url': 'https://oss.example.com/commerce/products/new-detail.png'}
        backend_client.post.return_value = {'id': 77, 'modelId': 66}

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post(
                '/commerce/products/77/images/update/',
                {
                    'model_id': '66',
                    'product_image_urls': [
                        'https://oss.example.com/commerce/products/main.png',
                        '',
                    ],
                    'product_image_usages': ['主图', '细节图'],
                    'product_image_1': replacement_image,
                },
                HTTP_X_REQUESTED_WITH='XMLHttpRequest',
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {
            'code': 200,
            'data': {
                'id': 77,
                'modelId': 66,
                'images': [
                    {'url': 'https://oss.example.com/commerce/products/main.png', 'usage': '主图'},
                    {'url': 'https://oss.example.com/commerce/products/new-detail.png', 'usage': '细节图'},
                ],
            },
        })
        backend_client.post.assert_called_once_with('/v1/commerce/products/77/images', {
            'images': [
                {'url': 'https://oss.example.com/commerce/products/main.png', 'usage': '主图'},
                {'url': 'https://oss.example.com/commerce/products/new-detail.png', 'usage': '细节图'},
            ],
        })

    def test_commerce_product_images_update_rejects_more_than_five_images(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/products/77/images/update/', {
                'model_id': '66',
                'product_image_urls': [
                    f'https://oss.example.com/commerce/products/{index}.png'
                    for index in range(6)
                ],
                'product_image_usages': ['细节图'] * 6,
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], '/commerce/models/66/')
        backend_client.upload.assert_not_called()
        backend_client.post.assert_not_called()

    def test_commerce_project_create_forwards_content_specific_settings(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.post.side_effect = [
            {'id': 88},
            {'id': 89},
        ]

        with patch('apps.series.views.get_client', return_value=backend_client):
            talking_response = self.client.post('/commerce/products/77/projects/create/', {
                'content_type': 'talking',
                'aspect_ratio': '9:16',
                'selling_points': '凉感显瘦',
                'target_audience': '通勤人群',
                'price_offer': '限时优惠',
            })
            try_on_response = self.client.post('/commerce/products/77/projects/create/', {
                'content_type': 'try_on',
                'aspect_ratio': '9:16',
                'try_on_shots': ['全身站立', '拿包通勤', '整理衣摆', '搭配展示', '戴帽展示'],
                'try_on_performance_style': '自信大片',
                'try_on_performance_boosts': ['快走急停回看', '回头挑眉', '自信微笑', '爽朗大笑'],
                'scene_ids': ['701', '702'],
                'duration_seconds': '12',
                'resolution': '1080p',
                'video_model': 'seedance-2.0',
                'selling_points': '试穿不该提交卖点',
            })

        self.assertEqual(talking_response['Location'], '/commerce/projects/88/')
        self.assertEqual(try_on_response.status_code, 200)
        self.assertEqual(try_on_response.json()['data']['id'], 89)
        self.assertEqual(backend_client.post.call_args_list[0].args[1], {
            'contentType': 'talking',
            'aspectRatio': '9:16',
            'sellingPoints': '凉感显瘦',
            'targetAudience': '通勤人群',
            'priceOffer': '限时优惠',
        })
        self.assertEqual(backend_client.post.call_args_list[1].args[1], {
            'contentType': 'try_on',
            'aspectRatio': '9:16',
            'tryOnRequirements': '展示方式：全身站立、拿包通勤、整理衣摆、搭配展示；表演风格：自信大片；动作表情：快走急停回看、回头挑眉、自信微笑',
            'sceneIds': [701],
            'durationSeconds': 12,
            'resolution': '1080p',
            'videoModel': 'seedance-2.0',
        })
        self.assertEqual(COMMERCE_TRY_ON_MAX_SHOTS, 4)

    def test_commerce_project_create_falls_back_when_model_resolution_is_unsupported(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.post.side_effect = [
            {'id': 90},
            {'id': 91},
        ]

        with patch('apps.series.views.get_client', return_value=backend_client):
            seedance_fast_response = self.client.post('/commerce/products/77/projects/create/', {
                'content_type': 'try_on',
                'aspect_ratio': '9:16',
                'resolution': '1080p',
                'video_model': 'seedance-2.0-fast',
            })
            kling_response = self.client.post('/commerce/products/77/projects/create/', {
                'content_type': 'try_on',
                'aspect_ratio': '9:16',
                'resolution': '480p',
                'video_model': 'kling-v3-omni',
            })

        self.assertEqual(seedance_fast_response.status_code, 200)
        self.assertEqual(seedance_fast_response.json()['data']['id'], 90)
        self.assertEqual(kling_response.status_code, 200)
        self.assertEqual(kling_response.json()['data']['id'], 91)
        self.assertEqual(backend_client.post.call_args_list[0].args[1]['videoModel'], 'seedance-2.0-fast')
        self.assertEqual(backend_client.post.call_args_list[0].args[1]['resolution'], '480p')
        self.assertEqual(backend_client.post.call_args_list[1].args[1]['videoModel'], 'kling-v3-omni')
        self.assertEqual(backend_client.post.call_args_list[1].args[1]['resolution'], '720p')

    def test_commerce_project_create_ignores_retired_first_frame_fields(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = {'id': 91}

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/products/77/projects/create/', {
                'content_type': 'try_on',
                'aspect_ratio': '9:16',
                'try_on_first_frame_check': 'enabled',
                'try_on_first_frame_aspect_ratio': '2:1',
                'try_on_first_frame_resolution': '8K',
                'duration_seconds': '99',
                'resolution': '8k',
                'video_model': 'unknown-model',
                'scene_ids': ['701'],
            })

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['data']['id'], 91)
        self.assertEqual(backend_client.post.call_args_list[0].args[1], {
            'contentType': 'try_on',
            'aspectRatio': '9:16',
            'sceneIds': [701],
            'durationSeconds': 8,
            'resolution': '480p',
            'videoModel': 'seedance-2.0',
        })

    def test_commerce_project_status_proxies_backend(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.get.return_value = {
            'id': 92,
            'status': 'draft',
        }

        with patch('apps.series.views.get_client', return_value=backend_client):
            status_response = self.client.get('/commerce/projects/92/status/')

        self.assertEqual(status_response.status_code, 200)
        self.assertEqual(status_response.json()['data']['status'], 'draft')
        backend_client.post.assert_not_called()
        self.assertEqual(backend_client.get.call_args.args[0], '/v1/commerce/projects/92')

    def test_commerce_project_create_ignores_retired_freeform_try_on_requirements(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = {'id': 90}

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/products/77/projects/create/', {
                'content_type': 'try_on',
                'aspect_ratio': '9:16',
                'try_on_requirements': '通勤街景、室内全身镜、户外自然光',
                'scene_ids': ['701'],
                'duration_seconds': '6',
                'resolution': '480p',
                'video_model': 'kling-v3-omni',
            })

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['data']['id'], 90)
        self.assertEqual(backend_client.post.call_args_list[0].args[1], {
            'contentType': 'try_on',
            'aspectRatio': '9:16',
            'sceneIds': [701],
            'durationSeconds': 6,
            'resolution': '720p',
            'videoModel': 'kling-v3-omni',
        })

    def test_commerce_project_detail_template_does_not_render_try_on_first_frame_preview(self):
        template_path = Path(settings.BASE_DIR) / 'templates/commerce/project_detail.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertNotIn('project.tryOnFirstFrameEnabled', template)
        self.assertNotIn('试穿首帧确认', template)
        self.assertNotIn('project.tryOnFirstFrameAspectRatio', template)
        self.assertNotIn('project.tryOnFirstFrameResolution', template)
        self.assertNotIn('project.tryOnFirstFrameStatus', template)
        self.assertNotIn('project.tryOnFirstFrameUrl', template)
        self.assertNotIn('试穿首帧确认图', template)
        self.assertNotIn('project.tryOnFirstFrameError', template)
        self.assertNotIn('project.tryOnFirstFrameConfirmed', template)
        self.assertNotIn('/commerce/projects/{{ project.id }}/confirm-first-frame/', template)
        self.assertNotIn('确认首帧', template)
        self.assertNotIn('首帧确认后再生成视频', template)
        self.assertNotIn('确认颜色对不对、版型对不对、图案有没有丢、衣长袖长是否合理、模特身体有没有变形', template)

    def test_commerce_series_list_template_links_back_to_home(self):
        template_path = Path(settings.BASE_DIR) / 'templates/commerce/series_list.html'
        template = template_path.read_text(encoding='utf-8')

        header_block = template.split('电商系列</h1>', 1)[1].split('<div id="commerceSeriesList"', 1)[0]
        self.assertIn('href="/"', header_block)
        self.assertIn('data-lucide="arrow-left"', header_block)
        self.assertIn('返回首页', header_block)
        self.assertIn('创建电商系列', header_block)

    def test_commerce_series_detail_template_lists_models_as_rows_with_full_image_preview(self):
        template_path = Path(settings.BASE_DIR) / 'templates/commerce/series_detail.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('commerce-model-list', template)
        self.assertIn('commerce-model-row', template)
        self.assertIn('commerce-model-preview', template)
        self.assertIn('commerce-scene-library-entry', template)
        self.assertIn('/commerce/series/{{ commerce_series.id }}/scenes/', template)
        self.assertIn('场景库', template)
        self.assertIn('生成和维护试穿场景图', template)
        self.assertIn('commerce-model-library-entry', template)
        self.assertIn('/commerce/series/{{ commerce_series.id }}/model-library/', template)
        self.assertIn('模特库', template)
        self.assertIn('沿用已锁定模特到当前系列', template)
        self.assertLess(template.index('commerce-scene-library-entry'), template.index('<h2 class="text-lg font-semibold">创建模特</h2>'))
        self.assertLess(template.index('commerce-scene-library-entry'), template.index('commerce-model-library-entry'))
        self.assertLess(template.index('commerce-model-library-entry'), template.index('<h2 class="text-lg font-semibold">创建模特</h2>'))
        self.assertNotIn('mb-5 flex items-start justify-between gap-4', template.split('<h2 class="mb-4 text-lg font-semibold">模特列表</h2>', 1)[0])
        self.assertIn('object-contain', template)
        self.assertIn('模特名称', template)
        self.assertIn('性别', template)
        self.assertIn('年龄与类型', template)
        self.assertIn('人种风格', template)
        self.assertIn('身材', template)
        self.assertIn('模特图比例', template)
        self.assertIn('模特提示词', template)
        self.assertIn('model.gender', template)
        self.assertIn('model.modelType', template)
        self.assertIn('model.ethnicity', template)
        self.assertIn('model.bodyProfile', template)
        self.assertIn('model.modelAspectRatio', template)
        self.assertIn('创建时间', template)
        self.assertIn('model.createdAtDisplay', template)
        self.assertIn('commerce-model-created-at', template)
        self.assertIn('model.appearancePrompt', template)
        self.assertIn('commerce-model-summary', template)
        self.assertIn('commerce-model-prompt-preview', template)
        self.assertIn('data-commerce-model-prompt-toggle', template)
        self.assertIn('data-commerce-model-prompt-content', template)
        self.assertIn('aria-expanded="false"', template)
        self.assertIn('展开提示词', template)
        self.assertIn('bindCommerceModelPromptToggles', template)
        self.assertIn('promptContent.hidden = expanded;', template)
        self.assertIn('refreshCommerceModelIcons', template)
        self.assertIn('lucide.createIcons()', template)
        self.assertIn('去绑定商品', template)
        self.assertNotIn('去绑定商品（等待上线）', template)
        action_block = template.split('data-commerce-model-action-panel', 1)[1]
        locked_block = action_block.split("{% if model.status == 'locked' %}", 1)[1].split("{% elif model.status == 'pending_review' %}", 1)[0]
        self.assertIn('commerce-model-bind-link', locked_block)
        self.assertIn('href="/commerce/models/{{ model.id }}/"', locked_block)
        self.assertNotIn('commerce-model-bind-disabled', locked_block)
        self.assertNotIn('disabled', locked_block)
        self.assertNotIn('aria-disabled="true"', locked_block)
        self.assertIn('bindCommerceModelBindLinks', template)
        self.assertIn("link.textContent = '加载商品中...';", template)
        self.assertIn("link.setAttribute('aria-disabled', 'true');", template)
        self.assertIn('锁定', template)
        self.assertIn('解锁', template)
        self.assertIn('重新生成（6积分）', template)
        self.assertIn('/commerce/models/{{ model.id }}/confirm/', template)
        self.assertIn('/commerce/models/{{ model.id }}/unlock/', template)
        self.assertIn('/commerce/models/{{ model.id }}/regenerate/', template)
        self.assertIn('{% if model.status == \'locked\' %}', template)
        self.assertIn('{% elif model.status == \'pending_review\' %}', template)
        self.assertIn('commerce-model-unlock-form', template)
        self.assertIn('data-commerce-model-confirm-button', template)
        self.assertIn('bindCommerceModelConfirmForms', template)
        self.assertIn('applyCommerceModelLockedState', template)
        self.assertIn("headers: {'X-Requested-With': 'XMLHttpRequest', 'Accept': 'application/json'}", template)
        self.assertIn("event.preventDefault();", template)
        self.assertIn("button.textContent = '锁定中...';", template)
        self.assertIn("row.dataset.modelStatus = 'locked';", template)
        self.assertIn('模特已锁定', template)
        self.assertIn('去绑定商品', template)
        self.assertNotIn("window.location.href = `/commerce/models/${modelId}/`;", template)
        self.assertIn('sm:grid-cols-2', template)
        self.assertIn('sm:col-span-2', template)
        self.assertNotIn('commerce-model-details', template)
        self.assertNotIn('展开完整信息', template)
        self.assertNotIn('<details', template)
        self.assertNotIn('</details>', template)
        self.assertNotIn('max-h-28 overflow-y-auto', template)
        self.assertIn('bg-[rgba(8,12,28,0.42)]', template)
        self.assertIn('shadow-[inset_0_1px_0_rgba(255,255,255,0.08)]', template)
        self.assertIn('border-t border-white/[0.08]', template)
        self.assertIn('text-white/[0.42]', template)
        self.assertIn('bg-cyan-500', locked_block)
        self.assertIn('hover:bg-cyan-400', locked_block)
        self.assertNotIn('rounded-2xl', template)
        self.assertNotIn('border-l-4 border-l-emerald-300/70', template)
        self.assertNotIn('shadow-[0_16px_42px_rgba(0,0,0,0.22)]', template)
        self.assertNotIn('bg-gradient-to-br from-white/[0.08] via-white/[0.035] to-emerald-300/[0.05]', template)
        self.assertIn('序号', template)
        self.assertIn('序号 {{ forloop.revcounter }}', template)
        self.assertNotIn('序号 {{ forloop.counter }}', template)
        self.assertIn('commerce-model-title', template)
        self.assertIn('commerce-model-actions', template)
        self.assertNotIn('查看详情', template)
        self.assertIn('min-h-[168px]', template)
        self.assertIn('max-h-[248px]', template)
        self.assertNotIn('mb-3 flex items-center justify-between gap-3', template)
        self.assertNotIn('min-h-[118px]', template)
        self.assertNotIn('max-h-[176px]', template)
        self.assertNotIn('min-h-[140px]', template)
        self.assertNotIn('max-h-[220px]', template)
        self.assertIn('待锁定', template)
        self.assertIn('生成中', template)
        self.assertIn('commerce-model-spinner', template)
        self.assertIn('animate-spin', template)
        self.assertIn('pollCommerceModelList', template)
        self.assertIn('setInterval(pollCommerceModelList', template)
        self.assertIn("'.commerce-model-row[data-model-status=\"queued\"], .commerce-model-row[data-model-status=\"generating\"]'", template)
        self.assertIn("freshList.innerHTML", template)
        self.assertIn('生成失败', template)
        self.assertIn('重新生成（6积分）', template)
        self.assertIn('/commerce/models/{{ model.id }}/regenerate/', template)
        self.assertIn('{{ model.errorMessage|default:"模特图生成失败，请重新生成" }}', template)
        self.assertNotIn('data-preview-src="{{ model.baseImageUrl }}"', template)
        self.assertNotIn('commerceModelPreviewModal', template)
        self.assertNotIn('commercePreviewZoomIn', template)
        self.assertNotIn('commercePreviewZoomOut', template)
        self.assertNotIn('commercePreviewImage.style.transform', template)
        self.assertNotIn('cursor-zoom-in', template)
        self.assertNotIn('md:grid-cols-2', template)
        self.assertNotIn('sm:grid-cols-[220px_minmax(0,1fr)]', template)

    def test_commerce_series_detail_shows_generating_placeholder_from_backend_models_without_created_query(self):
        session = self.client.session
        session['token'] = 'token-1'
        session['email'] = 'tester@example.com'
        session.save()
        backend_client = Mock()

        def backend_get(endpoint):
            if endpoint == '/v1/commerce/series/10':
                return {
                    'id': 10,
                    'name': '小雅女装种草号',
                    'defaultAspectRatio': '9:16',
                }
            if endpoint == '/v1/commerce/series/10/models':
                return [{
                    'id': 66,
                    'seriesId': 10,
                    'name': '看看',
                    'genderAge': '男 / 成熟男性 / 中国',
                    'bodyProfile': '标准匀称',
                    'appearancePrompt': '成熟中国男性，五官立体，气质稳重。',
                    'modelAspectRatio': '16:9',
                    'status': 'generating',
                    'baseImageUrl': '',
                }]
            raise AssertionError(f'unexpected endpoint: {endpoint}')

        backend_client.get.side_effect = backend_get

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/commerce/series/10/')

        self.assertEqual(response.status_code, 200)
        html = response.content.decode('utf-8')
        self.assertIn('看看', html)
        self.assertIn('男', html)
        self.assertIn('成熟男性', html)
        self.assertIn('中国', html)
        self.assertIn('commerce-model-spinner', html)
        self.assertIn('animate-spin', html)
        self.assertIn('生成中', html)
        backend_client.post.assert_not_called()

    def test_commerce_series_detail_formats_model_created_time(self):
        enriched = _enrich_commerce_model({
            'genderAge': '女 / 年轻美女 / 中国',
            'createdAt': '2026-05-21T01:23:45',
        })

        self.assertEqual(enriched['createdAtDisplay'], '2026-05-21 01:23')

    def test_commerce_series_detail_renders_model_created_time(self):
        session = self.client.session
        session['token'] = 'token-1'
        session['email'] = 'tester@example.com'
        session.save()
        backend_client = Mock()

        def backend_get(endpoint):
            if endpoint == '/v1/commerce/series/10':
                return {
                    'id': 10,
                    'name': '小雅女装种草号',
                    'defaultAspectRatio': '9:16',
                }
            if endpoint == '/v1/commerce/series/10/models':
                return [{
                    'id': 66,
                    'seriesId': 10,
                    'name': '小雅',
                    'genderAge': '女 / 年轻美女 / 中国',
                    'bodyProfile': '标准匀称',
                    'appearancePrompt': '年轻中国女性，五官清爽，气质亲和。',
                    'modelAspectRatio': '16:9',
                    'status': 'pending_review',
                    'baseImageUrl': 'https://oss.example.com/model.png',
                    'createdAt': '2026-05-21T01:23:45',
                }]
            raise AssertionError(f'unexpected endpoint: {endpoint}')

        backend_client.get.side_effect = backend_get

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/commerce/series/10/')

        self.assertEqual(response.status_code, 200)
        html = response.content.decode('utf-8')
        self.assertIn('创建时间', html)
        self.assertIn('2026-05-21 01:23', html)
        self.assertIn('commerce-model-created-at', html)

    def test_commerce_series_detail_links_to_scene_workspace(self):
        template_path = Path(settings.BASE_DIR) / 'templates/commerce/series_detail.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('/commerce/series/{{ commerce_series.id }}/scenes/', template)
        self.assertIn('场景库', template)

    def test_commerce_model_library_lists_locked_models_and_reuse_actions(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()

        def backend_get(endpoint):
            if endpoint == '/v1/commerce/series/10':
                return {'id': 10, 'name': '夏日女装'}
            if endpoint == '/v1/commerce/series/10/model-library':
                return [
                    {
                        'id': 66,
                        'seriesId': 20,
                        'seriesName': '春季女装',
                        'name': '小雅',
                        'genderAge': '女 / 年轻美女 / 中国',
                        'bodyProfile': '高挑模特',
                        'appearancePrompt': '年轻中国女性，气质自信。',
                        'modelAspectRatio': '16:9',
                        'status': 'locked',
                        'baseImageUrl': 'https://oss.example.com/model.png',
                        'createdAt': '2026-05-21T01:23:45',
                        'inTargetSeries': False,
                    },
                    {
                        'id': 67,
                        'seriesId': 10,
                        'seriesName': '夏日女装',
                        'name': '小夏',
                        'genderAge': '女 / 亲和素人女性 / 中国',
                        'bodyProfile': '标准匀称',
                        'appearancePrompt': '亲和自然。',
                        'modelAspectRatio': '16:9',
                        'status': 'locked',
                        'baseImageUrl': 'https://oss.example.com/current-model.png',
                        'createdAt': '2026-05-22T09:10:11',
                        'inTargetSeries': True,
                    },
                ]
            raise AssertionError(f'unexpected endpoint: {endpoint}')

        backend_client.get.side_effect = backend_get

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/commerce/series/10/model-library/')

        self.assertEqual(response.status_code, 200)
        html = response.content.decode('utf-8')
        self.assertIn('commerce-model-library-list', html)
        self.assertIn('commerce-model-library-series-group', html)
        self.assertIn('commerce-model-library-series-grid', html)
        self.assertIn('commerce-model-library-card', html)
        self.assertIn('小雅', html)
        self.assertIn('来自系列', html)
        self.assertIn('春季女装 · 1 个模特', html)
        self.assertIn('夏日女装 · 1 个模特', html)
        self.assertIn('春季女装', html)
        self.assertIn('年轻美女', html)
        self.assertIn('高挑模特', html)
        self.assertIn('2026-05-21 01:23', html)
        self.assertIn('/commerce/series/10/model-library/reuse/66/', html)
        self.assertIn('沿用到当前系列', html)
        self.assertIn('已在当前系列', html)
        self.assertIn('← 返回模特列表', html)
        self.assertEqual(backend_client.get.call_args_list[0].args[0], '/v1/commerce/series/10')
        self.assertEqual(backend_client.get.call_args_list[1].args[0], '/v1/commerce/series/10/model-library')

    def test_commerce_model_library_groups_context_by_source_series(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()

        def backend_get(endpoint):
            if endpoint == '/v1/commerce/series/10':
                return {'id': 10, 'name': '夏日女装'}
            if endpoint == '/v1/commerce/series/10/model-library':
                return [
                    {'id': 66, 'seriesId': 20, 'seriesName': '春季女装', 'name': '小雅', 'createdAt': '2026-05-21T01:23:45'},
                    {'id': 67, 'seriesId': 20, 'seriesName': '春季女装', 'name': '小青', 'createdAt': '2026-05-22T01:23:45'},
                    {'id': 68, 'seriesId': 10, 'seriesName': '夏日女装', 'name': '小夏', 'createdAt': '2026-05-23T01:23:45'},
                ]
            raise AssertionError(f'unexpected endpoint: {endpoint}')

        backend_client.get.side_effect = backend_get

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/commerce/series/10/model-library/')

        groups = response.context['model_library_groups']
        self.assertEqual([group['seriesName'] for group in groups], ['春季女装', '夏日女装'])
        self.assertEqual([group['count'] for group in groups], [2, 1])
        self.assertEqual([model['name'] for model in groups[0]['models']], ['小雅', '小青'])
        self.assertTrue(groups[1]['inTargetSeries'])

    def test_commerce_model_library_reuse_forwards_to_backend_and_returns_to_series(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = {'id': 88, 'seriesId': 10, 'name': '小雅', 'status': 'locked'}

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/series/10/model-library/reuse/66/')

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], '/commerce/series/10/')
        backend_client.post.assert_called_once_with('/v1/commerce/series/10/models/reuse/66')

    def test_commerce_series_detail_empty_state_offers_model_library_reuse(self):
        template_path = Path(settings.BASE_DIR) / 'templates/commerce/series_detail.html'
        template = template_path.read_text(encoding='utf-8')

        empty_block = template.split('{% empty %}', 1)[1].split('{% endfor %}', 1)[0]
        self.assertIn('也可以从模特库沿用已锁定模特', empty_block)
        self.assertIn('/commerce/series/{{ commerce_series.id }}/model-library/', empty_block)
        self.assertIn('去模特库沿用', empty_block)
        self.assertNotIn('还没有模特，先在右侧创建一个。', empty_block)

    def test_commerce_scene_workspace_lists_libraries_and_batch_generate_form(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()

        def backend_get(endpoint):
            if endpoint == '/v1/commerce/series/10':
                return {'id': 10, 'name': '小雅女装种草号'}
            if endpoint == '/v1/commerce/series/10/scenes':
                return [
                    {
                        'id': 701,
                        'name': '直播间背景',
                        'sceneType': '直播间背景',
                        'libraryType': '直播间背景库',
                        'imageUrl': 'https://oss.example.com/commerce/scenes/live.png',
                        'status': 'completed',
                        'resolution': '2K',
                        'aspectRatio': '9:16',
                        'createdAt': '2026-05-21T01:23:45',
                    },
                    {
                        'id': 702,
                        'name': '咖啡店落地窗旁',
                        'sceneType': '自定义',
                        'libraryType': '自定义场景库',
                        'imageUrl': 'https://oss.example.com/commerce/scenes/cafe.png',
                        'status': 'completed',
                        'resolution': '2K',
                        'aspectRatio': '9:16',
                        'createdAt': '2026-05-21T01:24:45',
                    },
                ]
            raise AssertionError(f'unexpected endpoint: {endpoint}')

        backend_client.get.side_effect = backend_get

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/commerce/series/10/scenes/')

        self.assertEqual(response.status_code, 200)
        scene_type_groups = response.context['scene_type_groups']
        self.assertEqual([group['title'] for group in scene_type_groups], ['基础商拍', '出片氛围'])
        self.assertEqual(
            scene_type_groups[0]['scene_types'],
            ('品牌场景', '直播间背景', '棚拍场景', '商场试衣间', '室内全身镜'),
        )
        self.assertIn('咖啡店外', scene_type_groups[1]['scene_types'])
        self.assertIn('艺术馆', scene_type_groups[1]['scene_types'])
        self.assertIn('酒店民宿', scene_type_groups[1]['scene_types'])
        self.assertIn('海边氛围', scene_type_groups[1]['scene_types'])
        grouped_scene_types = [
            scene_type
            for group in scene_type_groups
            for scene_type in group['scene_types']
        ]
        self.assertEqual(grouped_scene_types, list(COMMERCE_SCENE_TYPES))
        html = response.content.decode('utf-8')
        self.assertIn('场景图工作区', html)
        for category in (
                '全部', '品牌场景', '直播间背景', '街拍场景', '棚拍场景', '商场试衣间', '室内全身镜',
                '田间自然', '海边旅拍', '海边氛围', '山野公路', '湖畔草地', '花房庭院', '咖啡店外',
                '复古街区', '城市夜景', '天台露台', '艺术馆', '酒店民宿', '地铁通勤',
                '校园林荫', '公园绿荫', '其他场景'):
            self.assertIn(category, html)
        self.assertIn('data-commerce-scene-filter="全部"', html)
        self.assertIn('data-commerce-scene-filter="品牌场景"', html)
        self.assertIn('data-commerce-scene-filter="其他场景"', html)
        self.assertNotIn('data-commerce-scene-filter="咖啡店落地窗旁"', html)
        self.assertIn('data-commerce-scene-category="直播间背景"', html)
        self.assertIn('data-commerce-scene-category="咖啡店落地窗旁"', html)
        self.assertIn('data-commerce-scene-group="其他场景"', html)
        self.assertIn('filterCommerceSceneCards', html)
        self.assertIn("sceneCard.dataset.commerceSceneGroup === activeCommerceSceneFilter", html)
        self.assertIn("sceneCard.classList.toggle('hidden', !visible);", html)
        self.assertIn('data-commerce-scene-empty-filter', html)
        self.assertIn('批量生成场景图', html)
        self.assertIn('data-commerce-scene-type-group="基础商拍"', html)
        self.assertIn('data-commerce-scene-type-group="出片氛围"', html)
        self.assertIn('适合商品页、直播间、棚拍和试衣间，出图稳定、主体落位清楚', html)
        self.assertIn('适合生活方式大片、街拍旅拍、自然光和城市夜景，更强调情绪和画面感', html)
        self.assertIn('data-commerce-scene-type-pill', html)
        self.assertIn('whitespace-nowrap', html)
        self.assertIn('name="scene_types"', html)
        self.assertIn('name="brand_scene_name"', html)
        self.assertIn('品牌名称 / 标识', html)
        self.assertIn('例如：LUMIA Studio', html)
        self.assertIn('name="time_mood"', html)
        self.assertIn('>白天<', html)
        self.assertIn('>夜晚<', html)
        self.assertIn('commerce-segment-option', html)
        self.assertIn('commerce-segment-option--active', html)
        self.assertIn('data-commerce-segment-input', html)
        self.assertIn('syncCommerceSegmentState', html)
        self.assertNotIn('白天氛围', html)
        self.assertNotIn('夜晚氛围', html)
        self.assertIn('name="crowd_mode"', html)
        self.assertIn('>空场<', html)
        self.assertIn('>有人<', html)
        self.assertNotIn('空场景', html)
        self.assertNotIn('有人氛围', html)
        self.assertIn('data-commerce-brand-scene-field', html)
        self.assertIn('hidden', html.split('data-commerce-brand-scene-field', 1)[0].split('<label', 1)[-1])
        self.assertIn('data-commerce-scene-type-input', html)
        self.assertIn('toggleCommerceBrandSceneField', html)
        self.assertIn("input.value === '品牌场景' && input.checked", html)
        self.assertIn('brandInput.value = \'\';', html)
        self.assertIn('name="custom_scenes"', html)
        self.assertIn('data-add-commerce-custom-scene', html)
        self.assertIn('data-remove-commerce-custom-scene', html)
        self.assertIn("row.remove();", html)
        self.assertIn('data-commerce-scene-list', html)
        self.assertIn('data-commerce-scene-poll-url="/commerce/series/10/scenes/"', html)
        self.assertIn('data-commerce-scene-create-form', html)
        self.assertIn('data-commerce-scene-create-submit', html)
        self.assertIn('data-commerce-scene-count-per-scene', html)
        self.assertIn('data-commerce-scene-credit-cost', html)
        self.assertIn('const commerceSceneCreditsPerImage = 8;', html)
        self.assertIn('calculateCommerceSceneGenerateCount', html)
        self.assertIn('updateCommerceSceneCreditDisplay', html)
        self.assertIn('credits = count * commerceSceneCreditsPerImage', html)
        self.assertIn('正在提交，马上显示生成中...', html)
        self.assertIn('data-commerce-scene-status="completed"', html)
        self.assertIn('pollCommerceSceneList', html)
        self.assertIn('https://oss.example.com/commerce/scenes/live.png', html)
        self.assertIn('data-commerce-scene-preview="https://oss.example.com/commerce/scenes/live.png"', html)
        self.assertIn('data-commerce-scene-frame', html)
        self.assertIn('class="block h-auto w-full object-contain', html)
        self.assertIn("closest('[data-commerce-scene-preview]')", html)
        self.assertIn("openModal(url);", html)
        self.assertNotIn('aspect-video', html)
        for ratio_label in (
                '9:16 - 抖音 / 快手 / 视频号全屏竖屏',
                '3:4 - 真人模特 / 服装试穿竖图',
                '1:1 - 商品卡片 / 方图投放素材',
                '16:9 - 横屏展示 / 店铺详情素材'):
            self.assertIn(ratio_label, html)
        self.assertNotIn('<textarea name="description"', html)
        self.assertNotIn('补充描述', html)

    def test_commerce_scene_batch_create_prepares_then_schedules_selected_and_custom_scenes(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = [{'id': 701}, {'id': 702}, {'id': 703}]

        with patch('apps.series.views.get_client', return_value=backend_client), \
                patch('apps.series.views._start_commerce_scene_batch_async') as start_async:
            response = self.client.post('/commerce/series/10/scenes/create/', {
                'scene_types': ['品牌场景', '直播间背景', '街拍场景', '田间自然', '海边氛围'],
                'brand_scene_name': 'LUMIA Studio',
                'custom_scenes': ['咖啡店落地窗旁', '地铁站扶梯旁', ''],
                'aspect_ratio': '16:9',
                'resolution': '3K',
                'count_per_scene': '2',
                'time_mood': 'night',
                'crowd_mode': 'ambient',
                'description': '浅色女装品牌调性',
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], '/commerce/series/10/scenes/')
        backend_client.post.assert_called_once_with('/v1/commerce/series/10/scenes/batch/prepare', {
            'sceneTypes': ['品牌场景', '直播间背景', '街拍场景', '田间自然', '海边氛围'],
            'brandSceneName': 'LUMIA Studio',
            'customScenes': ['咖啡店落地窗旁', '地铁站扶梯旁'],
            'aspectRatio': '16:9',
            'resolution': '3K',
            'countPerScene': 2,
            'timeMood': 'night',
            'crowdMode': 'ambient',
        })
        start_async.assert_called_once_with('token-1', 10, [701, 702, 703])

    def test_commerce_scene_batch_create_ignores_brand_name_without_brand_scene(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = [{'id': 801}]

        with patch('apps.series.views.get_client', return_value=backend_client), \
                patch('apps.series.views._start_commerce_scene_batch_async') as start_async:
            response = self.client.post('/commerce/series/10/scenes/create/', {
                'scene_types': ['直播间背景'],
                'brand_scene_name': 'LUMIA Studio',
                'aspect_ratio': '16:9',
                'resolution': '2K',
                'count_per_scene': '1',
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(backend_client.post.call_args_list[0].args[1], {
            'sceneTypes': ['直播间背景'],
            'customScenes': [],
            'aspectRatio': '16:9',
            'resolution': '2K',
            'countPerScene': 1,
            'timeMood': 'day',
            'crowdMode': 'empty',
        })
        start_async.assert_called_once_with('token-1', 10, [801])

    def test_commerce_series_detail_shows_queued_placeholder_from_backend_models_without_created_query(self):
        session = self.client.session
        session['token'] = 'token-1'
        session['email'] = 'tester@example.com'
        session.save()
        backend_client = Mock()

        def backend_get(endpoint):
            if endpoint == '/v1/commerce/series/10':
                return {
                    'id': 10,
                    'name': '小雅女装种草号',
                    'defaultAspectRatio': '9:16',
                }
            if endpoint == '/v1/commerce/series/10/models':
                return [{
                    'id': 66,
                    'seriesId': 10,
                    'name': '刚创建',
                    'genderAge': '女 / 年轻美女 / 中国',
                    'bodyProfile': '标准匀称',
                    'appearancePrompt': '年轻中国女性，五官清爽，气质亲和。',
                    'modelAspectRatio': '16:9',
                    'status': 'queued',
                    'baseImageUrl': '',
                }]
            raise AssertionError(f'unexpected endpoint: {endpoint}')

        backend_client.get.side_effect = backend_get

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/commerce/series/10/')

        self.assertEqual(response.status_code, 200)
        html = response.content.decode('utf-8')
        self.assertIn('刚创建', html)
        self.assertIn('commerce-model-spinner', html)
        self.assertIn('animate-spin', html)
        self.assertIn('生成中', html)
        backend_client.post.assert_not_called()

    def test_commerce_model_regenerate_forwards_to_backend_and_returns_to_series(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = {'id': 66, 'seriesId': 10, 'name': '妹妹'}

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/models/66/regenerate/')

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], '/commerce/series/10/')
        backend_client.post.assert_called_once_with('/v1/commerce/models/66/regenerate')

    def test_commerce_model_confirm_and_unlock_return_to_series(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.post.side_effect = [
            {'id': 66, 'seriesId': 10, 'name': '妹妹'},
            {'id': 66, 'seriesId': 10, 'name': '妹妹'},
        ]

        with patch('apps.series.views.get_client', return_value=backend_client):
            confirm_response = self.client.post('/commerce/models/66/confirm/')
            unlock_response = self.client.post('/commerce/models/66/unlock/')

        self.assertEqual(confirm_response.status_code, 302)
        self.assertEqual(confirm_response['Location'], '/commerce/series/10/')
        self.assertEqual(unlock_response.status_code, 302)
        self.assertEqual(unlock_response['Location'], '/commerce/series/10/')
        self.assertEqual(backend_client.post.call_args_list[0].args[0], '/v1/commerce/models/66/confirm')
        self.assertEqual(backend_client.post.call_args_list[1].args[0], '/v1/commerce/models/66/unlock')

    def test_commerce_model_confirm_returns_json_for_ajax(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = {'id': 66, 'seriesId': 10, 'name': '妹妹', 'status': 'locked'}

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post(
                '/commerce/models/66/confirm/',
                HTTP_X_REQUESTED_WITH='XMLHttpRequest',
                HTTP_ACCEPT='application/json',
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {
            'code': 200,
            'data': {'id': 66, 'seriesId': 10, 'name': '妹妹', 'status': 'locked'},
            'message': '模特已锁定',
        })
        backend_client.post.assert_called_once_with('/v1/commerce/models/66/confirm')

    def test_commerce_model_detail_requires_locked_model_before_binding_products(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.get.return_value = {
            'id': 66,
            'seriesId': 10,
            'status': 'pending_review',
        }

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/commerce/models/66/')

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], '/commerce/series/10/')
        backend_client.get.assert_called_once_with('/v1/commerce/models/66')

    def test_commerce_failed_model_retry_button_prevents_double_submit(self):
        template_path = Path(settings.BASE_DIR) / 'templates/commerce/series_detail.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('commerce-model-regenerate-form', template)
        self.assertIn('commerce-model-regenerate-button', template)
        self.assertIn('isCommerceModelRegenerating', template)
        self.assertIn("button.textContent = '重新生成中...'", template)

    def test_commerce_model_prompt_input_is_disabled_while_generating_prompt(self):
        template_path = Path(settings.BASE_DIR) / 'templates/commerce/series_detail.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('生成提示词（1积分）', template)
        self.assertIn('大概花费15秒', template)
        self.assertIn('不需要描述服装', template)
        self.assertIn('promptInput.disabled = true', template)
        self.assertIn('promptInput.readOnly = true', template)
        self.assertIn("promptInput.classList.add('opacity-60', 'cursor-not-allowed', 'pointer-events-none')", template)
        self.assertIn("'pointer-events-none'", template)
        self.assertIn('promptInput.disabled = false', template)
        self.assertIn('promptInput.readOnly = false', template)
        self.assertIn("promptInput.classList.remove('opacity-60', 'cursor-not-allowed', 'pointer-events-none')", template)

    def test_commerce_model_prompt_button_binds_name_input_with_stable_selector(self):
        template_path = Path(settings.BASE_DIR) / 'templates/commerce/series_detail.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('form.querySelector(\'[name="name"]\')', template)
        self.assertNotIn('form.elements.name', template)

    def test_commerce_model_prompt_forwards_guided_choices(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = {
            'name': '小雅',
            'prompt': '年轻东亚女性，五官清爽，性格亲和自然，适合女装种草。'
        }

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post(
                '/commerce/series/10/models/prompt/',
                data=json.dumps({
                    'name': '小雅',
                    'gender': '女',
                    'model_type': '年轻美女',
                    'ethnicity': '中国',
                    'body_profile': '微胖亲和',
                }),
                content_type='application/json',
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['name'], '小雅')
        self.assertEqual(response.json()['prompt'], '年轻东亚女性，五官清爽，性格亲和自然，适合女装种草。')
        self.assertNotIn('身材设定', response.json()['prompt'])
        backend_client.post.assert_called_once_with('/v1/commerce/models/prompt', {
            'gender': '女',
            'modelType': '年轻美女',
            'ethnicity': '中国',
            'bodyProfile': '微胖亲和',
        })

    def test_commerce_model_create_stays_on_series_and_forwards_confirmed_prompt(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = {'id': 66, 'name': '小雅'}

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/series/10/models/create/', {
                'name': '小雅',
                'gender': '女',
                'model_type': '年轻美女',
                'ethnicity': '中国',
                'body_profile': '丰满曲线',
                'model_aspect_ratio': '9:16',
                'appearance_prompt': '年轻东亚女性，五官清爽，性格亲和自然，适合女装种草。',
                'source_type': 'uploaded',
                'base_image_url': 'https://oss.example.com/model.png',
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], '/commerce/series/10/?created_model=1&created_model_id=66')
        backend_client.post.assert_called_once_with('/v1/commerce/series/10/models', {
            'name': '小雅',
            'sourceType': 'ai',
            'gender': '女',
            'modelType': '年轻美女',
            'ethnicity': '中国',
            'bodyProfile': '丰满曲线',
            'modelAspectRatio': '16:9',
            'appearancePrompt': '年轻东亚女性，五官清爽，性格亲和自然，适合女装种草。',
        })

    def test_commerce_model_start_image_generation_forwards_to_backend(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = {'id': 66, 'status': 'generating'}

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/models/66/generate-image/')

        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json()['success'])
        backend_client.post.assert_called_once_with('/v1/commerce/models/66/generate-image')

    def test_commerce_model_create_forces_recommended_sixteen_nine_model_ratio(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.post.return_value = {'id': 66, 'name': '小雅'}

        with patch('apps.series.views.get_client', return_value=backend_client):
            self.client.post('/commerce/series/10/models/create/', {
                'name': '小雅',
                'gender': '女',
                'model_type': '年轻美女',
                'ethnicity': '中国',
                'model_aspect_ratio': '3:4',
                'appearance_prompt': '年轻东亚女性，五官清爽，性格亲和自然，适合女装种草。',
            })

        backend_client.post.assert_called_once()
        payload = backend_client.post.call_args.args[1]
        self.assertEqual(payload['modelAspectRatio'], '16:9')

    def test_commerce_model_create_requires_model_name_before_backend(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/series/10/models/create/', {
                'name': '',
                'gender': '女',
                'model_type': '年轻美女',
                'ethnicity': '中国',
                'appearance_prompt': '年轻东亚女性，五官清爽，性格亲和自然，适合女装种草。',
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], '/commerce/series/10/')
        backend_client.post.assert_not_called()

    def test_commerce_model_create_requires_generated_prompt_before_backend(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/series/10/models/create/', {
                'name': '小雅',
                'gender': '男',
                'model_type': '年轻帅哥',
                'ethnicity': '中国',
                'appearance_prompt': '',
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], '/commerce/series/10/')
        backend_client.post.assert_not_called()

    def test_commerce_model_create_rejects_gender_type_mismatch_before_backend(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/series/10/models/create/', {
                'name': '小雅',
                'gender': '男',
                'model_type': '年轻美女',
                'ethnicity': '中国',
            })

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], '/commerce/series/10/')
        backend_client.post.assert_not_called()

    def test_commerce_series_detail_template_only_exposes_simple_ai_model_choices(self):
        template_path = Path(settings.BASE_DIR) / 'templates/commerce/series_detail.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('模特名称', template)
        self.assertIn('name="name" required', template)
        self.assertIn('id="commerceModelCreateButton"', template)
        self.assertIn('autocomplete="off"', template)
        self.assertIn('data-created-model="{{ model_created|yesno:"true,false" }}"', template)
        self.assertIn('data-created-model-id="{{ created_model_id|default:"" }}"', template)
        self.assertIn('data-start-image-url="/commerce/models/{{ created_model_id }}/generate-image/"', template)
        self.assertIn('startCreatedCommerceModelImageGeneration()', template)
        self.assertIn('resetCommerceModelForm()', template)
        self.assertIn('创建模特（6积分）', template)
        self.assertIn('updateCommerceModelCreateState', template)
        self.assertIn('createButton.disabled = !canSubmit', template)
        self.assertIn('event.preventDefault()', template)
        self.assertIn('createButton.textContent = \'创建中...\'', template)
        self.assertIn('if (result.name && !nameInput.value.trim())', template)
        self.assertIn('nameInput.value = result.name;', template)
        self.assertIn('性别', template)
        self.assertIn('年龄与类型', template)
        self.assertIn('人种风格', template)
        self.assertIn('身材', template)
        self.assertIn('生成提示词', template)
        self.assertIn('name="appearance_prompt"', template)
        self.assertIn('required', template)
        self.assertIn('/commerce/series/{{ commerce_series.id }}/models/prompt/', template)
        self.assertIn('name="gender"', template)
        self.assertIn('name="model_type"', template)
        self.assertIn('name="ethnicity"', template)
        self.assertIn('name="body_profile"', template)
        self.assertIn('name="model_aspect_ratio"', template)
        self.assertIn('value="16:9"', template)
        self.assertIn('固定 16:9', template)
        self.assertNotIn('9:16（竖屏预览）', template)
        self.assertNotIn('3:4（封面模特）', template)
        self.assertIn('标准匀称', template)
        self.assertIn('微胖亲和', template)
        self.assertIn('丰满曲线', template)
        self.assertIn('大码胖模特', template)
        self.assertIn('中国', template)
        self.assertIn('亚洲', template)
        self.assertIn('华人', template)
        self.assertIn('日韩', template)
        self.assertIn('AI 生成模特', template)
        self.assertIn('年轻美女', template)
        self.assertIn('年轻帅哥', template)
        self.assertIn('小女孩', template)
        self.assertIn('data-gender="男"', template)
        self.assertIn('data-gender="儿童"', template)
        self.assertIn('filterCommerceModelTypes', template)
        self.assertNotIn('name="source_type"', template)
        self.assertNotIn('name="base_image_url"', template)
        self.assertNotIn('name="style_tags"', template)
        self.assertNotIn('name="speaking_persona"', template)

    def test_commerce_model_create_locks_form_controls_while_submitting(self):
        template_path = Path(settings.BASE_DIR) / 'templates/commerce/series_detail.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('function setCommerceModelFormLocked(locked)', template)
        self.assertIn("form.querySelectorAll('input, select, textarea, button')", template)
        self.assertIn("field.setAttribute('aria-disabled', 'true')", template)
        self.assertIn("field.classList.add('opacity-60', 'cursor-not-allowed', 'pointer-events-none')", template)
        self.assertIn('field.readOnly = true', template)
        self.assertIn('field.tabIndex = -1', template)
        self.assertIn('setCommerceModelFormLocked(true)', template)
        self.assertIn("createButton.disabled = true", template)

    def test_project_generate_video_forwards_to_backend(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.post('/commerce/projects/88/generate-video/')

        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], '/commerce/projects/88/')
        backend_client.post.assert_called_once_with('/v1/commerce/projects/88/generate-video')


class CommerceHomeGroupingTests(TestCase):
    def test_home_template_switches_short_drama_and_ecommerce_tabs(self):
        template_path = Path(settings.BASE_DIR) / 'templates/series/series_list.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('role="tablist"', template)
        self.assertIn('data-series-tab="drama"', template)
        self.assertIn('data-series-tab="commerce"', template)
        self.assertIn('id="dramaSeriesPanel"', template)
        self.assertIn('id="commerceSeriesPanel"', template)
        self.assertIn('aria-controls="dramaSeriesPanel"', template)
        self.assertIn('aria-controls="commerceSeriesPanel"', template)
        self.assertIn('function activateSeriesTab(tabName)', template)
        self.assertIn('dramaPanel.hidden = isCommerce', template)
        self.assertIn('commercePanel.hidden = !isCommerce', template)
        self.assertIn('短剧系列', template)
        self.assertIn('电商系列', template)
        self.assertIn('创建电商系列', template)
        self.assertIn('href="/commerce/"', template)

    def test_commerce_series_api_forwards_to_backend(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.get.return_value = [{'id': 10, 'name': '小雅女装种草号'}]

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/commerce/api/series/')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['data'][0]['name'], '小雅女装种草号')
        backend_client.get.assert_called_once_with('/v1/commerce/series')

    def test_commerce_series_list_renders_cards_on_first_response(self):
        session = self.client.session
        session['token'] = 'token-1'
        session.save()
        backend_client = Mock()
        backend_client.get.return_value = [
            {'id': 10, 'name': '测试', 'defaultAspectRatio': '3:4'},
            {'id': 11, 'name': '女装种草', 'defaultAspectRatio': '9:16'},
        ]

        with patch('apps.series.views.get_client', return_value=backend_client):
            response = self.client.get('/commerce/')

        self.assertEqual(response.status_code, 200)
        html = response.content.decode('utf-8')
        self.assertIn('测试', html)
        self.assertIn('女装种草', html)
        self.assertIn('3:4', html)
        self.assertIn('9:16', html)
        self.assertIn('模特 -> 商品 -> 口播/试穿视频', html)
        list_block = html.split('id="commerceSeriesList"', 1)[1].split('</div>\n</div>', 1)[0]
        self.assertNotIn('加载中...', list_block)
        self.assertNotIn("fetch('/commerce/api/series/')", html)
        backend_client.get.assert_called_once_with('/v1/commerce/series')
