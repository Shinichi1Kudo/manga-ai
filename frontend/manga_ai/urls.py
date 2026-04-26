"""
URL configuration for manga_ai project.
"""
from django.urls import path, include
from apps.role import urls as role_urls

urlpatterns = [
    # 首页
    path('', include('apps.series.urls')),

    # 用户认证
    path('auth/', include(('apps.auth.urls', 'auth'), namespace='auth')),

    # 角色管理
    path('roles/', include('apps.role.urls')),

    # 资产管理
    path('assets/', include(('apps.asset.urls', 'asset'), namespace='asset')),

    # API routes (for AJAX calls)
    path('api/', include(('apps.asset.urls', 'asset-api'), namespace='asset-api')),
    path('api/', include((role_urls.api_urlpatterns, 'role-api'), namespace='role-api')),
]
