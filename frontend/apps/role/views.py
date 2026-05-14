import json
from django.shortcuts import render
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods
from django.views.decorators.csrf import csrf_exempt

from api.backend_client import BackendClient, BackendAPIError


def get_client(request):
    """获取带认证Token的BackendClient"""
    token = request.session.get('token')
    return BackendClient(token=token)


def role_detail(request, role_id):
    """角色详情页面"""
    client = get_client(request)
    try:
        role = client.get(f'/v1/roles/{role_id}')
        assets = client.get(f'/v1/assets/role/{role_id}')
    except BackendAPIError as e:
        # 如果是API请求，返回JSON
        if request.headers.get('X-Requested-With') == 'XMLHttpRequest' or request.path.startswith('/api/'):
            return JsonResponse({'success': False, 'error': e.message}, status=400)
        return render(request, 'error.html', {'message': e.message})

    # 如果是API请求，返回JSON
    if request.headers.get('X-Requested-With') == 'XMLHttpRequest' or request.path.startswith('/api/'):
        return JsonResponse({'success': True, 'data': role})

    return render(request, 'role/role_detail.html', {
        'role': role,
        'assets': assets,
    })


@csrf_exempt
@require_http_methods(["POST"])
def role_create(request, series_id):
    """创建角色"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        data['seriesId'] = series_id
        result = client.post('/v1/roles', data)
        return JsonResponse({'success': True, 'roleId': result})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["DELETE"])
def role_delete(request, role_id):
    """删除角色"""
    client = get_client(request)
    try:
        client.delete(f'/v1/roles/{role_id}')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def role_confirm(request, role_id):
    """确认角色"""
    client = get_client(request)
    try:
        client.post(f'/v1/roles/{role_id}/confirm')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def role_regenerate(request, role_id):
    """重新生成角色图片"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        result = client.post(f'/v1/roles/{role_id}/regenerate', {
            'viewTypes': data.get('viewTypes', []),
            'clothingId': data.get('clothingId'),
            'modifiedPrompt': data.get('modifiedPrompt', ''),
            'originalPrompt': data.get('originalPrompt', ''),
            'keepSeed': data.get('keepSeed', True),
            'isNewClothing': data.get('isNewClothing', False),
            'clothingName': data.get('clothingName', ''),
            'referenceImageUrl': data.get('referenceImageUrl', ''),
            'aspectRatio': data.get('aspectRatio'),
            'quality': data.get('quality'),
            'styleKeywords': data.get('styleKeywords', ''),
            'detailedView': data.get('detailedView', False),
        })
        # result now contains: {clothingId, version, assetId}
        return JsonResponse({'success': True, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["PUT"])
def role_update(request, role_id):
    """更新角色属性"""
    client = get_client(request)
    try:
        data = json.loads(request.body)
        result = client.put(f'/v1/roles/{role_id}', data)
        return JsonResponse({'success': True, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def role_unlock(request, role_id):
    """解锁角色（恢复为待审核状态）"""
    client = get_client(request)
    try:
        client.post(f'/v1/roles/{role_id}/unlock')
        return JsonResponse({
            'success': True,
            'roleStatus': 1,
            'roleStatusDesc': '待审核',
            'seriesStatus': 1,
            'seriesStatusDesc': '待审核',
        })
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)
