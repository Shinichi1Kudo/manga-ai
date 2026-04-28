"""
认证工具函数
"""
from functools import wraps
from django.shortcuts import redirect
from api.backend_client import BackendAPIError


def handle_401(view_func):
    """
    装饰器：捕获BackendAPIError 401错误，清除session并重定向到登录页
    """
    @wraps(view_func)
    def wrapper(request, *args, **kwargs):
        try:
            return view_func(request, *args, **kwargs)
        except BackendAPIError as e:
            if e.status_code == 401:
                request.session.flush()
                return redirect('/auth/login/')
            raise
    return wrapper