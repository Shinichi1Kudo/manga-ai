from django.shortcuts import render
from django.http import JsonResponse, FileResponse
from django.views.decorators.http import require_http_methods
from django.views.decorators.csrf import csrf_exempt
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import threading
import time

from api.backend_client import BackendClient, BackendAPIError

_CACHE_TTL_SECONDS = 30
_CACHE_LOCK = threading.Lock()
_CACHE = {}


def _cache_key(request, endpoint):
    token = request.session.get('token') or ''
    return f'{token}:{endpoint}'


def _cache_key_for_token(token, endpoint):
    return f'{token or ""}:{endpoint}'


def _get_cached(request, endpoint, fetcher):
    return _get_cached_for_key(_cache_key(request, endpoint), fetcher)


def _get_cached_for_token(token, endpoint, fetcher):
    return _get_cached_for_key(_cache_key_for_token(token, endpoint), fetcher)


def _get_cached_for_key(key, fetcher):
    now = time.monotonic()
    with _CACHE_LOCK:
        cached = _CACHE.get(key)
        if cached and cached['expires_at'] > now:
            return cached['data']

    data = fetcher()
    with _CACHE_LOCK:
        _CACHE[key] = {
            'data': data,
            'expires_at': now + _CACHE_TTL_SECONDS,
        }
    return data


def get_client(request):
    """获取带认证Token的BackendClient"""
    token = request.session.get('token')
    return BackendClient(token=token)


def asset_library_page(request):
    """资产库页面"""
    return render(request, 'asset/library.html', {
        'locked_series': [],
    })


def get_locked_series(request):
    """API: 获取已锁定的系列列表"""
    client = get_client(request)
    try:
        locked_series = _get_cached(request, '/v1/series/locked', lambda: client.get('/v1/series/locked'))
        return JsonResponse({'success': True, 'data': locked_series})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


def get_series_assets(request, series_id):
    """API: 获取系列的所有资产（角色资产按系列，场景/道具资产展示全部）"""
    client = get_client(request)
    token = request.session.get('token')
    try:
        # 获取系列信息
        series = _get_cached(request, f'/v1/series/{series_id}', lambda: client.get(f'/v1/series/{series_id}'))
        # 获取角色列表
        roles = _get_cached(request, f'/v1/roles/series/{series_id}', lambda: client.get(f'/v1/roles/series/{series_id}') or [])

        # 收集所有角色资产
        role_assets = []

        def fetch_role_assets(role):
            role_id = role["id"]
            role_client = BackendClient(token=token)
            endpoint = f'/v1/assets/role/{role_id}'
            assets = _get_cached_for_token(token, endpoint, lambda: role_client.get(endpoint) or [])
            # 只取激活的资产
            active_assets = [a for a in assets if a.get('isActive') == 1 or a.get('isActive') == True]

            # 按clothingId分组
            clothing_map = {}
            for asset in active_assets:
                cid = asset.get('clothingId', 1)
                if cid not in clothing_map:
                    clothing_map[cid] = {
                        'clothingId': cid,
                        'clothingName': asset.get('clothingName', f'服装{cid}'),
                        'assets': []
                    }
                clothing_map[cid]['assets'].append(asset)

            return {
                'roleId': role['id'],
                'roleName': role['roleName'],
                'roleCode': role.get('roleCode', ''),
                'clothings': list(clothing_map.values()),
                'hasAssets': len(active_assets) > 0  # 标记是否有资产
            }

        if roles:
            max_workers = min(8, len(roles))
            with ThreadPoolExecutor(max_workers=max_workers) as executor:
                future_map = {executor.submit(fetch_role_assets, role): index for index, role in enumerate(roles)}
                indexed_results = []
                for future in as_completed(future_map):
                    indexed_results.append((future_map[future], future.result()))
            role_assets = [item[1] for item in sorted(indexed_results, key=lambda item: item[0])]

        return JsonResponse({
            'success': True,
            'data': {
                'series': series,
                'roles': role_assets
            }
        })
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'success': False, 'error': str(e)}, status=500)


def get_all_locked_scenes(request):
    """API: 获取所有已锁定系列的场景资产"""
    client = get_client(request)
    try:
        # 获取所有已锁定的系列
        locked_series = client.get('/v1/series/locked') or []

        all_scenes = []
        for series in locked_series:
            series_id = series.get('id')
            series_name = series.get('seriesName', '')
            scenes = client.get(f'/v1/scenes/series/{series_id}') or []
            # 只取已锁定的场景，并添加系列名称
            for scene in scenes:
                if scene.get('status') == 3:
                    scene['seriesName'] = series_name
                    all_scenes.append(scene)

        return JsonResponse({
            'success': True,
            'data': all_scenes
        })
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'success': False, 'error': str(e)}, status=500)


def get_series_scenes(request, series_id):
    """API: 获取指定系列的场景资产"""
    client = get_client(request)
    try:
        # 获取系列信息
        series = _get_cached(request, f'/v1/series/{series_id}', lambda: client.get(f'/v1/series/{series_id}'))
        series_name = series.get('seriesName', '') if series else ''

        scenes = _get_cached(request, f'/v1/scenes/series/{series_id}', lambda: client.get(f'/v1/scenes/series/{series_id}') or [])
        # 只取已锁定的场景，并添加系列名称
        locked_scenes = []
        for scene in scenes:
            if scene.get('status') == 3:
                scene['seriesName'] = series_name
                locked_scenes.append(scene)

        return JsonResponse({
            'success': True,
            'data': locked_scenes
        })
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'success': False, 'error': str(e)}, status=500)


