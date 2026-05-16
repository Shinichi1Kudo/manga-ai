from django.shortcuts import render, redirect
from django.http import JsonResponse, HttpResponse, HttpResponseForbidden
from django.views.decorators.http import require_http_methods
from django.views.decorators.csrf import csrf_exempt
from django.contrib import messages
from concurrent.futures import ThreadPoolExecutor
import json
import requests

from api.backend_client import BackendClient, BackendAPIError


def get_client(request):
    """获取带认证Token的BackendClient"""
    token = request.session.get('token')
    return BackendClient(token=token)


def _parallel_backend_gets(token, endpoints):
    """并发请求后端接口，避免页面入口串行等待。"""
    if not endpoints:
        return {}

    def fetch(endpoint):
        return BackendClient(token=token).get(endpoint)

    max_workers = min(len(endpoints), 8)
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        future_map = {
            executor.submit(fetch, endpoint): name
            for name, endpoint in endpoints.items()
        }
        results = {}
        for future, name in future_map.items():
            results[name] = future.result()
        return results


def _build_roles_from_role_assets(role_assets):
    """把系列角色服装资产接口转换成详情页角色卡片需要的轻量结构。"""
    roles = []
    for role in (role_assets or {}).get('roles', []) or []:
        clothings = []
        asset_url = None
        for clothing in role.get('clothings', []) or []:
            clothing_url = clothing.get('assetUrl')
            if not asset_url and clothing_url:
                asset_url = clothing_url
            clothing_id = clothing.get('clothingId')
            clothings.append({
                'id': clothing_id,
                'clothingId': clothing_id,
                'clothingName': clothing.get('clothingName') or f'服装{clothing_id or ""}',
                'assetUrl': clothing_url,
                'assetId': clothing.get('assetId'),
                'version': clothing.get('version'),
                'active': clothing.get('active'),
                'defaultClothing': clothing.get('defaultClothing') if clothing.get('defaultClothing') is not None else clothing_id == 1,
                'status': 1,
            })

        roles.append({
            'id': role.get('id'),
            'roleName': role.get('roleName'),
            'assetUrl': asset_url,
            'status': 3 if asset_url else 0,
            'statusDesc': '已锁定' if asset_url else '生成中',
            'clothings': clothings,
        })
    return roles


def series_list(request):
    """首页 - 系列列表"""
    if request.headers.get('X-Requested-With') == 'XMLHttpRequest' or request.path.startswith('/api/'):
        # API 请求 - 支持分页
        page = request.GET.get('page', 1)
        page_size = request.GET.get('pageSize', 9)
        client = get_client(request)
        try:
            result = client.get(f'/v1/series/list?page={page}&pageSize={page_size}')
            response = JsonResponse({'data': result})
            # 禁止缓存
            response['Cache-Control'] = 'no-cache, no-store, must-revalidate'
            response['Pragma'] = 'no-cache'
            response['Expires'] = '0'
            return response
        except BackendAPIError as e:
            status = 401 if e.status_code == 401 else 500
            return JsonResponse({'code': e.status_code, 'message': e.message, 'data': []}, status=status)

    return render(request, 'series/series_list.html', {
        'processing_series_ids': json.dumps([]),
    })


@csrf_exempt
def series_init(request):
    """系列初始化页面"""
    if request.method == 'POST':
        series_name = request.POST.get('series_name', '').strip()
        outline = request.POST.get('outline', '').strip()
        background = request.POST.get('background', '').strip()
        series_style = request.POST.get('series_style', '').strip()
        characters_json = request.POST.get('characters_json', '').strip()

        # 构建表单数据，用于验证失败时回填
        form_data = {
            'series_name': series_name,
            'outline': outline,
            'background': background,
            'series_style': series_style,
            'characters_json': characters_json,
        }

        # 验证必填字段
        if not series_name:
            messages.error(request, '请输入系列名称')
            return render(request, 'series/series_init.html', {'form_data': form_data})
        if not outline:
            messages.error(request, '请输入剧本大纲')
            return render(request, 'series/series_init.html', {'form_data': form_data})
        if not series_style:
            messages.error(request, '请选择系列风格')
            return render(request, 'series/series_init.html', {'form_data': form_data})
        if not characters_json:
            messages.error(request, '请至少添加一个角色')
            return render(request, 'series/series_init.html', {'form_data': form_data})

        client = get_client(request)
        try:
            result = client.post('/v1/series/init', {
                'seriesName': series_name,
                'outline': outline,
                'background': background,
                'seriesStyle': series_style,
                'charactersJson': characters_json,
            })
            # 创建成功后跳转首页，显示提示消息
            messages.success(request, f'系列 "{series_name}" 创建成功！角色图片正在后台生成中，完成后可进入审核。')
            return redirect('series:list')
        except BackendAPIError as e:
            messages.error(request, f'初始化失败: {e.message}')
            return render(request, 'series/series_init.html', {'form_data': form_data})

    return render(request, 'series/series_init.html')


def series_progress(request, series_id):
    """处理进度页面"""
    client = get_client(request)
    try:
        series = client.get(f'/v1/series/{series_id}')
    except BackendAPIError as e:
        messages.error(request, f'获取系列信息失败: {e.message}')
        return redirect('series:list')

    return render(request, 'series/series_progress.html', {
        'series': series,
        'series_id': series_id,
    })


def series_detail(request, series_id):
    """系列详情页面"""
    client = get_client(request)
    try:
        series = client.get(f'/v1/series/{series_id}')
        roles = client.get(f'/v1/roles/series/{series_id}')
    except BackendAPIError as e:
        messages.error(request, f'获取系列信息失败: {e.message}')
        return redirect('series:list')

    return render(request, 'series/series_detail.html', {
        'series': series,
        'roles': roles,
    })


