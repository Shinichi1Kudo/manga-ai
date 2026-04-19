from django.urls import path
from . import views

app_name = 'role'

urlpatterns = [
    path('series/<int:series_id>/create/', views.role_create, name='create'),
    path('<int:role_id>/', views.role_detail, name='detail'),
    path('<int:role_id>/delete/', views.role_delete, name='delete'),
    path('<int:role_id>/confirm/', views.role_confirm, name='confirm'),
    path('<int:role_id>/regenerate/', views.role_regenerate, name='regenerate'),
    path('<int:role_id>/update/', views.role_update, name='update'),
]

# API routes
api_urlpatterns = [
    path('roles/<int:role_id>/', views.role_detail, name='role-detail-api'),
]
