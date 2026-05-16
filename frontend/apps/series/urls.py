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

    # 主体替换
    path('subject-replacement/', views.subject_replacement_page, name='subject_replacement'),
    path('api/v1/subject-replacements/', views.subject_replacement_tasks, name='subject_replacement_tasks'),
    path('api/v1/subject-replacements/<int:task_id>/', views.subject_replacement_task_detail, name='subject_replacement_task_detail'),
    path('api/v1/subject-replacements/<int:task_id>/name/', views.subject_replacement_task_rename, name='subject_replacement_task_rename'),
    path('api/v1/subject-replacements/<int:task_id>/delete/', views.subject_replacement_task_delete, name='subject_replacement_task_delete'),
    path('api/v1/subject-replacements/upload-video/', views.subject_replacement_upload_video, name='subject_replacement_upload_video'),
    path('api/v1/subject-replacements/upload-reference/', views.subject_replacement_upload_reference, name='subject_replacement_upload_reference'),

    # GPT-Image2 生图
    path('gpt-image2/', views.gpt_image2_page, name='gpt_image2_page'),
    path('api/v1/gpt-image2/', views.gpt_image2_tasks, name='gpt_image2_tasks'),
    path('api/v1/gpt-image2/generate/', views.gpt_image2_generate, name='gpt_image2_generate'),
    path('api/v1/gpt-image2/latest/', views.gpt_image2_latest_task, name='gpt_image2_latest_task'),
    path('api/v1/gpt-image2/<int:task_id>/', views.gpt_image2_task_detail, name='gpt_image2_task_detail'),
    path('api/v1/gpt-image2/upload-reference/', views.gpt_image2_upload_reference, name='gpt_image2_upload_reference'),

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

    # 删除剧集
    path('api/v1/episodes/<int:episode_id>/delete/', views.episode_delete, name='episode_delete'),

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

    # 上传场景图片
    path('api/v1/scenes/upload/', views.scene_upload, name='scene_upload'),
    path('api/v1/scenes/<int:scene_id>/upload/', views.scene_asset_upload, name='scene_asset_upload'),

    # 创建道具
    path('api/v1/props/', views.prop_create, name='prop_create'),

    # 上传道具图片
    path('api/v1/props/upload/', views.prop_upload, name='prop_upload'),
    path('api/v1/props/<int:prop_id>/upload/', views.prop_asset_upload, name='prop_asset_upload'),

    # 获取系列道具
    path('api/v1/props/series/<int:series_id>/', views.props_by_series, name='props_by_series'),

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

    # 下载分镜视频
    path('api/v1/shots/<int:shot_id>/download/', views.shot_download_video, name='shot_download'),

    # 手动上传分镜视频
    path('api/v1/shots/<int:shot_id>/upload-video/', views.shot_upload_video, name='shot_upload_video'),

    # 审核分镜
    path('api/shots/<int:shot_id>/review/', views.shot_review, name='shot_review'),
    path('api/v1/shots/<int:shot_id>/review/', views.shot_review, name='shot_review_v1'),
    path('api/v1/shots/<int:shot_id>/unlock/', views.shot_unlock, name='shot_unlock'),

    # 更新分镜
    path('api/v1/shots/<int:shot_id>/', views.shot_update, name='shot_update'),

    # 获取剧集分镜列表
    path('api/v1/shots/episode/<int:episode_id>/', views.shot_list_api, name='shot_list_api'),

    # ========== 分镜参考图相关 ==========
    # 获取分镜参考图列表
    path('api/v1/shots/<int:shot_id>/references/', views.shot_references, name='shot_references'),

    # 自动匹配分镜资产
    path('api/v1/shots/<int:shot_id>/match-assets/', views.shot_match_assets, name='shot_match_assets'),

    # 带参考图生成视频
    path('api/v1/shots/<int:shot_id>/generate-with-references/', views.shot_generate_with_references, name='shot_generate_with_references'),

    # 分镜视频生成两阶段提交：先落库生成中状态，再后台启动生成任务
    path('api/v1/shots/<int:shot_id>/generation/prepare', views.shot_generation_prepare, name='shot_generation_prepare'),
    path('api/v1/shots/<int:shot_id>/generation/prepare/', views.shot_generation_prepare, name='shot_generation_prepare_slash'),
    path('api/v1/shots/<int:shot_id>/generation/start', views.shot_generation_start, name='shot_generation_start'),
    path('api/v1/shots/<int:shot_id>/generation/start/', views.shot_generation_start, name='shot_generation_start_slash'),

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

    # 联系我们图片
    path('site-logo.png', views.site_logo, name='site_logo_png'),
    path('api/v1/common/contact-image/', views.contact_image, name='contact_image'),
    path('api/v1/common/site-logo/', views.site_logo, name='site_logo'),
    path('api/v1/common/showcase-assets/', views.showcase_assets, name='showcase_assets'),

    # 积分记录页面
    path('credits/records/', views.credit_records, name='credit_records'),

    # 工藤新一专属积分管理后台
    path('admin/credits/', views.credit_admin_dashboard, name='credit_admin_dashboard'),
    path('api/admin/credits/dashboard/', views.credit_admin_dashboard_api, name='credit_admin_dashboard_api'),

    # 积分记录API
    path('api/credits/records/', views.credit_records_api, name='credit_records_api'),

    # 兑换码API
    path('api/credits/redeem/', views.credits_redeem, name='credits_redeem'),

    # 用户信息API
    path('api/user/info/', views.user_info_api, name='user_info_api'),

    # 用户设置页面
    path('user/settings/', views.user_settings, name='user_settings'),

    # 更新用户资料API
    path('api/user/profile/', views.user_profile_update, name='user_profile_update'),

    # 文件上传API
    path('api/upload/', views.file_upload, name='file_upload'),

    # 检查昵称可用性API
    path('api/user/check-nickname/', views.user_check_nickname, name='user_check_nickname'),
]
