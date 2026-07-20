from django.shortcuts import render, redirect
from django.http import JsonResponse, HttpResponse, HttpResponseForbidden
from django.views.decorators.http import require_http_methods
from django.views.decorators.csrf import csrf_exempt, ensure_csrf_cookie
from django.contrib import messages
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from urllib.parse import urlencode
import json
import requests
from django.core.serializers.json import DjangoJSONEncoder
import logging

from api.backend_client import BackendClient, BackendAPIError


logger = logging.getLogger(__name__)

COMMERCE_ASPECT_RATIOS = ('9:16', '3:4', '1:1', '16:9')
COMMERCE_ASPECT_RATIO_OPTIONS = (
    {'value': '9:16', 'label': '9:16 - 抖音 / 快手 / 视频号全屏竖屏'},
    {'value': '3:4', 'label': '3:4 - 真人模特 / 服装试穿竖图'},
    {'value': '1:1', 'label': '1:1 - 商品卡片 / 方图投放素材'},
    {'value': '16:9', 'label': '16:9 - 横屏展示 / 店铺详情素材'},
)
DEFAULT_COMMERCE_ASPECT_RATIO = '9:16'
DEFAULT_COMMERCE_MODEL_ASPECT_RATIO = '16:9'
COMMERCE_TRY_ON_SHOTS = (
    {'value': '全身站立', 'duration': '约4秒'},
    {'value': '转身展示', 'duration': '约5秒'},
    {'value': '走动展示', 'duration': '约5秒'},
    {'value': '细节展示', 'duration': '约4秒'},
    {'value': '半身近景', 'duration': '约3秒'},
    {'value': '坐姿展示', 'duration': '约4秒'},
    {'value': '抬手展示', 'duration': '约3秒'},
    {'value': '整理衣摆', 'duration': '约3秒'},
    {'value': '拿包通勤', 'duration': '约4秒'},
    {'value': '戴帽展示', 'duration': '约3秒'},
    {'value': '搭配展示', 'duration': '约4秒'},
)
COMMERCE_TRY_ON_SHOT_VALUES = tuple(shot['value'] for shot in COMMERCE_TRY_ON_SHOTS)
COMMERCE_TRY_ON_MAX_SHOTS = 4
COMMERCE_TRY_ON_PERFORMANCE_STYLES = (
    {'value': '自信大片', 'description': '张扬大方、主动掌控镜头，适合时装大片和高级街拍。'},
    {'value': '甜酷活力', 'description': '明亮有能量，动作更轻快，适合年轻女装和日常出街。'},
    {'value': '高冷超模', 'description': '眼神更锋利、步伐更利落，适合廓形感和秀场感服装。'},
    {'value': '松弛度假', 'description': '自然舒展、风吹发丝和衣摆，适合旅拍、海边和户外场景。'},
    {'value': '明朗亲和', 'description': '笑容更自然亲切，适合直播间、通勤和日常种草氛围。'},
)
COMMERCE_TRY_ON_PERFORMANCE_STYLE_VALUES = tuple(style['value'] for style in COMMERCE_TRY_ON_PERFORMANCE_STYLES)
COMMERCE_TRY_ON_DEFAULT_PERFORMANCE_STYLE = '自信大片'
COMMERCE_TRY_ON_PERFORMANCE_BOOSTS = (
    {'value': '轻跑靠近镜头', 'hint': '两三步轻跑入画，发丝和衣摆有风感。'},
    {'value': '快走急停回看', 'hint': '有气场快走后稳稳停住，再回看镜头。'},
    {'value': '旋身甩发', 'hint': '肩线带动旋身，发丝甩出动态弧线。'},
    {'value': '主动靠近镜头', 'hint': '从中景走近镜头，制造强镜头互动。'},
    {'value': '回头挑眉', 'hint': '回头看镜头，眉峰轻挑，有短暂停顿。'},
    {'value': '大胆回看镜头', 'hint': '眼神更直接，自信停留后再移开。'},
    {'value': '自信微笑', 'hint': '嘴角自然上扬，不僵硬不假笑。'},
    {'value': '开朗笑起来', 'hint': '笑容更明亮，适合活力和日常风格。'},
    {'value': '爽朗大笑', 'hint': '短暂真实大笑，动作外放但不夸张。'},
)
COMMERCE_TRY_ON_PERFORMANCE_BOOST_VALUES = tuple(boost['value'] for boost in COMMERCE_TRY_ON_PERFORMANCE_BOOSTS)
COMMERCE_TRY_ON_MAX_PERFORMANCE_BOOSTS = 3
COMMERCE_TRY_ON_VIDEO_DURATIONS = tuple(range(4, 16))
COMMERCE_TRY_ON_DEFAULT_DURATION = 8
COMMERCE_TRY_ON_VIDEO_RESOLUTIONS = (
    {'value': '480p', 'label': '高清', 'supported_models': ('seedance-2.0', 'seedance-2.0-fast')},
    {'value': '720p', 'label': '超清', 'supported_models': ('seedance-2.0', 'seedance-2.0-fast', 'kling-v3-omni')},
    {'value': '1080p', 'label': '4K', 'supported_models': ('seedance-2.0', 'kling-v3-omni')},
)
COMMERCE_TRY_ON_VIDEO_RESOLUTION_VALUES = tuple(option['value'] for option in COMMERCE_TRY_ON_VIDEO_RESOLUTIONS)
COMMERCE_TRY_ON_DEFAULT_RESOLUTION = '480p'
COMMERCE_TRY_ON_VIDEO_MODELS = (
    {'value': 'seedance-2.0', 'label': 'Seedance 2.0 VIP'},
    {'value': 'seedance-2.0-fast', 'label': 'Seedance 2.0 Fast VIP'},
    {'value': 'kling-v3-omni', 'label': 'Kling v3 Omni'},
)
COMMERCE_TRY_ON_VIDEO_MODEL_VALUES = tuple(option['value'] for option in COMMERCE_TRY_ON_VIDEO_MODELS)
COMMERCE_TRY_ON_DEFAULT_VIDEO_MODEL = 'seedance-2.0'
COMMERCE_TRY_ON_SUPPORTED_RESOLUTIONS_BY_MODEL = {
    model['value']: tuple(
        resolution['value']
        for resolution in COMMERCE_TRY_ON_VIDEO_RESOLUTIONS
        if model['value'] in resolution['supported_models']
    )
    for model in COMMERCE_TRY_ON_VIDEO_MODELS
}
COMMERCE_TRY_ON_SCENES = (
    '室内全身镜', '棚拍场景', '街拍场景', '户外自然光', '商场试衣间', '直播间背景',
    '田间自然', '海边旅拍', '海边氛围', '山野公路', '湖畔草地', '花房庭院', '咖啡店外',
    '复古街区', '城市夜景', '天台露台', '艺术馆', '酒店民宿', '地铁通勤',
    '校园林荫', '公园绿荫',
)
COMMERCE_MODEL_TYPES_BY_GENDER = {
    '女': ('年轻美女', '成熟女性', '中年女性', '老年女性', '高级感女模特', '亲和素人女性'),
    '男': ('年轻帅哥', '成熟男性', '中年男性', '老年男性', '高级感男模特', '亲和素人男性'),
    '儿童': ('小女孩', '小男孩', '中性儿童'),
}
COMMERCE_MODEL_ETHNICITIES = ('中国', '华人', '东亚', '日韩', '亚洲', '东南亚', '南亚', '欧美', '拉美', '中东', '非洲', '混血感')
COMMERCE_MODEL_BODY_PROFILES = ('标准匀称', '纤细修长', '高挑模特', '微胖亲和', '丰满曲线', '大码胖模特')
DEFAULT_COMMERCE_MODEL_BODY_PROFILE = '标准匀称'
COMMERCE_DAILY_SCENE_TYPES = (
    '品牌场景', '直播间背景', '棚拍场景', '商场试衣间', '室内全身镜',
)
COMMERCE_ATMOSPHERE_SCENE_TYPES = (
    '街拍场景', '田间自然', '海边旅拍', '海边氛围', '山野公路', '湖畔草地',
    '花房庭院', '咖啡店外', '复古街区', '城市夜景', '天台露台',
    '艺术馆', '酒店民宿', '地铁通勤', '校园林荫', '公园绿荫',
)
COMMERCE_SCENE_TYPE_GROUPS = (
    {
        'title': '基础商拍',
        'description': '适合商品页、直播间、棚拍和试衣间，出图稳定、主体落位清楚',
        'scene_types': COMMERCE_DAILY_SCENE_TYPES,
    },
    {
        'title': '出片氛围',
        'description': '适合生活方式大片、街拍旅拍、自然光和城市夜景，更强调情绪和画面感',
        'scene_types': COMMERCE_ATMOSPHERE_SCENE_TYPES,
    },
)
COMMERCE_SCENE_TYPES = COMMERCE_DAILY_SCENE_TYPES + COMMERCE_ATMOSPHERE_SCENE_TYPES
COMMERCE_CUSTOM_SCENE_FILTER = '其他场景'
COMMERCE_SCENE_RESOLUTIONS = ('2K', '3K', '4K')
COMMERCE_SCENE_TIME_MOODS = ('day', 'night')
COMMERCE_SCENE_CROWD_MODES = ('empty', 'ambient')
COMMERCE_PRODUCT_IMAGE_USAGES = ('主图', '细节图', '上身参考')
COMMERCE_PRODUCT_IMAGE_ALLOWED_TYPES = {'image/jpeg', 'image/png', 'image/webp'}
COMMERCE_PRODUCT_IMAGE_ALLOWED_EXTENSIONS = ('.jpg', '.jpeg', '.png', '.webp')
COMMERCE_PRODUCT_IMAGE_MAX_SIZE = 10 * 1024 * 1024
COMMERCE_PRODUCT_IMAGE_MAX_COUNT = 5


