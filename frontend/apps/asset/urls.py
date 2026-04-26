from django.urls import path
from . import views

app_name = 'asset'

urlpatterns = [
    path('<int:asset_id>/', views.asset_detail, name='detail'),
    path('<int:asset_id>/prompt/', views.asset_prompt, name='prompt'),
    path('<int:asset_id>/download/', views.asset_download, name='download'),
    path('<int:asset_id>/rollback/', views.rollback_asset, name='rollback'),
    path('series/<int:series_id>/', views.asset_library, name='library'),
    path('role/<int:role_id>/default/<int:clothing_id>/', views.set_default_clothing, name='set_default'),
    path('assets/role/<int:role_id>/versions/<int:clothing_id>/', views.get_clothing_versions, name='versions'),
    path('assets/role/<int:role_id>/clothing/<int:clothing_id>/rename/', views.rename_clothing, name='rename_clothing'),
    path('assets/role/<int:role_id>/clothing/<int:clothing_id>/delete/', views.delete_clothing, name='delete_clothing'),
    # 资产库页面
    path('library/', views.asset_library_page, name='library_page'),
    path('api/locked-series/', views.get_locked_series, name='api_locked_series'),
    path('api/series/<int:series_id>/assets/', views.get_series_assets, name='api_series_assets'),
    # 场景和道具资产API（全部锁定资产，不按系列区分）
    path('api/locked-scenes/', views.get_all_locked_scenes, name='api_locked_scenes'),
    path('api/locked-props/', views.get_all_locked_props, name='api_locked_props'),
    # 按系列获取场景和道具资产
    path('api/series/<int:series_id>/scenes/', views.get_series_scenes, name='api_series_scenes'),
    path('api/series/<int:series_id>/props/', views.get_series_props, name='api_series_props'),
    # 影视资产
    path('api/series/<int:series_id>/video-assets/', views.get_series_video_assets, name='api_series_video_assets'),
]
