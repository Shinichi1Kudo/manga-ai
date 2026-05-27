import json
import requests
from django.shortcuts import render, redirect
from django.http import JsonResponse
from django.views.decorators.http import require_POST
from django.contrib import messages
from django.conf import settings

BACKEND_SESSION = requests.Session()
BACKEND_SESSION.trust_env = False


def get_api_url(path):
    """获取后端API完整URL"""
    return f"{settings.BACKEND_API_URL}{path}"


def is_safe_next_url(next_url):
    """登录后只跳转到页面地址，避免跳到 JSON API。"""
    return (
        isinstance(next_url, str)
        and next_url.startswith('/')
        and not next_url.startswith('//')
        and not next_url.startswith('/api/')
    )


def login_view(request):
    """登录页面"""
    # 如果已登录，直接跳转首页
    token = request.session.get('token')
    if token:
        return redirect('/')

    if request.method == 'POST':
        account = (request.POST.get('account') or request.POST.get('email') or '').strip()
        password = request.POST.get('password', '').strip()
        code = request.POST.get('code', '').strip()
        login_type = request.POST.get('login_type', 'password').strip() or 'password'

        if not account or (login_type == 'password' and not password) or (login_type == 'sms' and not code):
            messages.error(request, '请填写完整登录信息')
            return render(request, 'auth/login.html')

        try:
            response = BACKEND_SESSION.post(
                get_api_url('/v1/auth/login'),
                json={
                    'account': account,
                    'password': password,
                    'code': code,
                    'loginType': login_type,
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
                    request.session['phone'] = user_data.get('phone')
                    request.session['nickname'] = user_data.get('nickname')
                    request.session['requires_phone_binding'] = bool(user_data.get('requiresPhoneBinding'))
                    if user_data.get('requiresPhoneBinding'):
                        return redirect('/auth/bind-phone/')
                    messages.success(request, '登录成功')
                    # 登录后跳转到原始请求页面
                    next_url = request.session.pop('next', '/')
                    if not is_safe_next_url(next_url):
                        next_url = '/'
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
        phone = request.POST.get('phone', '').strip()
        code = request.POST.get('code', '').strip()
        password = request.POST.get('password', '').strip()
        nickname = request.POST.get('nickname', '').strip()

        if not phone or not code or not password:
            messages.error(request, '请填写完整信息')
            return render(request, 'auth/register.html')

        if len(password) < 6:
            messages.error(request, '密码长度至少6位')
            return render(request, 'auth/register.html')

        try:
            response = BACKEND_SESSION.post(
                get_api_url('/v1/auth/register'),
                json={
                    'phone': phone,
                    'code': code,
                    'password': password,
                    'nickname': nickname or f"用户{phone[-4:]}"
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
                    request.session['phone'] = user_data.get('phone')
                    request.session['nickname'] = user_data.get('nickname')
                    request.session['requires_phone_binding'] = bool(user_data.get('requiresPhoneBinding'))
                    messages.success(request, '注册成功，已赠送20积分')
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
            BACKEND_SESSION.post(
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
        phone = data.get('phone', '').strip()
        code_type = data.get('type', 'register')

        if not phone:
            return JsonResponse({'code': 400, 'message': '请输入手机号'})

        response = BACKEND_SESSION.post(
            get_api_url('/v1/auth/send-code'),
            json={'phone': phone, 'type': code_type},
            timeout=30
        )

        if response.status_code == 200:
            return JsonResponse(response.json())
        else:
            return JsonResponse({'code': 500, 'message': '发送失败'})

    except Exception as e:
        return JsonResponse({'code': 500, 'message': str(e)})


def bind_phone_view(request):
    """老邮箱用户绑定手机号"""
    token = request.session.get('token')
    if not token:
        return redirect('/auth/login/')

    if request.method == 'POST':
        phone = request.POST.get('phone', '').strip()
        code = request.POST.get('code', '').strip()
        if not phone or not code:
            messages.error(request, '请填写手机号和验证码')
            return render(request, 'auth/bind_phone.html')

        try:
            response = BACKEND_SESSION.post(
                get_api_url('/v1/auth/bind-phone'),
                json={'phone': phone, 'code': code},
                headers={'Authorization': f'Bearer {token}'},
                timeout=30
            )
            if response.status_code == 200:
                data = response.json()
                if data.get('code') == 200:
                    user_data = data.get('data', {})
                    request.session['token'] = user_data.get('token') or token
                    request.session['user_id'] = user_data.get('id')
                    request.session['email'] = user_data.get('email')
                    request.session['phone'] = user_data.get('phone')
                    request.session['nickname'] = user_data.get('nickname')
                    request.session['requires_phone_binding'] = bool(user_data.get('requiresPhoneBinding'))
                    messages.success(request, '手机号绑定成功')
                    return redirect('/')
                messages.error(request, data.get('message', '绑定失败'))
            else:
                messages.error(request, '绑定失败，请稍后重试')
        except Exception as e:
            messages.error(request, f'绑定失败: {str(e)}')

    return render(request, 'auth/bind_phone.html')
