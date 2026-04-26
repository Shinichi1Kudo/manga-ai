"""
中间件
"""
from django.urls import reverse
from django.shortcuts import redirect


class LoginRequiredMiddleware:
    """
    登录验证中间件
    未登录用户访问需要认证的页面时，重定向到登录页面
    """

    def __init__(self, get_response):
        self.get_response = get_response

        # 不需要登录验证的路径
        self.exempt_paths = [
            '/auth/login/',
            '/auth/register/',
            '/auth/send-code/',
            '/auth/logout/',
            '/static/',
            '/media/',
        ]

    def __call__(self, request):
        # 检查是否是豁免路径
        path = request.path
        is_exempt = any(path.startswith(exempt) for exempt in self.exempt_paths)

        # 检查是否已登录
        is_logged_in = request.session.get('token') is not None

        # 如果未登录且不是豁免路径，重定向到登录页
        if not is_logged_in and not is_exempt:
            # 保存原始请求路径，登录后跳转回来
            request.session['next'] = path
            return redirect('/auth/login/')

        response = self.get_response(request)
        return response
