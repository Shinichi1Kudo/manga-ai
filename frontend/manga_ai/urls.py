"""
URL configuration for manga_ai project.
"""
from django.urls import path, include

urlpatterns = [
    # 首页
    path('', include('apps.series.urls')),

    # 角色管理
    path('roles/', include('apps.role.urls')),

    # 资产管理
    path('assets/', include('apps.asset.urls')),
]