def series_review(request, series_id):
    """角色审核页面"""
    client = get_client(request)
    try:
        series = client.get(f'/v1/series/{series_id}')
        roles = client.get(f'/v1/roles/series/{series_id}')

        # 批量获取所有角色的资产（一次请求，避免 N+1 问题）
        assets_map = client.get(f'/v1/assets/series/{series_id}/clothings')

        # 为每个角色分配资产
        for role in roles:
            role_id = role.get('id')
            role['assets'] = assets_map.get(str(role_id), [])  # JSON key 是字符串

            # 检查是否有任何成功生成的资产（用于决定是否显示"生成新服装"按钮）
            role['has_successful_asset'] = any(
                a.get('filePath') and a.get('filePath').strip()
                for a in role.get('assets', [])
            )
    except BackendAPIError as e:
        messages.error(request, f'获取系列信息失败: {e.message}')
        return redirect('series:list')

    response = render(request, 'series/series_review.html', {
        'series': series,
        'roles': roles,
        'series_id': series_id,
        'confirmed_count': sum(1 for r in roles if r.get('status', 0) >= 2),
    })
    # 禁止缓存，确保每次都从服务器获取最新数据
    response['Cache-Control'] = 'no-cache, no-store, must-revalidate'
    response['Pragma'] = 'no-cache'
    response['Expires'] = '0'
    return response


@require_http_methods(["POST"])
def series_lock(request, series_id):
    """锁定系列"""
    client = get_client(request)
    try:
        client.post(f'/v1/series/{series_id}/lock')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["PUT"])
def series_update(request, series_id):
    """更新系列信息"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        client.put(f'/v1/series/{series_id}', {
            'seriesName': data.get('seriesName'),
            'seriesStyle': data.get('seriesStyle'),
            'outline': data.get('outline'),
            'background': data.get('background'),
        })
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


def api_progress(request, series_id):
    """获取处理进度 - AJAX 接口"""
    client = get_client(request)
    try:
        result = client.get(f'/v1/series/{series_id}/progress')
        return JsonResponse(result)
    except BackendAPIError as e:
        return JsonResponse({'error': e.message}, status=500)


def subject_replacement_page(request):
    """主体替换页面"""
    return render(request, 'subject_replacement/index.html')


def gpt_image2_page(request):
    """GPT-Image2 生图工作台"""
    return render(request, 'series/gpt_image2.html')


@csrf_exempt
@require_http_methods(["GET", "POST"])
def subject_replacement_tasks(request):
    """主体替换任务列表和创建接口"""
    client = get_client(request)
    try:
        if request.method == 'GET':
            limit = request.GET.get('limit', 20)
            result = client.get(f'/v1/subject-replacements?limit={limit}')
            return JsonResponse({'code': 200, 'data': result})

        data = json.loads(request.body)
        result = client.post('/v1/subject-replacements', data)
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)
    except json.JSONDecodeError:
        return JsonResponse({'code': 400, 'message': 'Invalid JSON'}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)}, status=500)


@csrf_exempt
@require_http_methods(["GET", "DELETE"])
def subject_replacement_task_detail(request, task_id):
    """主体替换任务详情"""
    if request.method == 'DELETE':
        return subject_replacement_task_delete(request, task_id)

    client = get_client(request)
    try:
        result = client.get(f'/v1/subject-replacements/{task_id}')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)}, status=500)


@csrf_exempt
@require_http_methods(["POST"])
def subject_replacement_task_rename(request, task_id):
    """修改主体替换任务名称"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        result = client.post(f'/v1/subject-replacements/{task_id}/name', data)
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)
    except json.JSONDecodeError:
        return JsonResponse({'code': 400, 'message': 'Invalid JSON'}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)}, status=500)


@csrf_exempt
@require_http_methods(["DELETE"])
def subject_replacement_task_delete(request, task_id):
    """删除主体替换任务"""
    client = get_client(request)
    try:
        client.delete(f'/v1/subject-replacements/{task_id}')
        return JsonResponse({'code': 200, 'data': None})
    except BackendAPIError as e:
        if e.message == '任务不存在':
            return JsonResponse({'code': 200, 'data': None})
        return JsonResponse({'code': 400, 'message': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)}, status=500)


@csrf_exempt
@require_http_methods(["POST"])
def subject_replacement_upload_video(request):
    """上传主体替换原视频"""
    client = get_client(request)
    try:
        file = request.FILES.get('file')
        if not file:
            return JsonResponse({'code': 400, 'message': '请选择要上传的视频'}, status=400)

        allowed_types = {'video/mp4', 'video/webm', 'video/quicktime', 'video/x-m4v'}
        filename = (file.name or '').lower()
        if file.content_type not in allowed_types and not filename.endswith(('.mp4', '.webm', '.mov', '.m4v')):
            return JsonResponse({'code': 400, 'message': '只支持 MP4、WEBM、MOV、M4V 格式的视频'}, status=400)

        files = {'file': (file.name, file.file, file.content_type or 'application/octet-stream')}
        result = client.upload('/v1/subject-replacements/upload-video', files)
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)}, status=500)


@csrf_exempt
@require_http_methods(["POST"])
def subject_replacement_upload_reference(request):
    """上传主体替换参考图"""
    client = get_client(request)
    try:
        file = request.FILES.get('file')
        if not file:
            return JsonResponse({'code': 400, 'message': '请选择要上传的参考图'}, status=400)

        allowed_types = {'image/jpeg', 'image/png', 'image/webp'}
        filename = (file.name or '').lower()
        if file.content_type not in allowed_types and not filename.endswith(('.jpg', '.jpeg', '.png', '.webp')):
            return JsonResponse({'code': 400, 'message': '只支持 JPG、PNG、WEBP 格式的图片'}, status=400)

        files = {'file': (file.name, file.read(), file.content_type or 'application/octet-stream')}
        result = client.upload('/v1/subject-replacements/upload-reference', files)
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)}, status=500)


@require_http_methods(["GET"])
def gpt_image2_tasks(request):
    """GPT-Image2 生图任务列表"""
    client = get_client(request)
    limit = request.GET.get('limit', '50')
    try:
        result = client.get(f'/v1/gpt-image2?limit={limit}')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)}, status=500)


