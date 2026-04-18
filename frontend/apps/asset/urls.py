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
]