def _commerce_scene_filter_categories(scenes):
    """构建场景图工作区筛选分类：全部、系统场景、其他场景。"""
    return ['全部', *COMMERCE_SCENE_TYPES, COMMERCE_CUSTOM_SCENE_FILTER]


def _redirect_to_commerce_model_detail(model_id):
    try:
        model_id = int(model_id)
    except (TypeError, ValueError):
        model_id = 0
    if model_id > 0:
        return redirect('series:commerce_model_detail', model_id=model_id)
    return redirect('series:commerce_series_list')


def _validate_commerce_product_upload(product_image):
    filename = (getattr(product_image, 'name', '') or '').lower()
    content_type = getattr(product_image, 'content_type', '') or ''
    if content_type not in COMMERCE_PRODUCT_IMAGE_ALLOWED_TYPES and not filename.endswith(COMMERCE_PRODUCT_IMAGE_ALLOWED_EXTENSIONS):
        return '只支持 JPG、PNG、WEBP 格式的商品图片'
    if getattr(product_image, 'size', 0) and product_image.size > COMMERCE_PRODUCT_IMAGE_MAX_SIZE:
        return '商品图片大小不能超过10MB'
    return ''


def _upload_commerce_product_image(client, product_image):
    validation_error = _validate_commerce_product_upload(product_image)
    if validation_error:
        raise ValueError(validation_error)
    files = {
        'file': (
            product_image.name,
            product_image.read(),
            product_image.content_type or 'application/octet-stream',
        )
    }
    upload_result = client.upload('/v1/commerce/products/upload-image', files)
    image_url = (upload_result or {}).get('url', '').strip()
    if not image_url:
        raise ValueError('商品图片上传失败')
    return image_url


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
        for future in as_completed(future_map):
            name = future_map[future]
            results[name] = future.result()
        return results


def _parallel_client_gets(client, endpoints, client_factory=None):
    """使用当前请求的客户端并发加载互不依赖的数据。"""
    if not endpoints:
        return {}

    def fetch(endpoint):
        request_client = client_factory() if client_factory else client
        return request_client.get(endpoint)

    max_workers = min(len(endpoints), 8)
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        future_map = {
            executor.submit(fetch, endpoint): name
            for name, endpoint in endpoints.items()
        }
        results = {}
        for future in as_completed(future_map):
            name = future_map[future]
            results[name] = future.result()
        return results


def _commerce_try_on_requirement_section(requirements, label):
    for section in (requirements or '').replace(';', '；').split('；'):
        section = section.strip()
        if section.startswith(label):
            return section[len(label):].strip()
    return ''


def _commerce_try_on_performance_summary(requirements):
    style = _commerce_try_on_requirement_section(requirements, '表演风格：')
    boosts = _commerce_try_on_requirement_section(requirements, '动作表情：')
    parts = []
    if style:
        parts.append(f'表演风格：{style}')
    if boosts:
        parts.append(f'动作/表情：{boosts}')
    return ' · '.join(parts)


