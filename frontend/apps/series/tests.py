import json
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


class EpisodeEntryPerformanceTests(TestCase):
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

        self.assertIn('海带 AI 智能短剧制作系统', combined)
        self.assertNotIn('Manga AI', combined)
        self.assertIn('aria-label="海带 AI 智能短剧制作系统"', combined)

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
        self.assertIn('brand-subtitle">AI 智能短剧制作系统</span>', combined)

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


class GptImage2HomeTests(TestCase):
    def test_home_page_has_today_update_announcement(self):
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
        self.assertIn('今日更新', template)
        self.assertIn('2026.05.16', template)
        self.assertIn('Seedance 2.0 正式全面上线', template)
        self.assertIn('原生支持真人人脸表现', template)
        self.assertIn('新接入可灵 Kling v3 Omni 模型', template)
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

    def test_home_page_has_disabled_ecommerce_button_under_workbench(self):
        template_path = Path(settings.BASE_DIR) / 'templates/series/series_list.html'
        template = template_path.read_text(encoding='utf-8')

        workbench_block = template.split('基础工作台', 1)[1].split('AI 工具', 1)[0]
        self.assertIn('电商带货', workbench_block)
        self.assertIn('home-action-disabled', workbench_block)
        self.assertIn('disabled', workbench_block)
        self.assertIn('等待上线', workbench_block)
        self.assertIn('功能研制中', workbench_block)
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
        self.assertIn("['16:9', '9:16', '2:1', '1:2', '21:9', '9:21']", template)

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
            response = self.client.get('/api/admin/credits/dashboard/?hours=24&recordPage=2&recordPageSize=15')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['data']['totalBalance'], 10928)
        backend_client.get.assert_called_once_with('/v1/admin/credits/dashboard?hours=24&recordPage=2&recordPageSize=15')

    def test_credit_admin_template_contains_chart_and_tables(self):
        template_path = Path(settings.BASE_DIR) / 'templates/credits/admin_dashboard.html'
        template = template_path.read_text(encoding='utf-8')

        self.assertIn('id="creditUsageChart"', template)
        self.assertIn('drawCreditUsageChart', template)
        self.assertIn('用户积分余额', template)
        self.assertIn('最近积分流水', template)
        self.assertIn('近 3 天', template)
        self.assertIn('id="creditRecordPagination"', template)
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
