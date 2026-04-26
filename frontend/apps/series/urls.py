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

    # 回收站页面
    path('trash/', views.trash_page, name='trash'),

    # 系列详情
    path('<int:series_id>/', views.series_detail, name='detail'),

    # 处理进度页面
    path('<int:series_id>/progress/', views.series_progress, name='progress'),

    # 角色审核页面
    path('<int:series_id>/review/', views.series_review, name='review'),

    # 锁定系列
    path('<int:series_id>/lock/', views.series_lock, name='lock'),

    # 更新系列信息
    path('<int:series_id>/update/', views.series_update, name='update'),

    # 删除系列（移入回收站）
    path('api/series/<int:series_id>/delete/', views.series_delete, name='api_delete'),

    # 恢复系列
    path('api/series/<int:series_id>/restore/', views.series_restore, name='api_restore'),

    # 彻底删除系列
    path('api/series/<int:series_id>/permanent/', views.series_permanent_delete, name='api_permanent_delete'),

    # API: 获取进度
    path('api/<int:series_id>/progress/', views.api_progress, name='api_progress'),

    # ==================== 剧集管理 ====================
    # 选择系列（剧集制作入口）
    path('episodes/', views.select_series_for_episode, name='select_series_for_episode'),

    # 剧集列表
    path('<int:series_id>/episodes/', views.episode_list, name='episode_list'),

    # 创建剧集
    path('<int:series_id>/episodes/create/', views.episode_create, name='episode_create'),

    # 剧集详情/分镜审核
    path('<int:series_id>/episodes/<int:episode_id>/', views.episode_detail, name='episode_detail'),

    # 剧集进度API
    path('api/episodes/<int:episode_id>/progress/', views.episode_progress, name='episode_progress'),
    path('api/v1/episodes/<int:episode_id>/progress/', views.episode_progress, name='episode_progress_v1'),

    # 更新剧本
    path('api/v1/episodes/<int:episode_id>/script', views.episode_update_script, name='episode_update_script'),

    # 重新解析剧本（只解析资产）
    path('api/v1/episodes/<int:episode_id>/parse/', views.episode_parse_script, name='episode_parse_script'),

    # 解析分镜
    path('api/v1/episodes/<int:episode_id>/parse-shots/', views.episode_parse_shots, name='episode_parse_shots'),

    # 获取解析后的资产清单
    path('api/v1/episodes/<int:episode_id>/parsed-assets/', views.episode_parsed_assets, name='episode_parsed_assets'),

    # 批量生成选中资产
    path('api/v1/episodes/<int:episode_id>/generate-assets/', views.episode_generate_assets, name='episode_generate_assets'),

    # 创建场景
    path('api/v1/scenes/', views.scene_create, name='scene_create'),

    # 创建道具
    path('api/v1/props/', views.prop_create, name='prop_create'),

    # 重新生成场景
    path('api/v1/scenes/<int:scene_id>/regenerate/', views.scene_regenerate, name='scene_regenerate'),

    # 重新生成道具
    path('api/v1/props/<int:prop_id>/regenerate/', views.prop_regenerate, name='prop_regenerate'),

    # 场景回滚
    path('api/v1/scenes/<int:scene_id>/rollback/', views.scene_rollback, name='scene_rollback'),

    # 道具回滚
    path('api/v1/props/<int:prop_id>/rollback/', views.prop_rollback, name='prop_rollback'),

    # 场景锁定/解锁
    path('api/v1/scenes/<int:scene_id>/lock/', views.scene_lock, name='scene_lock'),
    path('api/v1/scenes/<int:scene_id>/unlock/', views.scene_unlock, name='scene_unlock'),

    # 场景更新名称
    path('api/v1/scenes/<int:scene_id>/name/', views.scene_update_name, name='scene_update_name'),

    # 道具锁定/解锁
    path('api/v1/props/<int:prop_id>/lock/', views.prop_lock, name='prop_lock'),
    path('api/v1/props/<int:prop_id>/unlock/', views.prop_unlock, name='prop_unlock'),

    # 道具更新名称
    path('api/v1/props/<int:prop_id>/name/', views.prop_update_name, name='prop_update_name'),

    # 道具详情 (GET) 和删除 (DELETE)
    path('api/v1/props/<int:prop_id>/', views.prop_detail, name='prop_detail'),

    # 场景详情 (GET) 和删除 (DELETE)
    path('api/v1/scenes/<int:scene_id>/', views.scene_detail, name='scene_detail'),

    # 生成单个分镜视频
    path('api/shots/<int:shot_id>/generate/', views.shot_generate_video, name='shot_generate'),

    # 批量生成视频
    path('api/episodes/<int:episode_id>/generate/', views.episode_generate_videos, name='episode_generate'),
    path('api/v1/episodes/<int:episode_id>/generate/', views.episode_generate_videos, name='episode_generate_v1'),

    # 审核分镜
    path('api/shots/<int:shot_id>/review/', views.shot_review, name='shot_review'),
    path('api/v1/shots/<int:shot_id>/review/', views.shot_review, name='shot_review_v1'),

    # 更新分镜
    path('api/v1/shots/<int:shot_id>/', views.shot_update, name='shot_update'),

    # ========== 分镜参考图相关 ==========
    # 获取分镜参考图列表
    path('api/v1/shots/<int:shot_id>/references/', views.shot_references, name='shot_references'),

    # 自动匹配分镜资产
    path('api/v1/shots/<int:shot_id>/match-assets/', views.shot_match_assets, name='shot_match_assets'),

    # 带参考图生成视频
    path('api/v1/shots/<int:shot_id>/generate-with-references/', views.shot_generate_with_references, name='shot_generate_with_references'),

    # 视频版本历史
    path('api/v1/shots/<int:shot_id>/video-history', views.shot_video_history, name='shot_video_history'),

    # 视频版本回滚
    path('api/v1/shots/<int:shot_id>/rollback-video/<int:asset_id>', views.shot_video_rollback, name='shot_video_rollback'),

    # 创建分镜
    path('api/v1/shots/episode/<int:episode_id>/create', views.shot_create, name='shot_create'),

    # 删除分镜
    path('api/v1/shots/<int:shot_id>/delete', views.shot_delete, name='shot_delete'),

    # 重新排序分镜
    path('api/v1/shots/episode/<int:episode_id>/reorder', views.shot_reorder, name='shot_reorder'),
]