@csrf_exempt
@require_http_methods(["POST"])
def gpt_image2_generate(request):
    """首页 GPT-Image2 生图接口"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        payload = {
            'prompt': (data.get('prompt') or '').strip(),
            'aspectRatio': data.get('aspectRatio') or '1:1',
            'resolution': data.get('resolution') or '2k',
        }
        reference_image_url = (data.get('referenceImageUrl') or '').strip()
        if reference_image_url:
            payload['referenceImageUrl'] = reference_image_url

        result = client.post('/v1/gpt-image2/generate', payload)
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)
    except json.JSONDecodeError:
        return JsonResponse({'code': 400, 'message': 'Invalid JSON'}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)}, status=500)


@require_http_methods(["GET"])
def gpt_image2_task_detail(request, task_id):
    """首页 GPT-Image2 生图任务详情"""
    client = get_client(request)
    try:
        result = client.get(f'/v1/gpt-image2/{task_id}')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)}, status=500)


@require_http_methods(["GET"])
def gpt_image2_latest_task(request):
    """首页 GPT-Image2 最近一次生图任务"""
    client = get_client(request)
    try:
        result = client.get('/v1/gpt-image2/latest')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)}, status=500)


@csrf_exempt
@require_http_methods(["POST"])
def gpt_image2_upload_reference(request):
    """首页 GPT-Image2 参考图上传接口"""
    client = get_client(request)
    try:
        file = request.FILES.get('file')
        if not file:
            return JsonResponse({'code': 400, 'message': '请选择要上传的参考图'}, status=400)

        allowed_types = {'image/jpeg', 'image/png', 'image/webp'}
        filename = (file.name or '').lower()
        if file.content_type not in allowed_types and not filename.endswith(('.jpg', '.jpeg', '.png', '.webp')):
            return JsonResponse({'code': 400, 'message': '只支持 JPG、PNG、WEBP 格式的图片'}, status=400)
        if file.size and file.size > 10 * 1024 * 1024:
            return JsonResponse({'code': 400, 'message': '参考图大小不能超过10MB'}, status=400)

        files = {'file': (file.name, file.read(), file.content_type or 'application/octet-stream')}
        result = client.upload('/v1/gpt-image2/upload-reference', files)
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)}, status=500)


@csrf_exempt
@require_http_methods(["DELETE"])
def series_delete(request, series_id):
    """删除系列（移入回收站）"""
    client = get_client(request)
    try:
        client.delete(f'/v1/series/{series_id}')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


def trash_page(request):
    """回收站页面"""
    client = get_client(request)
    try:
        trash_list = client.get('/v1/series/trash')
    except BackendAPIError as e:
        messages.error(request, f'获取回收站列表失败: {e.message}')
        trash_list = []

    return render(request, 'series/trash.html', {
        'trash_list': trash_list,
        'trash_list_json': json.dumps(trash_list),
    })


@csrf_exempt
@require_http_methods(["POST"])
def series_restore(request, series_id):
    """恢复系列"""
    client = get_client(request)
    try:
        client.post(f'/v1/series/{series_id}/restore')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["DELETE"])
def series_permanent_delete(request, series_id):
    """彻底删除系列"""
    client = get_client(request)
    try:
        client.delete(f'/v1/series/{series_id}/permanent')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


# ==================== 剧集管理相关视图 ====================

def select_series_for_episode(request):
    """选择系列页面 - 用于剧集制作"""
    client = get_client(request)
    try:
        result = client.get('/v1/series/locked')
        locked_series = result if isinstance(result, list) else result.get('list', [])

        for series in locked_series:
            if series.get('roleCount') is None:
                series['roleCount'] = len(series.get('roles') or [])
    except BackendAPIError as e:
        messages.error(request, f'获取系列列表失败: {e.message}')
        locked_series = []

    return render(request, 'episode/select_series.html', {
        'locked_series': locked_series,
    })


def episode_list(request, series_id):
    """剧集列表页面"""
    client = get_client(request)
    try:
        series = client.get(f'/v1/series/{series_id}')
        episodes = client.get(f'/v1/episodes/series/{series_id}')
    except BackendAPIError as e:
        messages.error(request, f'获取剧集列表失败: {e.message}')
        return redirect('series:list')

    return render(request, 'episode/episode_list.html', {
        'series': series,
        'episodes': episodes,
        'series_id': series_id,
    })


@csrf_exempt
def episode_create(request, series_id):
    """创建剧集页面"""
    client = get_client(request)
    try:
        series = client.get(f'/v1/series/{series_id}')
    except BackendAPIError as e:
        messages.error(request, f'获取系列信息失败: {e.message}')
        return redirect('series:list')

    if request.method == 'POST':
        episode_number = request.POST.get('episode_number', '').strip()
        episode_name = request.POST.get('episode_name', '').strip()
        script_text = request.POST.get('script_text', '').strip()

        # 验证必填字段
        if not episode_number:
            messages.error(request, '请输入集数编号')
            return render(request, 'episode/episode_create.html', {
                'series': series,
                'series_id': series_id,
                'form_data': request.POST,
            })
        if not episode_name:
            messages.error(request, '请输入剧集名称')
            return render(request, 'episode/episode_create.html', {
                'series': series,
                'series_id': series_id,
                'form_data': request.POST,
            })
        if not script_text:
            messages.error(request, '请输入剧本内容')
            return render(request, 'episode/episode_create.html', {
                'series': series,
                'series_id': series_id,
                'form_data': request.POST,
            })

        # 验证集数编号为正整数
        try:
            episode_num = int(episode_number)
            if episode_num < 1:
                messages.error(request, '集数编号必须大于0')
                return render(request, 'episode/episode_create.html', {
                    'series': series,
                    'series_id': series_id,
                    'form_data': request.POST,
                })
        except ValueError:
            messages.error(request, '集数编号必须是整数')
            return render(request, 'episode/episode_create.html', {
                'series': series,
                'series_id': series_id,
                'form_data': request.POST,
            })

        try:
            result = client.post(f'/v1/episodes/series/{series_id}', {
                'episodeNumber': episode_num,
                'episodeName': episode_name,
                'scriptText': script_text,
            })

            # 启动异步解析
            episode_id = result.get('data') if isinstance(result, dict) else result
            if episode_id:
                client.post(f'/v1/episodes/{episode_id}/parse')

            messages.success(request, f'第{episode_number}集创建成功，正在解析剧本...')
            # 重定向到详情页，带上参数表示需要显示资产选择弹窗
            return redirect(f'/{series_id}/episodes/{episode_id}/?show_asset_picker=1')
        except BackendAPIError as e:
            messages.error(request, f'创建剧集失败: {e.message}')
            return render(request, 'episode/episode_create.html', {
                'series': series,
                'series_id': series_id,
                'form_data': request.POST,
            })

    return render(request, 'episode/episode_create.html', {
        'series': series,
        'series_id': series_id,
    })


@csrf_exempt
@require_http_methods(["DELETE"])
def episode_delete(request, episode_id):
    """API: 删除剧集"""
    client = get_client(request)
    try:
        client.delete(f'/v1/episodes/{episode_id}')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'success': False, 'error': str(e)}, status=500)


def episode_detail(request, series_id, episode_id):
    """剧集详情/分镜审核页面"""
    token = request.session.get('token')
    try:
        results = _parallel_backend_gets(token, {
            'series': f'/v1/series/{series_id}',
            'episode': f'/v1/episodes/{episode_id}?basic=true',
            'shots': f'/v1/shots/episode/{episode_id}',
            'scenes': f'/v1/scenes/series/{series_id}',
            'props': f'/v1/props/series/{series_id}?episodeId={episode_id}',
            'role_assets': f'/v1/assets/series/{series_id}/role-assets',
        })
        series = results.get('series') or {}
        episode = results.get('episode') or {}
        shots = results.get('shots') or []
        # 获取场景和道具资产
        all_scenes = results.get('scenes') or []
        all_props = results.get('props') or []
        all_roles = _build_roles_from_role_assets(results.get('role_assets') or {})
    except BackendAPIError as e:
        messages.error(request, f'获取剧集信息失败: {e.message}')
        return redirect('series:episode_list', series_id=series_id)

    # 获取本集分镜关联的场景ID和道具信息
    episode_scene_ids = set()
    episode_prop_ids = set()
    episode_prop_names = set()
    if shots:
        for shot in shots:
            if shot.get('sceneId'):
                episode_scene_ids.add(shot.get('sceneId'))
            # 从propsJson中提取道具信息
            props_json = shot.get('propsJson')
            if props_json:
                try:
                    props_data = json.loads(props_json) if isinstance(props_json, str) else props_json
                    if isinstance(props_data, list):
                        for prop_info in props_data:
                            if isinstance(prop_info, dict):
                                # 尝试获取ID
                                prop_id = prop_info.get('propId') or prop_info.get('id')
                                if prop_id:
                                    episode_prop_ids.add(prop_id)
                                # 也尝试获取名称用于匹配
                                prop_name = prop_info.get('propName') or prop_info.get('name')
                                if prop_name:
                                    episode_prop_names.add(prop_name)
                except:
                    pass

    # 状态码: 0=生成中, 1=待审核, 3=已锁定
    # 过滤场景：已锁定的全部显示 + 生成中/待审核的全部显示 + 本集关联的未锁定场景
    scenes = []
    for scene in all_scenes:
        # 添加当前版本号和资产URL (isActive 可能是 1/0 或 true/false)
        assets = scene.get('assets', [])
        active_asset = next((a for a in assets if a.get('isActive') == 1 or a.get('isActive') is True), None)
        scene['activeVersion'] = active_asset.get('version') if active_asset else (len(assets) if assets else None)
        scene['activeAssetUrl'] = active_asset.get('filePath') if active_asset else None
        scene['assetCount'] = len(assets)

        if scene.get('status') == 3:  # 已锁定
            scenes.append(scene)
        elif scene.get('status') in [0, 1]:  # 生成中或待审核
            scenes.append(scene)
        elif scene.get('id') in episode_scene_ids:  # 本集关联的未锁定场景
            scenes.append(scene)

    # 后端已按当前剧集返回可见道具：已锁定全系列共享，生成中/待审核仅返回本集生成的版本。
    props = []
    for prop in all_props:
        if prop.get('status') == 3:  # 已锁定
            props.append(prop)
        elif prop.get('status') in [0, 1]:  # 生成中或待审核（仅本集）
            props.append(prop)
        elif prop.get('id') in episode_prop_ids:  # 本集关联的未锁定道具(通过ID)
            props.append(prop)
        elif prop.get('propName') in episode_prop_names:  # 本集关联的未锁定道具(通过名称)
            props.append(prop)
        # 添加当前版本号和资产URL (isActive 可能是 1/0 或 true/false)
        assets = prop.get('assets', [])
        active_asset = next((a for a in assets if a.get('isActive') == 1 or a.get('isActive') is True), None)
        prop['activeVersion'] = active_asset.get('version') if active_asset else (len(assets) if assets else None)
        prop['activeAssetUrl'] = active_asset.get('filePath') if active_asset else None
        prop['transparentUrl'] = active_asset.get('filePath') if active_asset else None
        prop['assetCount'] = len(assets)

    mention_scenes = [
        {
            'id': scene.get('id'),
            'sceneName': scene.get('sceneName'),
            'activeAssetUrl': scene.get('activeAssetUrl'),
        }
        for scene in scenes
        if scene.get('activeAssetUrl')
    ]
    mention_props = [
        {
            'id': prop.get('id'),
            'propName': prop.get('propName'),
            'activeAssetUrl': prop.get('activeAssetUrl'),
            'transparentUrl': prop.get('transparentUrl'),
        }
        for prop in props
        if prop.get('activeAssetUrl') or prop.get('transparentUrl')
    ]
    generated_shots = sum(1 for shot in shots if shot.get('generationStatus') == 2)
    pending_shots = sum(1 for shot in shots if shot.get('generationStatus') == 0)
    generating_shots = sum(1 for shot in shots if shot.get('generationStatus') == 1)
    locked_review_shots = [shot for shot in shots if shot.get('status') == 1]
    pending_review_shots = [shot for shot in shots if shot.get('status') != 1]
    locked_review_shots_count = len(locked_review_shots)
    progress_percent = round(locked_review_shots_count / len(shots) * 100) if shots else 0

    return render(request, 'episode/episode_detail.html', {
        'series': series,
        'episode': episode,
        'shots': shots,
        'scenes': scenes,
        'props': props,
        'roles': all_roles,
        'mention_scenes': mention_scenes,
        'mention_props': mention_props,
        'generated_shots': generated_shots,
        'pending_shots': pending_shots,
        'generating_shots': generating_shots,
        'locked_review_shots_count': locked_review_shots_count,
        'pending_review_shots_count': len(pending_review_shots),
        'progress_percent': progress_percent,
        'series_id': series_id,
        'episode_id': episode_id,
    })


@csrf_exempt
@require_http_methods(["POST"])
def shot_generate_video(request, shot_id):
    """生成单个分镜视频"""
    client = get_client(request)
    try:
        client.post(f'/v1/shots/{shot_id}/generate')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def episode_generate_videos(request, episode_id):
    """批量生成剧集的所有分镜视频"""
    client = get_client(request)
    try:
        client.post(f'/v1/shots/episode/{episode_id}/generate')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def shot_upload_video(request, shot_id):
    """手动上传分镜视频"""
    client = get_client(request)
    try:
        video_file = request.FILES.get('file')
        if not video_file:
            return JsonResponse({'code': 400, 'message': '请选择要上传的视频'}, status=400)

        content_type = video_file.content_type or ''
        allowed_types = {'video/mp4', 'video/webm', 'video/quicktime', 'video/x-m4v'}
        if content_type not in allowed_types:
            filename = (video_file.name or '').lower()
            if not filename.endswith(('.mp4', '.webm', '.mov', '.m4v')):
                return JsonResponse({'code': 400, 'message': '只支持 MP4、WEBM、MOV 格式的视频'}, status=400)

        result = client.upload(
            f'/v1/shots/{shot_id}/upload-video',
            {'file': (video_file.name, video_file.file, content_type or 'application/octet-stream')},
            data={'aspectRatio': request.POST.get('aspectRatio') or '16:9'}
        )
        return JsonResponse({'code': 200, 'data': result, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["GET"])
def shot_download_video(request, shot_id):
    """API: 下载分镜视频（代理，解决跨域问题）"""
    import requests as req_lib
    from urllib.parse import unquote, quote
    import logging
    logger = logging.getLogger(__name__)
    client = get_client(request)
    try:
        shot = client.get(f'/v1/shots/{shot_id}')
        video_url = shot.get('videoUrl') or shot.get('video_url')
        logger.info(f"下载分镜视频 shot_id={shot_id}, video_url={video_url}")
        if not video_url:
            return JsonResponse({'success': False, 'error': '该分镜没有视频'}, status=404)

        # 通过后端获取视频URL，再用Django代理下载
        resp = req_lib.get(video_url, stream=True, timeout=300, allow_redirects=True)
        resp.raise_for_status()
        content_type = resp.headers.get('Content-Type', 'video/mp4')
        logger.info(f"OSS响应 status={resp.status_code}, content_type={content_type}, content_length={resp.headers.get('Content-Length')}")

        # 使用前端传来的文件名，格式：系列名_第X集剧集名_分镜名.mp4
        filename = unquote(request.GET.get('filename', f'shot_{shot_id}.mp4'))
        # RFC 5987: filename* 需要URL编码的中文
        encoded_filename = quote(filename)

        from django.http import StreamingHttpResponse
        response = StreamingHttpResponse(
            resp.iter_content(chunk_size=8192),
            content_type=content_type
        )
        # 同时提供 filename 和 filename* 确保兼容性
        response['Content-Disposition'] = f"attachment; filename=\"{shot_id}.mp4\"; filename*=UTF-8''{encoded_filename}"
        content_length = resp.headers.get('Content-Length')
        if content_length:
            response['Content-Length'] = content_length
        logger.info(f"下载响应准备完成, filename={filename}")
        return response
    except BackendAPIError as e:
        logger.error(f"下载分镜视频失败(BackendAPIError): {e.message}")
        return JsonResponse({'success': False, 'error': e.message}, status=400)
    except Exception as e:
        logger.error(f"下载分镜视频失败(Exception): {e}", exc_info=True)
        return JsonResponse({'success': False, 'error': str(e)}, status=500)


@csrf_exempt
@require_http_methods(["POST"])
def shot_review(request, shot_id):
    """审核分镜"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        client.post(f'/v1/shots/{shot_id}/review', {
            'approved': data.get('approved', True),
            'comment': data.get('comment', ''),
        })
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def shot_unlock(request, shot_id):
    """解锁分镜，恢复为待审核"""
    client = get_client(request)
    try:
        client.post(f'/v1/shots/{shot_id}/unlock')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["PUT"])