def _enrich_commerce_try_on_project(project):
    project['tryOnPerformanceSummary'] = _commerce_try_on_performance_summary(project.get('tryOnRequirements'))
    return project


def _enrich_commerce_try_on_projects(projects):
    return [_enrich_commerce_try_on_project(project) for project in (projects or [])]


def _attach_commerce_product_try_on_projects(client, products, client_factory=None):
    """并发补齐每个商品的试穿视频项目，单个商品失败不阻塞详情页。"""
    product_list = products or []
    product_map = {
        product.get('id'): product
        for product in product_list
        if product.get('id')
    }
    for product in product_list:
        if not product.get('id'):
            product['tryOnProjects'] = _enrich_commerce_try_on_projects(product.get('tryOnProjects') or [])

    if not product_map:
        return product_list

    def fetch(project_product_id):
        request_client = client_factory() if client_factory else client
        return request_client.get(f'/v1/commerce/products/{project_product_id}/projects?contentType=try_on')

    max_workers = min(len(product_map), 8)
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        future_map = {
            executor.submit(fetch, product_id): product_id
            for product_id in product_map
        }
        for future in as_completed(future_map):
            product_id = future_map[future]
            product = product_map[product_id]
            fallback_try_on_projects = _enrich_commerce_try_on_projects(product.get('tryOnProjects') or [])
            try:
                try_on_projects = future.result()
                product['tryOnProjects'] = _enrich_commerce_try_on_projects(try_on_projects if try_on_projects is not None else [])
            except BackendAPIError as e:
                logger.warning('加载商品试穿视频项目失败 product_id=%s: %s', product_id, e.message)
                product['tryOnProjects'] = fallback_try_on_projects
            except Exception:
                logger.exception('加载商品试穿视频项目异常 product_id=%s', product_id)
                product['tryOnProjects'] = fallback_try_on_projects

    return product_list


