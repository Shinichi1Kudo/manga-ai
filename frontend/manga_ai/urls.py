"""
URL configuration for manga_ai project.
"""
from django.http import HttpResponse
from django.urls import path, include
from apps.role import urls as role_urls
from apps.series.seo import robots_txt, sitemap_xml

urlpatterns = [
    path('favicon.ico', lambda request: HttpResponse(status=204)),
    path('robots.txt', robots_txt, name='robots_txt'),
    path('sitemap.xml', sitemap_xml, name='sitemap_xml'),

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