def shot_update(request, shot_id):
    """更新分镜"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        client.put(f'/v1/shots/{shot_id}', data)
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)
    except json.JSONDecodeError:
        return JsonResponse({'success': False, 'error': 'Invalid JSON'}, status=400)


@csrf_exempt
@require_http_methods(["GET"])
def shot_list_api(request, episode_id):
    """获取剧集分镜列表"""
    client = get_client(request)
    try:
        result = client.get(f'/v1/shots/episode/{episode_id}')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


def episode_progress(request, episode_id):
    """获取剧集进度 - AJAX接口"""
    client = get_client(request)
    try:
        result = client.get(f'/v1/episodes/{episode_id}/progress')
        return JsonResponse(result)
    except BackendAPIError as e:
        return JsonResponse({'error': e.message}, status=500)


@csrf_exempt
@require_http_methods(["PUT"])
def episode_update_script(request, episode_id):
    """更新剧本内容"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        client.put(f'/v1/episodes/{episode_id}/script', {
            'scriptText': data.get('scriptText', ''),
        })
        return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'success': False, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def episode_parse_script(request, episode_id):
    """重新解析剧本（只解析资产）"""
    client = get_client(request)
    try:
        client.post(f'/v1/episodes/{episode_id}/parse')
        return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'success': False, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def episode_parse_shots(request, episode_id):
    """解析分镜"""
    client = get_client(request)
    try:
        client.post(f'/v1/episodes/{episode_id}/parse-shots')
        return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'success': False, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def scene_create(request):
    """创建场景"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        result = client.post('/v1/scenes', {
            'seriesId': data.get('seriesId'),
            'episodeId': data.get('episodeId'),
            'sceneName': data.get('sceneName'),
            'aspectRatio': data.get('aspectRatio', '16:9'),
            'quality': data.get('quality', '2k'),
            'customPrompt': data.get('customPrompt'),
        })
        scene_id = result.get('data') if isinstance(result, dict) else result
        return JsonResponse({'code': 200, 'data': scene_id})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def scene_upload(request):
    """上传场景图片并创建/更新场景资产"""
    client = get_client(request)
    try:
        file = request.FILES.get('file')
        if not file:
            return JsonResponse({'code': 400, 'message': '请选择要上传的图片'}, status=400)

        allowed_types = ['image/jpeg', 'image/png', 'image/webp']
        if file.content_type not in allowed_types:
            return JsonResponse({'code': 400, 'message': '只支持 JPG、PNG、WEBP 格式'}, status=400)

        if file.size > 5 * 1024 * 1024:
            return JsonResponse({'code': 400, 'message': '图片大小不能超过5MB'}, status=400)

        files = {'file': (file.name, file.read(), file.content_type)}
        data = {
            'seriesId': request.POST.get('seriesId'),
            'sceneName': request.POST.get('sceneName', '').strip(),
            'aspectRatio': request.POST.get('aspectRatio', '16:9'),
            'quality': request.POST.get('quality', '2k'),
        }
        episode_id = request.POST.get('episodeId')
        custom_prompt = request.POST.get('customPrompt', '').strip()
        if episode_id:
            data['episodeId'] = episode_id
        if custom_prompt:
            data['customPrompt'] = custom_prompt
        result = client.upload('/v1/scenes/upload', files, data=data)
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)}, status=500)


@csrf_exempt
@require_http_methods(["POST"])
def scene_asset_upload(request, scene_id):
    """为已有场景上传图片版本"""
    client = get_client(request)
    try:
        file = request.FILES.get('file')
        if not file:
            return JsonResponse({'code': 400, 'message': '请选择要上传的图片'}, status=400)

        allowed_types = ['image/jpeg', 'image/png', 'image/webp']
        if file.content_type not in allowed_types:
            return JsonResponse({'code': 400, 'message': '只支持 JPG、PNG、WEBP 格式'}, status=400)

        if file.size > 5 * 1024 * 1024:
            return JsonResponse({'code': 400, 'message': '图片大小不能超过5MB'}, status=400)

        files = {'file': (file.name, file.read(), file.content_type)}
        data = {
            'aspectRatio': request.POST.get('aspectRatio', '16:9'),
        }
        custom_prompt = request.POST.get('customPrompt', '').strip()
        if custom_prompt:
            data['customPrompt'] = custom_prompt
        result = client.upload(f'/v1/scenes/{scene_id}/upload', files, data=data)
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)}, status=500)


@csrf_exempt
@require_http_methods(["POST"])
def prop_create(request):
    """创建道具"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        result = client.post('/v1/props', {
            'seriesId': data.get('seriesId'),
            'episodeId': data.get('episodeId'),
            'propName': data.get('propName'),
            'quality': data.get('quality', '2k'),
            'customPrompt': data.get('customPrompt'),
        })
        prop_id = result.get('data') if isinstance(result, dict) else result
        return JsonResponse({'code': 200, 'data': prop_id})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def prop_upload(request):
    """上传道具图片并创建/更新道具资产"""
    client = get_client(request)
    try:
        file = request.FILES.get('file')
        if not file:
            return JsonResponse({'code': 400, 'message': '请选择要上传的图片'}, status=400)

        allowed_types = ['image/jpeg', 'image/png', 'image/webp']
        if file.content_type not in allowed_types:
            return JsonResponse({'code': 400, 'message': '只支持 JPG、PNG、WEBP 格式'}, status=400)

        if file.size > 5 * 1024 * 1024:
            return JsonResponse({'code': 400, 'message': '图片大小不能超过5MB'}, status=400)

        files = {'file': (file.name, file.read(), file.content_type)}
        data = {
            'seriesId': request.POST.get('seriesId'),
            'propName': request.POST.get('propName', '').strip(),
            'quality': request.POST.get('quality', '2k'),
        }
        episode_id = request.POST.get('episodeId')
        custom_prompt = request.POST.get('customPrompt', '').strip()
        if episode_id:
            data['episodeId'] = episode_id
        if custom_prompt:
            data['customPrompt'] = custom_prompt
        result = client.upload('/v1/props/upload', files, data=data)
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)}, status=500)