def _start_commerce_scene_batch_async(token, series_id, scene_ids):
    """后台触发场景图生成，让页面在落库后立即返回生成中占位。"""
    scene_ids = [int(scene_id) for scene_id in (scene_ids or []) if scene_id]
    if not scene_ids:
        return

    def start_batch():
        try:
            BackendClient(token=token).post(f'/v1/commerce/series/{series_id}/scenes/batch/start', {
                'sceneIds': scene_ids,
            })
        except Exception:
            logger.exception('Failed to start commerce scene batch generation: series_id=%s scene_ids=%s', series_id, scene_ids)

    executor = ThreadPoolExecutor(max_workers=1)
    future = executor.submit(start_batch)
    future.add_done_callback(lambda _: executor.shutdown(wait=False))


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

            # 补充模板需要的默认字段（资产级别的属性，角色本身没有但模板常用作fallback）
            role.setdefault('aspectRatio', '')
            role.setdefault('quality', '')
            role.setdefault('styleKey', '')
            role.setdefault('styleKeywords', '')
            role.setdefault('detailedView', False)
            role.setdefault('faceCloseupView', False)

            # 检查是否有任何成功生成的资产（用于决定是否显示"生成新服装"按钮）
            role['has_successful_asset'] = any(
                a.get('filePath') and a.get('filePath').strip()
                for a in role.get('assets', [])
            )
            role['has_usable_asset'] = any(
                a.get('filePath') and a.get('filePath').strip() and a.get('status') not in (-1, 0)
                for a in role.get('assets', [])
            )
            if not role['has_usable_asset'] and role.get('status', 0) >= 2:
                role['status'] = 1
                role['statusDesc'] = '待审核'
    except BackendAPIError as e:
        messages.error(request, f'获取系列信息失败: {e.message}')
        return redirect('series:list')

    ready_count = sum(1 for r in roles if r.get('status', 0) >= 2 and r.get('has_usable_asset'))
    response = render(request, 'series/series_review.html', {
        'series': series,
        'roles': roles,
        'series_id': series_id,
        'confirmed_count': ready_count,
        'roles_ready_for_lock': ready_count == len(roles) and len(roles) > 0,
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


def commerce_series_list(request):
    """电商带货系列列表"""
    client = get_client(request)
    try:
        series_list_data = client.get('/v1/commerce/series') or []
    except BackendAPIError as e:
        messages.error(request, f'获取电商系列失败: {e.message}')
        series_list_data = []
    return render(request, 'commerce/series_list.html', {
        'commerce_series_list': series_list_data,
    })


def commerce_series_api(request):
    """API: 获取电商系列列表"""
    client = get_client(request)
    try:
        result = client.get('/v1/commerce/series')
        return JsonResponse({'data': result})
    except BackendAPIError as e:
        status = 401 if e.status_code == 401 else 500
        return JsonResponse({'code': e.status_code, 'message': e.message, 'data': []}, status=status)


@csrf_exempt
@require_http_methods(["GET", "POST"])
def commerce_series_create(request):
    """创建电商系列"""
    if request.method == 'POST':
        name = request.POST.get('name', '').strip()
        default_aspect_ratio = request.POST.get('default_aspect_ratio', DEFAULT_COMMERCE_ASPECT_RATIO).strip()
        if default_aspect_ratio not in COMMERCE_ASPECT_RATIOS:
            default_aspect_ratio = DEFAULT_COMMERCE_ASPECT_RATIO
        form_data = {'name': name, 'default_aspect_ratio': default_aspect_ratio}
        if not name:
            messages.error(request, '请输入电商系列名称')
            return render(request, 'commerce/series_create.html', {'form_data': form_data})

        client = get_client(request)
        try:
            result = client.post('/v1/commerce/series', {
                'name': name,
                'category': 'clothing',
                'visualStyle': 'realistic',
                'defaultAspectRatio': default_aspect_ratio,
                'enabledContentTypes': ['talking', 'try_on'],
            })
            messages.success(request, f'电商系列 "{name}" 创建成功')
            return redirect('series:commerce_series_detail', series_id=result.get('id'))
        except BackendAPIError as e:
            messages.error(request, f'创建失败: {e.message}')
            return render(request, 'commerce/series_create.html', {'form_data': form_data})

    return render(request, 'commerce/series_create.html', {
        'form_data': {'default_aspect_ratio': DEFAULT_COMMERCE_ASPECT_RATIO}
    })


def commerce_series_detail(request, series_id):
    """电商系列详情"""
    client = get_client(request)
    try:
        series = client.get(f'/v1/commerce/series/{series_id}')
        models = client.get(f'/v1/commerce/series/{series_id}/models')
    except BackendAPIError as e:
        messages.error(request, f'获取电商系列失败: {e.message}')
        return redirect('series:commerce_series_list')

    return render(request, 'commerce/series_detail.html', {
        'commerce_series': series,
        'models': [_enrich_commerce_model(model) for model in (models or [])],
        'model_created': request.GET.get('created_model') == '1',
        'created_model_id': request.GET.get('created_model_id', ''),
    })


def commerce_scene_workspace(request, series_id):
    """电商场景图工作区"""
    client = get_client(request)
    try:
        series = client.get(f'/v1/commerce/series/{series_id}')
        scenes = client.get(f'/v1/commerce/series/{series_id}/scenes')
    except BackendAPIError as e:
        messages.error(request, f'获取场景库失败: {e.message}')
        return redirect('series:commerce_series_detail', series_id=series_id)

    return render(request, 'commerce/scene_workspace.html', {
        'commerce_series': series,
        'scenes': scenes or [],
        'scene_filter_categories': _commerce_scene_filter_categories(scenes),
        'scene_types': COMMERCE_SCENE_TYPES,
        'scene_type_groups': COMMERCE_SCENE_TYPE_GROUPS,
        'scene_resolutions': COMMERCE_SCENE_RESOLUTIONS,
        'aspect_ratios': COMMERCE_ASPECT_RATIO_OPTIONS,
    })


def commerce_model_library(request, series_id):
    """电商模特库工作区"""
    client = get_client(request)
    try:
        series = client.get(f'/v1/commerce/series/{series_id}')
        models = client.get(f'/v1/commerce/series/{series_id}/model-library')
    except BackendAPIError as e:
        messages.error(request, f'获取模特库失败: {e.message}')
        return redirect('series:commerce_series_detail', series_id=series_id)

    enriched_models = [_enrich_commerce_model(model) for model in (models or [])]
    return render(request, 'commerce/model_library.html', {
        'commerce_series': series,
        'library_models': enriched_models,
        'model_library_groups': _group_commerce_models_by_series(enriched_models, series.get('id')),
    })


@csrf_exempt
@require_http_methods(["POST"])
def commerce_model_library_reuse(request, series_id, source_model_id):
    """沿用模特库中的锁定模特到当前系列"""
    client = get_client(request)
    try:
        result = client.post(f'/v1/commerce/series/{series_id}/models/reuse/{source_model_id}')
        model_name = result.get('name') or '模特'
        messages.success(request, f'已沿用模特 "{model_name}" 到当前系列')
    except BackendAPIError as e:
        messages.error(request, f'沿用模特失败: {e.message}')
        return redirect('series:commerce_model_library', series_id=series_id)
    return redirect('series:commerce_series_detail', series_id=series_id)


@csrf_exempt
@require_http_methods(["POST"])
def commerce_scene_batch_create(request, series_id):
    """批量生成电商场景图"""
    scene_types = [
        scene for scene in request.POST.getlist('scene_types')
        if scene in COMMERCE_SCENE_TYPES
    ]
    custom_scenes = [
        scene.strip() for scene in request.POST.getlist('custom_scenes')
        if scene and scene.strip()
    ]
    aspect_ratio = request.POST.get('aspect_ratio', '16:9').strip()
    if aspect_ratio not in ('16:9', '9:16', '3:4', '1:1'):
        aspect_ratio = '16:9'
    resolution = request.POST.get('resolution', '2K').strip().upper()
    if resolution not in COMMERCE_SCENE_RESOLUTIONS:
        resolution = '2K'
    time_mood = request.POST.get('time_mood', 'day').strip()
    if time_mood not in COMMERCE_SCENE_TIME_MOODS:
        time_mood = 'day'
    crowd_mode = request.POST.get('crowd_mode', 'empty').strip()
    if crowd_mode not in COMMERCE_SCENE_CROWD_MODES:
        crowd_mode = 'empty'
    try:
        count_per_scene = int(request.POST.get('count_per_scene', '1'))
    except ValueError:
        count_per_scene = 1
    count_per_scene = max(1, min(count_per_scene, 4))
    brand_scene_name = request.POST.get('brand_scene_name', '').strip()
    payload = {
        'sceneTypes': scene_types,
        'customScenes': custom_scenes,
        'aspectRatio': aspect_ratio,
        'resolution': resolution,
        'countPerScene': count_per_scene,
        'timeMood': time_mood,
        'crowdMode': crowd_mode,
    }
    if '品牌场景' in scene_types and brand_scene_name:
        payload['brandSceneName'] = brand_scene_name
    client = get_client(request)
    try:
        prepared_scenes = client.post(f'/v1/commerce/series/{series_id}/scenes/batch/prepare', payload)
        scene_ids = [
            int(scene.get('id'))
            for scene in (prepared_scenes or [])
            if scene.get('id')
        ]
        if scene_ids:
            _start_commerce_scene_batch_async(request.session.get('token'), series_id, scene_ids)
        messages.success(request, '场景图已提交生成')
    except BackendAPIError as e:
        messages.error(request, f'生成场景图失败: {e.message}')
    return redirect('series:commerce_scene_workspace', series_id=series_id)


def _enrich_commerce_model(model):
    model = dict(model or {})
    parts = [part.strip() for part in (model.get('genderAge') or '').split('/')]
    model['gender'] = parts[0] if len(parts) > 0 and parts[0] else ''
    model['modelType'] = parts[1] if len(parts) > 1 and parts[1] else ''
    model['ethnicity'] = parts[2] if len(parts) > 2 and parts[2] else ''
    if not model.get('modelAspectRatio'):
        model['modelAspectRatio'] = DEFAULT_COMMERCE_MODEL_ASPECT_RATIO
    model['createdAtDisplay'] = _format_commerce_model_created_at(model.get('createdAt'))
    return model


def _group_commerce_models_by_series(models, target_series_id=None):
    groups = []
    group_index = {}
    for model in models or []:
        series_id = model.get('seriesId')
        series_name = model.get('seriesName') or '未知系列'
        key = series_id if series_id is not None else f'unknown:{series_name}'
        if key not in group_index:
            group_index[key] = {
                'seriesId': series_id,
                'seriesName': series_name,
                'models': [],
                'count': 0,
                'inTargetSeries': False,
            }
            groups.append(group_index[key])
        group = group_index[key]
        group['models'].append(model)
        group['count'] = len(group['models'])
        group['inTargetSeries'] = (
            group['inTargetSeries']
            or bool(model.get('inTargetSeries'))
            or (target_series_id is not None and series_id == target_series_id)
        )
    return groups


def _format_commerce_model_created_at(value):
    if not value:
        return ''
    if isinstance(value, datetime):
        return value.strftime('%Y-%m-%d %H:%M')
    text = str(value).strip()
    if not text:
        return ''
    normalized = text.replace('Z', '+00:00')
    try:
        parsed = datetime.fromisoformat(normalized)
        return parsed.strftime('%Y-%m-%d %H:%M')
    except ValueError:
        return text[:16].replace('T', ' ')


@csrf_exempt
@require_http_methods(["POST"])
def commerce_model_prompt(request, series_id):
    """生成电商模特提示词"""
    try:
        data = json.loads(request.body or '{}')
    except json.JSONDecodeError:
        return JsonResponse({'success': False, 'error': '请求格式错误'}, status=400)

    gender = (data.get('gender') or '').strip()
    model_type = (data.get('model_type') or data.get('modelType') or '').strip()
    ethnicity = (data.get('ethnicity') or '').strip()
    body_profile = (data.get('body_profile') or data.get('bodyProfile') or DEFAULT_COMMERCE_MODEL_BODY_PROFILE).strip()
    validation_error = _validate_commerce_model_choices(gender, model_type, ethnicity)
    if validation_error:
        return JsonResponse({'success': False, 'error': validation_error}, status=400)
    if body_profile not in COMMERCE_MODEL_BODY_PROFILES:
        return JsonResponse({'success': False, 'error': '请选择身材类型'}, status=400)

    client = get_client(request)
    try:
        result = client.post('/v1/commerce/models/prompt', {
            'gender': gender,
            'modelType': model_type,
            'ethnicity': ethnicity,
            'bodyProfile': body_profile,
        })
        return JsonResponse({
            'success': True,
            'name': result.get('name', ''),
            'prompt': result.get('prompt', ''),
        })
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def commerce_model_create(request, series_id):
    """创建电商模特"""
    name = request.POST.get('name', '').strip()
    gender = request.POST.get('gender', '').strip()
    model_type = request.POST.get('model_type', '').strip()
    ethnicity = request.POST.get('ethnicity', '').strip()
    body_profile = request.POST.get('body_profile', DEFAULT_COMMERCE_MODEL_BODY_PROFILE).strip()
    model_aspect_ratio = DEFAULT_COMMERCE_MODEL_ASPECT_RATIO
    appearance_prompt = request.POST.get('appearance_prompt', '').strip()
    if not name:
        messages.error(request, '请输入模特名称')
        return redirect('series:commerce_series_detail', series_id=series_id)
    validation_error = _validate_commerce_model_choices(gender, model_type, ethnicity)
    if validation_error:
        messages.error(request, validation_error)
        return redirect('series:commerce_series_detail', series_id=series_id)
    if body_profile not in COMMERCE_MODEL_BODY_PROFILES:
        messages.error(request, '请选择身材类型')
        return redirect('series:commerce_series_detail', series_id=series_id)
    if not appearance_prompt:
        messages.error(request, '请先生成并确认模特提示词')
        return redirect('series:commerce_series_detail', series_id=series_id)

    client = get_client(request)
    try:
        payload = {
            'name': name,
            'sourceType': 'ai',
            'gender': gender,
            'modelType': model_type,
            'ethnicity': ethnicity,
            'bodyProfile': body_profile,
            'modelAspectRatio': model_aspect_ratio,
            'appearancePrompt': appearance_prompt,
        }
        result = client.post(f'/v1/commerce/series/{series_id}/models', payload)
        model_name = result.get('name') or payload['name']
        model_id = result.get('id')
        messages.success(request, f'模特 "{model_name}" 已创建，基础形象正在生成中')
        redirect_url = f'/commerce/series/{series_id}/?created_model=1'
        if model_id:
            redirect_url += f'&created_model_id={model_id}'
        return redirect(redirect_url)
    except BackendAPIError as e:
        messages.error(request, f'创建模特失败: {e.message}')
        return redirect('series:commerce_series_detail', series_id=series_id)


@csrf_exempt
@require_http_methods(["POST"])
def commerce_model_generate_image(request, model_id):
    """启动电商模特基础图生成"""
    client = get_client(request)
    try:
        result = client.post(f'/v1/commerce/models/{model_id}/generate-image')
        return JsonResponse({'success': True, 'model': result})
    except BackendAPIError as e:
        return JsonResponse({'success': False, 'error': e.message}, status=400)


def _validate_commerce_model_choices(gender, model_type, ethnicity):
    allowed_model_types = COMMERCE_MODEL_TYPES_BY_GENDER.get(gender, ())
    if not gender or gender not in COMMERCE_MODEL_TYPES_BY_GENDER:
        return '请选择模特性别'
    if not model_type or model_type not in allowed_model_types:
        return '模特类型与性别不匹配，请重新选择'
    if not ethnicity or ethnicity not in COMMERCE_MODEL_ETHNICITIES:
        return '请选择人种风格'
    return ''


def commerce_model_detail(request, model_id):
    """电商模特详情"""
    token = request.session.get('token')
    client = get_client(request)
    try:
        model = client.get(f'/v1/commerce/models/{model_id}')
        if model.get('status') != 'locked':
            messages.error(request, '请先锁定模特再绑定商品')
            series_id = model.get('seriesId')
            if series_id:
                return redirect('series:commerce_series_detail', series_id=series_id)
        endpoints = {
            'products': f'/v1/commerce/models/{model_id}/products',
        }
        if model.get('seriesId'):
            endpoints['scenes'] = f'/v1/commerce/series/{model.get("seriesId")}/scenes'
        client_factory = (lambda: BackendClient(token=token)) if isinstance(client, BackendClient) else None
        results = _parallel_client_gets(client, endpoints, client_factory=client_factory)
        products = _attach_commerce_product_try_on_projects(client, results.get('products') or [], client_factory=client_factory)
        scenes = results.get('scenes') or []
    except BackendAPIError as e:
        messages.error(request, f'获取模特商品失败: {e.message}')
        products = []
        scenes = []

    completed_scenes = [scene for scene in (scenes or []) if scene.get('status') == 'completed' and scene.get('imageUrl')]
    for product in products or []:
        product['availableScenes'] = completed_scenes
    return render(request, 'commerce/model_detail.html', {
        'model_id': model_id,
        'series_id': model.get('seriesId'),
        'products': products,
        'available_scenes': completed_scenes,
        'try_on_scene_filter_categories': _commerce_scene_filter_categories(completed_scenes),
        'try_on_aspect_ratios': COMMERCE_ASPECT_RATIO_OPTIONS,
        'try_on_default_aspect_ratio': DEFAULT_COMMERCE_ASPECT_RATIO,
        'try_on_shots': COMMERCE_TRY_ON_SHOTS,
        'try_on_max_shots': COMMERCE_TRY_ON_MAX_SHOTS,
        'try_on_performance_styles': COMMERCE_TRY_ON_PERFORMANCE_STYLES,
        'try_on_default_performance_style': COMMERCE_TRY_ON_DEFAULT_PERFORMANCE_STYLE,
        'try_on_performance_boosts': COMMERCE_TRY_ON_PERFORMANCE_BOOSTS,
        'try_on_max_performance_boosts': COMMERCE_TRY_ON_MAX_PERFORMANCE_BOOSTS,
        'try_on_video_durations': COMMERCE_TRY_ON_VIDEO_DURATIONS,
        'try_on_default_duration': COMMERCE_TRY_ON_DEFAULT_DURATION,
        'try_on_video_resolutions': COMMERCE_TRY_ON_VIDEO_RESOLUTIONS,
        'try_on_default_resolution': COMMERCE_TRY_ON_DEFAULT_RESOLUTION,
        'try_on_video_models': COMMERCE_TRY_ON_VIDEO_MODELS,
        'try_on_default_video_model': COMMERCE_TRY_ON_DEFAULT_VIDEO_MODEL,
    })


@csrf_exempt
@require_http_methods(["POST"])
def commerce_model_confirm(request, model_id):
    """锁定电商模特"""
    client = get_client(request)
    is_ajax = request.headers.get('X-Requested-With') == 'XMLHttpRequest'
    try:
        result = client.post(f'/v1/commerce/models/{model_id}/confirm')
        if is_ajax:
            return JsonResponse({'code': 200, 'data': result, 'message': '模特已锁定'})
        messages.success(request, '模特已锁定')
        series_id = result.get('seriesId')
        if series_id:
            return redirect('series:commerce_series_detail', series_id=series_id)
    except BackendAPIError as e:
        if is_ajax:
            return JsonResponse({'code': e.status_code, 'message': e.message}, status=e.status_code)
        messages.error(request, f'锁定失败: {e.message}')
    return redirect('series:commerce_model_detail', model_id=model_id)


@csrf_exempt
@require_http_methods(["POST"])
def commerce_model_unlock(request, model_id):
    """解锁电商模特"""
    client = get_client(request)
    try:
        result = client.post(f'/v1/commerce/models/{model_id}/unlock')
        messages.success(request, '模特已解锁')
        series_id = result.get('seriesId')
        if series_id:
            return redirect('series:commerce_series_detail', series_id=series_id)
    except BackendAPIError as e:
        messages.error(request, f'解锁失败: {e.message}')
    return redirect('series:commerce_model_detail', model_id=model_id)


@csrf_exempt
@require_http_methods(["POST"])
def commerce_model_regenerate(request, model_id):
    """重新生成失败的电商模特图"""
    client = get_client(request)
    try:
        result = client.post(f'/v1/commerce/models/{model_id}/regenerate')
        model_name = result.get('name') or model_id
        messages.success(request, f'模特 "{model_name}" 已重新提交生成')
        series_id = result.get('seriesId')
        if series_id:
            return redirect('series:commerce_series_detail', series_id=series_id)
    except BackendAPIError as e:
        messages.error(request, f'重新生成模特失败: {e.message}')
    return redirect('series:commerce_model_detail', model_id=model_id)


@csrf_exempt
@require_http_methods(["POST"])
def commerce_product_create(request, model_id):
    """创建电商商品"""
    client = get_client(request)
    try:
        grouped_product_images = [
            (product_image, usage)
            for field_name, usage in (
                ('product_main_images', '主图'),
                ('product_detail_images', '细节图'),
                ('product_worn_images', '上身参考'),
            )
            for product_image in request.FILES.getlist(field_name)
        ]
        legacy_product_images = request.FILES.getlist('product_image')
        if legacy_product_images and not grouped_product_images:
            legacy_usages = request.POST.getlist('product_image_usages')
            grouped_product_images = list(zip(legacy_product_images, legacy_usages))
        if not grouped_product_images:
            messages.error(request, '请上传商品图片')
            return redirect('series:commerce_model_detail', model_id=model_id)
        if len(grouped_product_images) > COMMERCE_PRODUCT_IMAGE_MAX_COUNT:
            messages.error(request, '最多上传5张商品图片')
            return redirect('series:commerce_model_detail', model_id=model_id)
        if legacy_product_images and len(grouped_product_images) != len(legacy_product_images):
            messages.error(request, '请为每张商品图片选择用途')
            return redirect('series:commerce_model_detail', model_id=model_id)
        uploaded_images = []
        for product_image, usage in grouped_product_images:
            usage = (usage or '').strip()
            if usage not in COMMERCE_PRODUCT_IMAGE_USAGES:
                messages.error(request, '请为每张商品图片选择用途')
                return redirect('series:commerce_model_detail', model_id=model_id)
            image_url = _upload_commerce_product_image(client, product_image)
            uploaded_images.append({'url': image_url, 'usage': usage})
        main_image = next((image for image in uploaded_images if image['usage'] == '主图'), uploaded_images[0])
        payload = {
            'name': request.POST.get('name', '').strip(),
            'imageUrl': main_image['url'],
            'productType': request.POST.get('product_type', '').strip(),
            'images': uploaded_images,
        }
        client.post(f'/v1/commerce/models/{model_id}/products', payload)
        messages.success(request, f'商品 "{payload["name"]}" 已添加')
    except BackendAPIError as e:
        messages.error(request, f'添加商品失败: {e.message}')
    except ValueError as e:
        messages.error(request, str(e))
    return redirect('series:commerce_model_detail', model_id=model_id)


@csrf_exempt
@require_http_methods(["POST"])
def commerce_product_images_update(request, product_id):
    """更新已绑定商品的图片组，保留商品本身和既有视频记录。"""
    client = get_client(request)
    model_id = request.POST.get('model_id')
    is_ajax = request.headers.get('X-Requested-With') == 'XMLHttpRequest'

    def error_response(message, status=400):
        if is_ajax:
            return JsonResponse({'code': status, 'message': message}, status=status)
        messages.error(request, message)
        return _redirect_to_commerce_model_detail(model_id)

    try:
        image_urls = request.POST.getlist('product_image_urls')
        usages = request.POST.getlist('product_image_usages')
        image_rows = []
        max_rows = max(len(image_urls), len(usages))
        for index in range(max_rows):
            usage = (usages[index] if index < len(usages) else '').strip()
            image_url = (image_urls[index] if index < len(image_urls) else '').strip()
            upload_file = request.FILES.get(f'product_image_{index}')
            if upload_file:
                image_url = _upload_commerce_product_image(client, upload_file)
            if not image_url and not usage:
                continue
            if usage not in COMMERCE_PRODUCT_IMAGE_USAGES:
                return error_response('请为每张商品图片选择用途')
            if not image_url:
                return error_response('请上传商品图片')
            image_rows.append({'url': image_url, 'usage': usage})

        if not image_rows:
            return error_response('请上传商品图片')
        if len(image_rows) > COMMERCE_PRODUCT_IMAGE_MAX_COUNT:
            return error_response('最多上传5张商品图片')

        result = client.post(f'/v1/commerce/products/{product_id}/images', {'images': image_rows})
        model_id = result.get('modelId') or model_id
        if is_ajax:
            data = {**result, 'images': image_rows}
            return JsonResponse({'code': 200, 'data': data})
        messages.success(request, '商品图片已更新')
    except BackendAPIError as e:
        if is_ajax:
            return JsonResponse({'code': e.status_code, 'message': e.message}, status=e.status_code)
        messages.error(request, f'更新商品图片失败: {e.message}')
    except ValueError as e:
        if is_ajax:
            return JsonResponse({'code': 400, 'message': str(e)}, status=400)
        messages.error(request, str(e))
    return _redirect_to_commerce_model_detail(model_id)


@csrf_exempt
@require_http_methods(["POST"])
def commerce_project_create(request, product_id):
    """从商品创建带货内容项目"""
    content_type = request.POST.get('content_type', 'talking').strip() or 'talking'
    aspect_ratio = request.POST.get('aspect_ratio', DEFAULT_COMMERCE_ASPECT_RATIO).strip()
    if aspect_ratio not in COMMERCE_ASPECT_RATIOS:
        aspect_ratio = DEFAULT_COMMERCE_ASPECT_RATIO
    client = get_client(request)
    try:
        payload = {
            'contentType': content_type,
            'aspectRatio': aspect_ratio,
        }
        if content_type == 'talking':
            selling_points = request.POST.get('selling_points', '').strip()
            target_audience = request.POST.get('target_audience', '').strip()
            price_offer = request.POST.get('price_offer', '').strip()
            if selling_points:
                payload['sellingPoints'] = selling_points
            if target_audience:
                payload['targetAudience'] = target_audience
            if price_offer:
                payload['priceOffer'] = price_offer
        elif content_type == 'try_on':
            selected_shots = [
                shot for shot in request.POST.getlist('try_on_shots')
                if shot in COMMERCE_TRY_ON_SHOT_VALUES
            ][:COMMERCE_TRY_ON_MAX_SHOTS]
            performance_style = request.POST.get('try_on_performance_style', '').strip()
            if performance_style and performance_style not in COMMERCE_TRY_ON_PERFORMANCE_STYLE_VALUES:
                performance_style = COMMERCE_TRY_ON_DEFAULT_PERFORMANCE_STYLE
            selected_performance_boosts = [
                boost for boost in request.POST.getlist('try_on_performance_boosts')
                if boost in COMMERCE_TRY_ON_PERFORMANCE_BOOST_VALUES
            ][:COMMERCE_TRY_ON_MAX_PERFORMANCE_BOOSTS]
            try:
                duration_seconds = int(request.POST.get('duration_seconds', COMMERCE_TRY_ON_DEFAULT_DURATION))
            except (TypeError, ValueError):
                duration_seconds = COMMERCE_TRY_ON_DEFAULT_DURATION
            if duration_seconds not in COMMERCE_TRY_ON_VIDEO_DURATIONS:
                duration_seconds = COMMERCE_TRY_ON_DEFAULT_DURATION
            resolution = request.POST.get('resolution', COMMERCE_TRY_ON_DEFAULT_RESOLUTION).strip()
            if resolution not in COMMERCE_TRY_ON_VIDEO_RESOLUTION_VALUES:
                resolution = COMMERCE_TRY_ON_DEFAULT_RESOLUTION
            video_model = request.POST.get('video_model', COMMERCE_TRY_ON_DEFAULT_VIDEO_MODEL).strip()
            if video_model not in COMMERCE_TRY_ON_VIDEO_MODEL_VALUES:
                video_model = COMMERCE_TRY_ON_DEFAULT_VIDEO_MODEL
            supported_resolutions = COMMERCE_TRY_ON_SUPPORTED_RESOLUTIONS_BY_MODEL.get(
                video_model,
                COMMERCE_TRY_ON_SUPPORTED_RESOLUTIONS_BY_MODEL[COMMERCE_TRY_ON_DEFAULT_VIDEO_MODEL],
            )
            if resolution not in supported_resolutions:
                resolution = supported_resolutions[0]
            scene_ids = []
            for raw_scene_id in request.POST.getlist('scene_ids'):
                try:
                    scene_id = int(raw_scene_id)
                except (TypeError, ValueError):
                    continue
                if scene_id > 0 and scene_id not in scene_ids:
                    scene_ids.append(scene_id)
            try_on_parts = []
            if selected_shots:
                try_on_parts.append(f'展示方式：{"、".join(selected_shots)}')
            if performance_style:
                try_on_parts.append(f'表演风格：{performance_style}')
            if selected_performance_boosts:
                try_on_parts.append(f'动作表情：{"、".join(selected_performance_boosts)}')
            if try_on_parts:
                payload['tryOnRequirements'] = '；'.join(try_on_parts)
            if scene_ids:
                payload['sceneIds'] = scene_ids[:1]
            payload['durationSeconds'] = duration_seconds
            payload['resolution'] = resolution
            payload['videoModel'] = video_model
        result = client.post(f'/v1/commerce/products/{product_id}/projects', payload)
        if content_type == 'try_on':
            return JsonResponse({'code': 200, 'data': result})
        return redirect('series:commerce_project_detail', project_id=result.get('id'))
    except BackendAPIError as e:
        if content_type == 'try_on':
            return JsonResponse({'code': 400, 'message': e.message}, status=400)
        messages.error(request, f'生成内容失败: {e.message}')
        return redirect(request.META.get('HTTP_REFERER', 'series:commerce_series_list'))


def commerce_project_detail(request, project_id):
    """电商带货项目详情"""
    client = get_client(request)
    try:
        project = client.get(f'/v1/commerce/projects/{project_id}')
    except BackendAPIError as e:
        messages.error(request, f'获取带货项目失败: {e.message}')
        return redirect('series:commerce_series_list')

    return render(request, 'commerce/project_detail.html', {'project': project})


@csrf_exempt
@require_http_methods(["GET"])
def commerce_project_status(request, project_id):
    """给前端轮询项目生成状态使用。"""
    client = get_client(request)
    try:
        result = client.get(f'/v1/commerce/projects/{project_id}')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def commerce_project_generation_start(request, project_id):
    """启动电商试穿视频后台生成。"""
    client = get_client(request)
    try:
        result = client.post(f'/v1/commerce/projects/{project_id}/generation/start')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': 400, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def commerce_project_generate_video(request, project_id):
    """生成电商带货项目视频"""
    client = get_client(request)
    try:
        client.post(f'/v1/commerce/projects/{project_id}/generate-video')
        messages.success(request, '电商带货视频已生成')
    except BackendAPIError as e:
        messages.error(request, f'生成视频失败: {e.message}')
    return redirect('series:commerce_project_detail', project_id=project_id)


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
            # 轻量分镜接口不再携带 propsJson，优先使用已组装好的 props 列表。
            shot_props = shot.get('props') or []
            if isinstance(shot_props, list):
                for prop_info in shot_props:
                    if isinstance(prop_info, dict):
                        prop_id = prop_info.get('propId') or prop_info.get('id')
                        if prop_id:
                            episode_prop_ids.add(prop_id)
                        prop_name = prop_info.get('propName') or prop_info.get('name')
                        if prop_name:
                            episode_prop_names.add(prop_name)
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


@ensure_csrf_cookie
def membership_page(request):
    """会员与积分充值页面"""
    return render(request, 'payments/membership.html')


def payment_result_page(request):
    """支付结果确认页面"""
    if not request.session.get('token'):
        request.session['next'] = request.get_full_path()
        return redirect('/auth/login/')

    order_no = (request.GET.get('out_trade_no') or '').strip()
    return render(request, 'payments/payment_result.html', {'order_no': order_no})


def payment_products_api(request):
    """会员/充值产品API代理"""
    client = get_client(request)
    try:
        result = client.get('/v1/payments/products')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': e.status_code, 'message': e.message}, status=400)


def _validated_payment_method(data):
    payment_method = data.get('paymentMethod', 'PAGE')
    if payment_method not in {'PAGE', 'QR'}:
        raise ValueError('Invalid payment method')
    return payment_method


@csrf_exempt
@require_http_methods(["POST"])
def payment_membership_order(request):
    """会员订单创建API代理"""
    client = get_client(request)
    try:
        data = json.loads(request.body or '{}')
        result = client.post('/v1/payments/membership/orders', {
            'productCode': data.get('productCode'),
            'paymentMethod': _validated_payment_method(data),
        })
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': e.status_code, 'message': e.message}, status=400)
    except json.JSONDecodeError:
        return JsonResponse({'code': 400, 'message': 'Invalid JSON'}, status=400)
    except ValueError as e:
        return JsonResponse({'code': 400, 'message': str(e)}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def payment_recharge_order(request):
    """积分充值订单创建API代理"""
    client = get_client(request)
    try:
        data = json.loads(request.body or '{}')
        result = client.post('/v1/payments/recharge/orders', {
            'amountCents': data.get('amountCents'),
            'paymentMethod': _validated_payment_method(data),
        })
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': e.status_code, 'message': e.message}, status=400)
    except json.JSONDecodeError:
        return JsonResponse({'code': 400, 'message': 'Invalid JSON'}, status=400)
    except ValueError as e:
        return JsonResponse({'code': 400, 'message': str(e)}, status=400)


def _payment_error_response(status_code, message):
    safe_status = status_code if type(status_code) is int and 400 <= status_code <= 599 else 502
    return JsonResponse({'code': safe_status, 'message': message}, status=safe_status)


@require_http_methods(["GET"])
def payment_pending_orders(request):
    """待支付订单列表代理"""
    client = get_client(request)
    try:
        result = client.get('/v1/payments/orders/pending')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return _payment_error_response(e.status_code, e.message)


@require_http_methods(["POST"])
def payment_order_resume(request, order_no):
    """待支付订单恢复代理"""
    client = get_client(request)
    try:
        result = client.post(f'/v1/payments/orders/{order_no}/resume')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return _payment_error_response(e.status_code, e.message)


@require_http_methods(["POST"])
def payment_order_switch_to_page(request, order_no):
    """待支付订单安全切换收银台代理"""
    client = get_client(request)
    try:
        result = client.post(f'/v1/payments/orders/{order_no}/switch-to-page')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return _payment_error_response(e.status_code, e.message)


def payment_order_status(request, order_no):
    """支付订单状态查询代理"""
    client = get_client(request)
    try:
        result = client.get(f'/v1/payments/orders/{order_no}')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': e.status_code, 'message': e.message}, status=400)


@csrf_exempt
@require_http_methods(["POST"])
def payment_order_sync(request, order_no):
    """支付订单状态同步代理"""
    client = get_client(request)
    try:
        result = client.post(f'/v1/payments/orders/{order_no}/sync')
        return JsonResponse({'code': 200, 'data': result})
    except BackendAPIError as e:
        return JsonResponse({'code': e.status_code, 'message': e.message}, status=400)


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
    nickname = (request.GET.get('nickname') or '').strip()

    client = get_client(request)
    try:
        params = {
            'hours': hours,
            'recordPage': record_page,
            'recordPageSize': record_page_size,
        }
        if nickname:
            params['nickname'] = nickname
        result = client.get(f'/v1/admin/credits/dashboard?{urlencode(params)}')
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
        allowed_types = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
        if file.content_type not in allowed_types:
            return JsonResponse({'success': False, 'message': '只支持 JPG、PNG、GIF、WEBP 格式'}, status=400)

        # 检查文件大小 (最大 10MB)
        if file.size > 10 * 1024 * 1024:
            return JsonResponse({'success': False, 'message': '文件大小不能超过10MB'}, status=400)

        # 调用后端上传接口
        files = {'file': (file.name, file.read(), file.content_type)}
        upload_type = request.POST.get('type', 'avatar')
        result = client.upload('/v1/upload', files, data={'type': upload_type})

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
