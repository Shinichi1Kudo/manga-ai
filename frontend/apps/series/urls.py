from django.urls import path
from . import views

app_name = 'series'

urlpatterns = [
    # 首页
    path('', views.series_list, name='list'),

    # API: 获取系列列表
    path('api/series/list/', views.series_list, name='api_list'),

    # 创建新系列
    path('init/', views.series_init, name='init'),

    # 系列详情
    path('<int:series_id>/', views.series_detail, name='detail'),

    # 处理进度页面
    path('<int:series_id>/progress/', views.series_progress, name='progress'),

    # 角色审核页面
    path('<int:series_id>/review/', views.series_review, name='review'),

    # 锁定系列
    path('<int:series_id>/lock/', views.series_lock, name='lock'),

    # API: 获取进度
    path('api/<int:series_id>/progress/', views.api_progress, name='api_progress'),
]