@csrf_exempt
@require_http_methods(["POST"])
def prop_asset_upload(request, prop_id):
    """为已有道具上传图片版本"""
    client = get_client(request)
    try:
        file = request.FILES.get('file')
        if not file:
            return JsonResponse({'code': 400, 'message': '请选择要上传的图片'}, status=400)

        allowed_types = ['image/jpeg', 'image/png', 'image/webp']
        if file.content_type not in allowed_types:
            return JsonResponse({'code': 400, 'message': '只支持 JPG、PNG、WEBP 格式'}, status=400)

        if file.size > 5 * 1024 * 1024:
            return JsonResponse({'code': 400, 'message': '图片大小不能超过5MB'}, status=400)

        files = {'file': (file.name, file.read(), file.content_type)}
        data = {}
        episode_id = request.POST.get('episodeId')
        custom_prompt = request.POST.get('customPrompt', '').strip()
        if episode_id:
            data['episodeId'] = episode_id
        if custom_prompt:
            data['customPrompt'] = custom_prompt
        result = client.upload(f'/v1/props/{prop_id}/upload', files, data=data)
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)}, status=500)


@csrf_exempt
@require_http_methods(["GET"])
def props_by_series(request, series_id):
    """获取系列道具列表，用于详情页补齐跨标签状态。"""
    client = get_client(request)
    try:
        episode_id = request.GET.get('episodeId')
        path = f'/v1/props/series/{series_id}'
        if episode_id:
            path = f'{path}?episodeId={episode_id}'
        result = client.get(path)
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def scene_regenerate(request, scene_id):
    """重新生成场景"""
    client = get_client(request)
    try:
        data = json.loads(request.body) if request.body else {}
        client.post(f'/v1/scenes/{scene_id}/regenerate', {
            'customPrompt': data.get('customPrompt'),
            'aspectRatio': data.get('aspectRatio', '16:9'),
            'quality': data.get('quality', '2k'),
        })
        return JsonResponse({'code': 200, 'success': True})
    except requests.exceptions.ReadTimeout:
        return JsonResponse({'code': 504, 'message': '后端提交超时，请刷新后查看生成状态'}, status=504)
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def prop_regenerate(request, prop_id):
    """重新生成道具"""
    client = get_client(request)
    try:
        data = json.loads(request.body) if request.body else {}
        client.post(f'/v1/props/{prop_id}/regenerate', {
            'customPrompt': data.get('customPrompt'),
            'quality': data.get('quality', '2k'),
            'episodeId': data.get('episodeId'),
        })
        return JsonResponse({'code': 200, 'success': True})
    except requests.exceptions.ReadTimeout:
        return JsonResponse({'code': 504, 'message': '后端提交超时，请刷新后查看生成状态'}, status=504)
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def scene_rollback(request, scene_id):
    """场景回滚"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        asset_id = data.get('assetId')
        client.post(f'/v1/scenes/{scene_id}/rollback', {'assetId': asset_id})
        return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def prop_rollback(request, prop_id):
    """道具回滚"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        asset_id = data.get('assetId')
        payload = {'assetId': asset_id}
        if data.get('episodeId'):
            payload['episodeId'] = data.get('episodeId')
        client.post(f'/v1/props/{prop_id}/rollback', payload)
        return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


