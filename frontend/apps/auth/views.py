import json
import requests
from django.shortcuts import render, redirect
from django.http import JsonResponse
from django.views.decorators.http import require_POST
from django.contrib import messages
from django.conf import settings


def get_api_url(path):
    """获取后端API完整URL"""
    return f"{settings.BACKEND_API_URL}{path}"


def login_view(request):
    """登录页面"""
    # 如果已登录，直接跳转首页
    token = request.session.get('token')
    if token:
        return redirect('/')

    if request.method == 'POST':
        email = request.POST.get('email', '').strip()
        password = request.POST.get('password', '').strip()

        if not email or not password:
            messages.error(request, '请输入邮箱和密码')
            return render(request, 'auth/login.html')

        try:
            response = requests.post(
                get_api_url('/v1/auth/login'),
                json={'email': email, 'password': password},
                timeout=30
            )

            if response.status_code == 200:
                data = response.json()
                if data.get('code') == 200:
                    user_data = data.get('data', {})
                    # 保存到session
                    request.session['token'] = user_data.get('token')
                    request.session['user_id'] = user_data.get('id')
                    request.session['email'] = user_data.get('email')
                    request.session['nickname'] = user_data.get('nickname')
                    messages.success(request, '登录成功')
                    # 登录后跳转到原始请求页面
                    next_url = request.session.pop('next', '/')
                    return redirect(next_url)
                else:
                    messages.error(request, data.get('message', '登录失败'))
            else:
                messages.error(request, '登录失败，请稍后重试')
        except Exception as e:
            messages.error(request, f'登录失败: {str(e)}')

    return render(request, 'auth/login.html')


def register_view(request):
    """注册页面"""
    # 如果已登录，直接跳转首页
    token = request.session.get('token')
    if token:
        return redirect('/')

    if request.method == 'POST':
        email = request.POST.get('email', '').strip()
        code = request.POST.get('code', '').strip()
        password = request.POST.get('password', '').strip()
        nickname = request.POST.get('nickname', '').strip()

        if not email or not code or not password:
            messages.error(request, '请填写完整信息')
            return render(request, 'auth/register.html')

        if len(password) < 6:
            messages.error(request, '密码长度至少6位')
            return render(request, 'auth/register.html')

        try:
            response = requests.post(
                get_api_url('/v1/auth/register'),
                json={
                    'email': email,
                    'code': code,
                    'password': password,
                    'nickname': nickname or email.split('@')[0]
                },
                timeout=30
            )

            if response.status_code == 200:
                data = response.json()
                if data.get('code') == 200:
                    user_data = data.get('data', {})
                    # 保存到session
                    request.session['token'] = user_data.get('token')
                    request.session['user_id'] = user_data.get('id')
                    request.session['email'] = user_data.get('email')
                    request.session['nickname'] = user_data.get('nickname')
                    messages.success(request, '注册成功，已赠送10积分')
                    return redirect('/')
                else:
                    messages.error(request, data.get('message', '注册失败'))
            else:
                messages.error(request, '注册失败，请稍后重试')
        except Exception as e:
            messages.error(request, f'注册失败: {str(e)}')

    return render(request, 'auth/register.html')


def logout_view(request):
    """退出登录"""
    token = request.session.get('token')
    # 调用后端logout API，删除Redis中的token
    if token:
        try:
            requests.post(
                get_api_url('/v1/auth/logout'),
                headers={'Authorization': f'Bearer {token}'},
                timeout=5
            )
        except Exception:
            pass
    request.session.flush()
    messages.success(request, '已退出登录')
    return redirect('/auth/login/')


@require_POST
def send_code_api(request):
    """发送验证码API"""
    try:
        data = json.loads(request.body)
        email = data.get('email', '').strip()
        code_type = data.get('type', 'register')

        if not email:
            return JsonResponse({'code': 400, 'message': '请输入邮箱'})

        response = requests.post(
            get_api_url('/v1/auth/send-code'),
            json={'email': email, 'type': code_type},
            timeout=30
        )

        if response.status_code == 200:
            return JsonResponse(response.json())
        else:
            return JsonResponse({'code': 500, 'message': '发送失败'})

    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)})