def get_all_locked_props(request):
    """API: 获取所有已锁定系列的道具资产"""
    client = get_client(request)
    try:
        # 获取所有已锁定的系列
        locked_series = client.get('/v1/series/locked') or []

        all_props = []
        for series in locked_series:
            series_id = series.get('id')
            series_name = series.get('seriesName', '')
            props = client.get(f'/v1/props/series/{series_id}') or []
            # 只取已锁定的道具，并添加系列名称
            for prop in props:
                if prop.get('status') == 3:
                    prop['seriesName'] = series_name
                    all_props.append(prop)

        return JsonResponse({
            'success': True,
            'data': all_props
        })
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'success': False, 'error': str(e)}, status=500)


def get_series_props(request, series_id):
    """API: 获取指定系列的道具资产"""
    client = get_client(request)
    try:
        # 获取系列信息
        series = _get_cached(request, f'/v1/series/{series_id}', lambda: client.get(f'/v1/series/{series_id}'))
        series_name = series.get('seriesName', '') if series else ''

        props = _get_cached(request, f'/v1/props/series/{series_id}', lambda: client.get(f'/v1/props/series/{series_id}') or [])
        # 只取已锁定的道具，并添加系列名称
        locked_props = []
        for prop in props:
            if prop.get('status') == 3:
                prop['seriesName'] = series_name
                locked_props.append(prop)

        return JsonResponse({
            'success': True,
            'data': locked_props
        })
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'success': False, 'error': str(e)}, status=500)


def asset_detail(request, asset_id):
    """资产详情"""
    client = get_client(request)
    try:
        asset = client.get(f'/v1/assets/{asset_id}')
    except BackendAPIError as e:
        return JsonResponse({'error': e.message}, status=400)

    return JsonResponse(asset)


def asset_prompt(request, asset_id):
    """获取资产生成时使用的提示词"""
    client = get_client(request)
    try:
        prompt = client.get(f'/v1/assets/{asset_id}/prompt')
        return JsonResponse({'data': prompt})
    except BackendAPIError as e:
        return JsonResponse({'data': None, 'error': e.message}, status=400)


def asset_download(request, asset_id):
    """下载资产"""
    client = get_client(request)
    try:
        asset = client.get(f'/v1/assets/{asset_id}')
        file_path = asset.get('transparent_path') or asset.get('file_path')

        if file_path:
            import os
            if os.path.exists(file_path):
                return FileResponse(
                    open(file_path, 'rb'),
                    as_attachment=True,
                    filename=asset.get('fileName', 'asset.png')
                )
    except BackendAPIError as e:
        pass

    return JsonResponse({'error': 'File not found'}, status=404)


@csrf_exempt
@require_http_methods(["POST"])
def set_default_clothing(request, role_id, clothing_id):
    """设置默认服装"""
    client = get_client(request)
    try:
        client.post(f'/v1/assets/role/{role_id}/default/{clothing_id}')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def rollback_asset(request, asset_id):
    """回滚到指定版本的资产"""
    client = get_client(request)
    try:
        client.post(f'/v1/assets/{asset_id}/rollback')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def rename_clothing(request, role_id, clothing_id):
    """重命名服装"""
    client = get_client(request)
    try:
        import json
        data = json.loads(request.body)
        clothing_name = data.get('clothingName', '')
        client.put(f'/v1/assets/role/{role_id}/clothing/{clothing_id}/name', {'clothingName': clothing_name})
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["DELETE"])
def delete_clothing(request, role_id, clothing_id):
    """删除服装"""
    client = get_client(request)
    try:
        client.delete(f'/v1/assets/role/{role_id}/clothing/{clothing_id}')
        return JsonResponse({'success': True})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


def get_clothing_versions(request, role_id, clothing_id):
    """获取服装的所有版本"""
    client = get_client(request)
    try:
        # 获取所有资产，然后过滤指定服装
        all_assets = client.get(f'/v1/assets/role/{role_id}/all')
        versions = [a for a in all_assets if a.get('clothingId') == int(clothing_id)]
        # 按版本降序排列
        versions.sort(key=lambda x: x.get('version', 0), reverse=True)
        return JsonResponse({'data': versions})
    except BackendAPIError as e:
        return JsonResponse({'data': [], 'error': e.message}, status=400)


def asset_library(request, series_id):
    """资产库页面"""
    client = get_client(request)
    try:
        series = client.get(f'/v1/series/{series_id}')
        roles = client.get(f'/v1/roles/series/{series_id}')

        all_assets = []
        for role in roles:
            assets = client.get(f'/v1/assets/role/{role["id"]}')
            for asset in assets:
                asset['role_name'] = role['role_name']
                all_assets.append(asset)
    except BackendAPIError as e:
        return render(request, 'error.html', {'message': e.message})

    return render(request, 'asset/asset_library.html', {
        'series': series,
        'assets': all_assets,
        'series_id': series_id,
    })


def get_series_video_assets(request, series_id):
    """API: 获取系列影视资产"""
    client = get_client(request)
    try:
        data = _get_cached(request, f'/v1/series/{series_id}/video-assets', lambda: client.get(f'/v1/series/{series_id}/video-assets'))
        return JsonResponse({'success': True, 'data': data})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)
    except Exception as e:
        return JsonResponse({'success': False, 'error': str(e)}, status=500)


def get_series_role_assets(request, series_id):
    """API: 获取系列所有角色的服装资产（用于@提及）"""
    client = get_client(request)
    try:
        data = client.get(f'/v1/assets/series/{series_id}/role-assets')
        return JsonResponse({'code': 200, 'message': 'success', 'data': data, 'success': True})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message, 'data': None, 'success': False}, status=400)
    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e), 'data': None, 'success': False}, status=500)