# ==================== 场景和道具管理 ====================

@csrf_exempt
@require_http_methods(["POST"])
def scene_lock(request, scene_id):
    """锁定场景"""
    client = get_client(request)
    try:
        client.post(f'/v1/scenes/{scene_id}/lock')
        return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def scene_unlock(request, scene_id):
    """解锁场景"""
    client = get_client(request)
    try:
        client.post(f'/v1/scenes/{scene_id}/unlock')
        return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["PUT"])
def scene_update_name(request, scene_id):
    """更新场景名称"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        client.put(f'/v1/scenes/{scene_id}/name', {'sceneName': data.get('sceneName')})
        return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def prop_lock(request, prop_id):
    """锁定道具"""
    client = get_client(request)
    try:
        data = json.loads(request.body) if request.body else {}
        client.post(f'/v1/props/{prop_id}/lock', {
            'episodeId': data.get('episodeId'),
        })
        return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def prop_unlock(request, prop_id):
    """解锁道具"""
    client = get_client(request)
    try:
        client.post(f'/v1/props/{prop_id}/unlock')
        return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["PUT"])
def prop_update_name(request, prop_id):
    """更新道具名称"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        client.put(f'/v1/props/{prop_id}/name', {'propName': data.get('propName')})
        return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


# ==================== 道具和场景详情接口（用于轮询） ====================

