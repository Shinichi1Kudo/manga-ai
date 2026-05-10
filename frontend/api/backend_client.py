"""
后端 API 客户端
"""
import json
import requests
from typing import Dict, Any, Optional
from django.conf import settings


class BackendClient:
    """后端 API 客户端"""

    def __init__(self, token: Optional[str] = None):
        self.base_url = settings.BACKEND_API_URL
        self.timeout = settings.BACKEND_API_TIMEOUT
        self.session = requests.Session()
        self.session.headers.update({
            'Content-Type': 'application/json',
            'Accept': 'application/json',
        })
        # 设置认证Token
        if token:
            self.session.headers.update({
                'Authorization': f'Bearer {token}'
            })

    def _build_url(self, endpoint: str) -> str:
        return f"{self.base_url.rstrip('/')}{endpoint}"

    def _handle_response(self, response: requests.Response) -> Any:
        if response.status_code == 401:
            raise BackendAPIError(
                status_code=401,
                message='Token已失效，请重新登录',
                data={'code': 401}
            )
        if response.status_code >= 400:
            try:
                error_data = response.json() if response.content else {}
            except ValueError:
                error_data = {'message': '后端服务返回了非 JSON 错误页面'}
            raise BackendAPIError(
                status_code=response.status_code,
                message=error_data.get('message', 'Unknown error'),
                data=error_data
            )

        if response.content:
            try:
                result = response.json()
            except ValueError:
                raise BackendAPIError(
                    status_code=response.status_code,
                    message='后端服务返回了非 JSON 响应',
                    data={}
                )
            # 处理包装的返回结果
            if isinstance(result, dict):
                # 检查业务逻辑错误（HTTP 200 但 code != 200 或 success=false）
                if result.get('code') and result.get('code') != 200:
                    raise BackendAPIError(
                        status_code=result.get('code'),
                        message=result.get('message', 'Unknown error'),
                        data=result
                    )
                if result.get('success') is False:
                    raise BackendAPIError(
                        status_code=500,
                        message=result.get('message', 'Unknown error'),
                        data=result
                    )
                # 如果有 data 字段，返回 data 的值（可能是 null）
                if 'data' in result:
                    return result['data']
                # 如果是成功响应但没有 data 字段，说明数据为 null
                if result.get('code') == 200 or result.get('success'):
                    return None
            return result
        return None

    def get(self, endpoint: str, params: Optional[Dict] = None) -> Any:
        """GET 请求"""
        response = self.session.get(
            self._build_url(endpoint),
            params=params,
            timeout=self.timeout
        )
        return self._handle_response(response)

    def post(self, endpoint: str, data: Optional[Dict] = None) -> Any:
        """POST 请求"""
        response = self.session.post(
            self._build_url(endpoint),
            data=json.dumps(data) if data else None,
            timeout=self.timeout
        )
        return self._handle_response(response)

    def put(self, endpoint: str, data: Optional[Dict] = None) -> Any:
        """PUT 请求"""
        response = self.session.put(
            self._build_url(endpoint),
            data=json.dumps(data) if data else None,
            timeout=self.timeout
        )
        return self._handle_response(response)

    def delete(self, endpoint: str) -> Any:
        """DELETE 请求"""
        response = self.session.delete(
            self._build_url(endpoint),
            timeout=self.timeout
        )
        return self._handle_response(response)

    def upload(self, endpoint: str, files: Dict, data: Optional[Dict] = None) -> Any:
        """上传文件"""
        # 上传文件时需要移除 Content-Type，让 requests 自动设置
        headers = dict(self.session.headers)
        headers.pop('Content-Type', None)

        response = requests.post(
            self._build_url(endpoint),
            files=files,
            data=data,
            headers=headers,
            timeout=self.timeout
        )
        return self._handle_response(response)


class BackendAPIError(Exception):
    """后端 API 错误"""

    def __init__(self, status_code: int, message: str, data: Dict = None):
        self.status_code = status_code
        self.message = message
        self.data = data or {}
        super().__init__(f"API Error {status_code}: {message}")
