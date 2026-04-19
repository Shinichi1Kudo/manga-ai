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
            return JsonResponse({'data': result})
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

        # 为每个角色获取所有服装（每个服装的最新版本）
        for role in roles:
            try:
                role['assets'] = client.get(f'/v1/assets/role/{role["id"]}/clothings')
            except:
                role['assets'] = []

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


def api_progress(request, series_id):
    """获取处理进度 - AJAX 接口"""
    client = BackendClient()
    try:
        result = client.get(f'/v1/series/{series_id}/progress')
        return JsonResponse(result)
    except BackendAPIError as e:
        return JsonResponse({'error': e.message}, status=500)