@csrf_exempt
@require_http_methods(["GET", "DELETE"])
def prop_detail(request, prop_id):
    """道具详情（GET）或删除（DELETE）"""
    client = get_client(request)
    try:
        if request.method == 'DELETE':
            client.delete(f'/v1/props/{prop_id}')
            return JsonResponse({'code': 200, 'success': True})
        else:
            # GET - 获取道具详情
            episode_id = request.GET.get('episodeId')
            query = []
            if episode_id:
                query.append(f'episodeId={episode_id}')
            if request.GET.get('includeHistory') == 'true':
                query.append('includeHistory=true')
            path = f'/v1/props/{prop_id}'
            if query:
                path = f'{path}?{"&".join(query)}'
            result = client.get(path)
            return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["GET", "DELETE"])
def scene_detail(request, scene_id):
    """场景详情（GET）或删除（DELETE）"""
    client = get_client(request)
    try:
        if request.method == 'DELETE':
            client.delete(f'/v1/scenes/{scene_id}')
            return JsonResponse({'code': 200, 'success': True})
        else:
            # GET - 获取场景详情
            result = client.get(f'/v1/scenes/{scene_id}')
            return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)

# ==================== 资产选择生成相关接口 ====================

@csrf_exempt
@require_http_methods(["GET"])
def episode_parsed_assets(request, episode_id):
    """获取解析后的资产清单"""
    client = get_client(request)
    try:
        result = client.get(f'/v1/episodes/{episode_id}/parsed-assets')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def episode_generate_assets(request, episode_id):
    """批量生成选中资产的图片"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        result = client.post(f'/v1/episodes/{episode_id}/generate-assets', {
            'sceneIds': data.get('sceneIds', []),
            'propIds': data.get('propIds', []),
            'newSceneNames': data.get('newSceneNames', []),
            'newPropNames': data.get('newPropNames', []),
            'unselectedSceneIds': data.get('unselectedSceneIds', []),
            'unselectedPropIds': data.get('unselectedPropIds', []),
            'unselectedSceneNames': data.get('unselectedSceneNames', []),
            'unselectedPropNames': data.get('unselectedPropNames', []),
            'quality': data.get('quality', '2k'),
            'parseMode': data.get('parseMode', 'default')
        })
        return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


# ========== 分镜参考图相关接口 ==========

@csrf_exempt
@require_http_methods(["GET", "PUT"])
def shot_references(request, shot_id):
    """获取或更新分镜参考图列表"""
    client = get_client(request)
    try:
        if request.method == 'GET':
            result = client.get(f'/v1/shots/{shot_id}/references')
            return JsonResponse({'code': 200, 'data': result})
        elif request.method == 'PUT':
            data = json.loads(request.body)
            client.put(f'/v1/shots/{shot_id}/references', data)
            return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["PUT"])
def shot_update_references(request, shot_id):
    """更新分镜参考图列表"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        client.put(f'/v1/shots/{shot_id}/references', data)
        return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def shot_match_assets(request, shot_id):
    """自动匹配分镜资产"""
    client = get_client(request)
    try:
        result = client.post(f'/v1/shots/{shot_id}/match-assets')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def shot_generate_with_references(request, shot_id):
    """带参考图生成视频"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        reference_urls = data.get('referenceUrls', [])
        payload = {'referenceUrls': reference_urls}
        if 'referenceImages' in data:
            payload['referenceImages'] = data.get('referenceImages') or []
        if 'shotUpdate' in data:
            payload['shotUpdate'] = data.get('shotUpdate') or {}
        if 'generationStartTime' in data:
            payload['generationStartTime'] = data.get('generationStartTime')
        client.post(f'/v1/shots/{shot_id}/generate-with-references', payload)
        return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def shot_generation_prepare(request, shot_id):
    """提交分镜生成中状态，并转发生成参数到后端"""
    client = get_client(request)
    try:
        data = json.loads(request.body or '{}')
        result = client.post(f'/v1/shots/{shot_id}/generation/prepare', data)
        return JsonResponse({'code': 200, 'data': result, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def shot_generation_start(request, shot_id):
    """启动已提交状态的分镜后台生成任务"""
    client = get_client(request)
    try:
        data = json.loads(request.body or '{}')
        client.post(f'/v1/shots/{shot_id}/generation/start', data)
        return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["GET"])
def shot_video_history(request, shot_id):
    """获取分镜视频版本历史"""
    client = get_client(request)
    try:
        data = client.get(f'/v1/shots/{shot_id}/video-history')
        return JsonResponse({'code': 200, 'data': data, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def shot_video_rollback(request, shot_id, asset_id):
    """回滚到指定视频版本"""
    client = get_client(request)
    try:
        data = client.post(f'/v1/shots/{shot_id}/rollback-video/{asset_id}')
        return JsonResponse({'code': 200, 'data': data, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def shot_create(request, episode_id):
    """创建分镜"""
    client = get_client(request)
    try:
        data = json.loads(request.body) if request.body else {}
        result = client.post(f'/v1/shots/episode/{episode_id}/create', data=data)
        return JsonResponse({'code': 200, 'data': result, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["DELETE"])
def shot_delete(request, shot_id):
    """删除分镜"""
    client = get_client(request)
    try:
        client.delete(f'/v1/shots/{shot_id}')
        return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def shot_reorder(request, episode_id):
    """重新排序分镜"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        client.post(f'/v1/shots/episode/{episode_id}/reorder', data)
        return JsonResponse({'code': 200, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


def contact_image(request):
    """获取联系我们二维码图片URL"""
    client = get_client(request)
    try:
        result = client.get('/v1/common/contact-image')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


def site_logo(request):
    """获取网站 Logo 图片URL"""
    client = BackendClient()
    try:
        result = client.get('/v1/common/site-logo')
        url = result.get('url') if isinstance(result, dict) else None
        if not url:
            return JsonResponse({'code': 500, 'message': '获取 Logo 失败'}, status=500)

        logo_response = requests.get(url, timeout=15)
        if logo_response.status_code != 200:
            return JsonResponse({'code': 500, 'message': '获取 Logo 失败'}, status=500)

        response = HttpResponse(
            logo_response.content,
            content_type=logo_response.headers.get('Content-Type') or 'image/png'
        )
        response['Content-Disposition'] = 'inline; filename="site-logo.png"'
        response['Cache-Control'] = 'public, max-age=86400'
        return response
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


def showcase_assets(request):
    """获取首页主体替换演示素材URL"""
    client = get_client(request)
    try:
        result = client.get('/v1/common/showcase-assets')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


def credit_records(request):
    """积分记录页面"""
    return render(request, 'credits/credit_records.html')


def _is_credit_admin_session(request):
    nickname = (request.session.get('nickname') or '').strip()
    email = (request.session.get('email') or '').strip().lower()
    return bool(request.session.get('token')) and (nickname == '工藤新一' or email == '1198693014@qq.com')


def credit_admin_dashboard(request):
    """工藤新一专属积分管理后台"""
    if not _is_credit_admin_session(request):
        return HttpResponseForbidden('无权访问积分管理后台')
    return render(request, 'credits/admin_dashboard.html')


def credit_admin_dashboard_api(request):
    """积分管理后台API代理"""
    if not _is_credit_admin_session(request):
        return JsonResponse({'code': 403, 'message': '无权访问积分管理后台'}, status=403)

    hours = request.GET.get('hours', 24)
    record_page = request.GET.get('recordPage', 1)
    record_page_size = request.GET.get('recordPageSize', 20)

    client = get_client(request)
    try:
        result = client.get(f'/v1/admin/credits/dashboard?hours={hours}&recordPage={record_page}&recordPageSize={record_page_size}')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': e.status_code, 'message': e.message}, status=400)


def credit_records_api(request):
    """积分记录API"""
    page = request.GET.get('page', 1)
    page_size = request.GET.get('pageSize', 20)
    record_type = request.GET.get('type', '')

    client = get_client(request)
    try:
        type_param = f'&type={record_type}' if record_type else ''
        result = client.get(f'/v1/credits/records?page={page}&pageSize={page_size}{type_param}')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def credits_redeem(request):
    """兑换积分API"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        code = data.get('code', '').strip()

        if not code:
            return JsonResponse({'code': 400, 'message': '请输入兑换码'}, status=400)

        result = client.post('/v1/credits/redeem', {'code': code})
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)}, status=500)


def user_info_api(request):
    """用户信息API"""
    client = get_client(request)
    try:
        result = client.get('/v1/user/info')
        response = JsonResponse({'code': 200, 'data': result})
        # 禁止缓存
        response['Cache-Control'] = 'no-cache, no-store, must-revalidate'
        response['Pragma'] = 'no-cache'
        response['Expires'] = '0'
        return response
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


def user_settings(request):
    """用户设置页面"""
    return render(request, 'user/settings.html')


@csrf_exempt
@require_http_methods(["PUT"])
def user_profile_update(request):
    """更新用户资料API"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        nickname = data.get('nickname')
        avatar = data.get('avatar')

        result = client.put('/v1/user/profile', {'nickname': nickname, 'avatar': avatar})

        # 更新 session 中的昵称
        if nickname:
            request.session['nickname'] = nickname

        return JsonResponse({'code': 200, 'data': result, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message, 'success': False}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e), 'success': False}, status=500)


