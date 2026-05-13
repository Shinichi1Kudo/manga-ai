"""
中间件
"""
from django.http import JsonResponse
from django.shortcuts import redirect
from api.backend_client import BackendAPIError


class LoginRequiredMiddleware:
    """
    登录验证中间件
    未登录用户访问需要认证的页面时，重定向到登录页面
    后端返回401时自动清除session并重定向到登录页
    """

    def __init__(self, get_response):
        self.get_response = get_response

        # 不需要登录验证的路径
        self.exempt_paths = [
            '/auth/login/',
            '/auth/register/',
            '/auth/send-code/',
            '/auth/logout/',
            '/favicon.ico',
            '/api/v1/common/contact-image/',
            '/api/v1/common/showcase-assets/',
            '/static/',
            '/media/',
        ]

    def __call__(self, request):
        # 检查是否是豁免路径
        path = request.path
        is_home_page = path == '/'
        is_exempt = is_home_page or any(path.startswith(exempt) for exempt in self.exempt_paths)
        is_api_path = path.startswith('/api/')

        # 检查是否已登录
        is_logged_in = request.session.get('token') is not None

        # 如果未登录且不是豁免路径，重定向到登录页
        if not is_logged_in and not is_exempt:
            if is_api_path:
                return JsonResponse({'code': 401, 'message': '请先登录'}, status=401)
            # 保存原始请求路径，登录后跳转回来
            request.session['next'] = path
            return redirect('/auth/login/')

        response = self.get_response(request)
        return response

    def process_exception(self, request, exception):
        """统一处理视图抛出的异常"""
        if isinstance(exception, BackendAPIError) and exception.status_code == 401:
            request.session.flush()
            return redirect('/auth/login/')
        return None
