"""
后端 API 客户端
"""
import json
import requests
from typing import Dict, Any, Optional
from django.conf import settings


class BackendClient:
    """后端 API 客户端"""

    def __init__(self):
        self.base_url = settings.BACKEND_API_URL
        self.timeout = settings.BACKEND_API_TIMEOUT
        self.session = requests.Session()
        self.session.headers.update({
            'Content-Type': 'application/json',
            'Accept': 'application/json',
        })

    def _build_url(self, endpoint: str) -> str:
        return f"{self.base_url.rstrip('/')}{endpoint}"

    def _handle_response(self, response: requests.Response) -> Any:
        if response.status_code >= 400:
            error_data = response.json() if response.content else {}
            raise BackendAPIError(
                status_code=response.status_code,
                message=error_data.get('message', 'Unknown error'),
                data=error_data
            )

        if response.content:
            result = response.json()
            # 处理包装的返回结果
            if isinstance(result, dict):
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


class BackendAPIError(Exception):
    """后端 API 错误"""

    def __init__(self, status_code: int, message: str, data: Dict = None):
        self.status_code = status_code
        self.message = message
        self.data = data or {}
        super().__init__(f"API Error {status_code}: {message}")