@csrf_exempt
@require_http_methods(["POST"])
def file_upload(request):
    """文件上传API"""
    client = get_client(request)
    try:
        file = request.FILES.get('file')
        if not file:
            return JsonResponse({'success': False, 'message': '未选择文件'}, status=400)

        # 检查文件类型
        allowed_types = ['image/jpeg', 'image/png', 'image/gif']
        if file.content_type not in allowed_types:
            return JsonResponse({'success': False, 'message': '只支持 JPG、PNG、GIF 格式'}, status=400)

        # 检查文件大小 (最大 5MB)
        if file.size > 5 * 1024 * 1024:
            return JsonResponse({'success': False, 'message': '文件大小不能超过5MB'}, status=400)

        # 调用后端上传接口
        files = {'file': (file.name, file.read(), file.content_type)}
        result = client.upload('/v1/upload', files)

        return JsonResponse({'success': True, 'url': result.get('url')})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'message': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'success': False, 'message': str(e)}, status=500)


@require_http_methods(["GET"])
def user_check_nickname(request):
    """检查昵称是否可用API"""
    nickname = request.GET.get('nickname', '').strip()
    if not nickname:
        return JsonResponse({'available': False, 'message': '请输入昵称'})

    client = get_client(request)
    try:
        result = client.get(f'/v1/user/check-nickname?nickname={nickname}')
        return JsonResponse(result)
    except BackendAPIError as e:
        return JsonResponse({'available': False, 'message': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'available': False, 'message': str(e)}, status=500)
