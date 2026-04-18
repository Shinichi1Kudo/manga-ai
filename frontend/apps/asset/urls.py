from django.urls import path
from . import views

app_name = 'asset'

urlpatterns = [
    path('<int:asset_id>/', views.asset_detail, name='detail'),
    path('<int:asset_id>/download/', views.asset_download, name='download'),
    path('series/<int:series_id>/', views.asset_library, name='library'),
    path('api/role/<int:role_id>/default/<int:clothing_id>/', views.set_default_clothing, name='set_default'),
    path('api/assets/role/<int:role_id>/versions/<int:clothing_id>/', views.get_clothing_versions, name='versions'),
]
