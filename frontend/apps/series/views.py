from django.shortcuts import render, redirect
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods
from django.views.decorators.csrf import csrf_exempt
from django.contrib import messages
import json

from api.backend_client import BackendClient, BackendAPIError


def series_list(request):
    """首页 - 系列列表"""
    if request.headers.get('X-Requested-With') == 'XMLHttpRequest' or request.path.startswith('/api/'):
        # API 请求 - 支持分页
        page = request.GET.get('page', 1)
        page_size = request.GET.get('pageSize', 9)
        client = BackendClient()
        try:
            result = client.get(f'/v1/series/list?page={page}&pageSize={page_size}')
            response = JsonResponse({'data': result})
            # 禁止缓存
            response['Cache-Control'] = 'no-cache, no-store, must-revalidate'
            response['Pragma'] = 'no-cache'
            response['Expires'] = '0'
            return response
        except BackendAPIError as e:
            return JsonResponse({'data': [], 'error': e.message}, status=500)

    # 获取第一页系列，提取处理中的系列ID
    client = BackendClient()
    try:
        result = client.get('/v1/series/list?page=1&pageSize=100')
        series_list = result.get('list', [])
        # 只提取状态为"处理中"(status=0)的系列ID
        processing_ids = [s['id'] for s in series_list if s.get('status') == 0]
    except BackendAPIError:
        series_list = []
        processing_ids = []

    return render(request, 'series/series_list.html', {
        'processing_series_ids': json.dumps(processing_ids),
    })


@csrf_exempt
def series_init(request):
    """系列初始化页面"""
    if request.method == 'POST':
        series_name = request.POST.get('series_name', '').strip()
        outline = request.POST.get('outline', '').strip()
        background = request.POST.get('background', '').strip()
        characters_json = request.POST.get('characters_json', '').strip()

        # 构建表单数据，用于验证失败时回填
        form_data = {
            'series_name': series_name,
            'outline': outline,
            'background': background,
            'characters_json': characters_json,
        }

        # 验证必填字段
        if not series_name:
            messages.error(request, '请输入系列名称')
            return render(request, 'series/series_init.html', {'form_data': form_data})
        if not outline:
            messages.error(request, '请输入剧本大纲')
            return render(request, 'series/series_init.html', {'form_data': form_data})
        if not characters_json:
            messages.error(request, '请至少添加一个角色')
            return render(request, 'series/series_init.html', {'form_data': form_data})

        client = BackendClient()
        try:
            result = client.post('/v1/series/init', {
                'seriesName': series_name,
                'outline': outline,
                'background': background,
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
    client = BackendClient()
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
    client = BackendClient()
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
    client = BackendClient()
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
    client = BackendClient()
    try:
        client.post(f'/v1/series/{series_id}/lock')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["PUT"])
def series_update(request, series_id):
    """更新系列信息"""
    client = BackendClient()
    try:
        data = json.loads(request.body)
        client.put(f'/v1/series/{series_id}', {
            'seriesName': data.get('seriesName'),
            'outline': data.get('outline'),
            'background': data.get('background'),
        })
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


def api_progress(request, series_id):
    """获取处理进度 - AJAX 接口"""
    client = BackendClient()
    try:
        result = client.get(f'/v1/series/{series_id}/progress')
        return JsonResponse(result)
    except BackendAPIError as e:
        return JsonResponse({'error': e.message}, status=500)


@csrf_exempt
@require_http_methods(["DELETE"])
def series_delete(request, series_id):
    """删除系列（移入回收站）"""
    client = BackendClient()
    try:
        client.delete(f'/v1/series/{series_id}')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


def trash_page(request):
    """回收站页面"""
    client = BackendClient()
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
    client = BackendClient()
    try:
        client.post(f'/v1/series/{series_id}/restore')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["DELETE"])
def series_permanent_delete(request, series_id):
    """彻底删除系列"""
    client = BackendClient()
    try:
        client.delete(f'/v1/series/{series_id}/permanent')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


# ==================== 剧集管理相关视图 ====================

def select_series_for_episode(request):
    """选择系列页面 - 用于剧集制作"""
    client = BackendClient()
    try:
        result = client.get('/v1/series/list?page=1&pageSize=100')
        series_list = result.get('list', [])
        # 过滤已锁定的系列
        locked_series = [s for s in series_list if s.get('status') == 2]

        # 获取每个系列的角色
        for series in locked_series:
            try:
                roles = client.get(f'/v1/roles/series/{series["id"]}')
                series['roles'] = roles
            except:
                series['roles'] = []
    except BackendAPIError as e:
        messages.error(request, f'获取系列列表失败: {e.message}')
        locked_series = []

    return render(request, 'episode/select_series.html', {
        'locked_series': locked_series,
    })


def episode_list(request, series_id):
    """剧集列表页面"""
    client = BackendClient()
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
    client = BackendClient()
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
            return redirect('series:episode_list', series_id=series_id)
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


def episode_detail(request, series_id, episode_id):
    """剧集详情/分镜审核页面"""
    client = BackendClient()
    try:
        series = client.get(f'/v1/series/{series_id}')
        episode = client.get(f'/v1/episodes/{episode_id}')
        shots = client.get(f'/v1/shots/episode/{episode_id}')
    except BackendAPIError as e:
        messages.error(request, f'获取剧集信息失败: {e.message}')
        return redirect('series:episode_list', series_id=series_id)

    return render(request, 'episode/episode_detail.html', {
        'series': series,
        'episode': episode,
        'shots': shots,
        'series_id': series_id,
        'episode_id': episode_id,
    })


@csrf_exempt
@require_http_methods(["POST"])
def shot_generate_video(request, shot_id):
    """生成单个分镜视频"""
    client = BackendClient()
    try:
        client.post(f'/v1/shots/{shot_id}/generate')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def episode_generate_videos(request, episode_id):
    """批量生成剧集的所有分镜视频"""
    client = BackendClient()
    try:
        client.post(f'/v1/shots/episode/{episode_id}/generate')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def shot_review(request, shot_id):
    """审核分镜"""
    client = BackendClient()
    try:
        data = json.loads(request.body)
        client.post(f'/v1/shots/{shot_id}/review', {
            'approved': data.get('approved', True),
            'comment': data.get('comment', ''),
        })
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


def episode_progress(request, episode_id):
    """获取剧集进度 - AJAX接口"""
    client = BackendClient()
    try:
        result = client.get(f'/v1/episodes/{episode_id}/progress')
        return JsonResponse(result)
    except BackendAPIError as e:
        return JsonResponse({'error': e.message}, status=500)

