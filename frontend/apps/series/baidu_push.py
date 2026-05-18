"""
百度搜索资源平台 URL 推送
"""
import logging
import requests

logger = logging.getLogger(__name__)

# 百度推送 API 配置
BAIDU_PUSH_URL = 'http://data.zz.baidu.com/urls'
BAIDU_SITE = 'https://www.yzmovie.cn'
BAIDU_TOKEN = 'mfxt6p3ivwbASMYk'


def push_urls(urls: list[str]) -> dict:
    """
    推送 URL 到百度搜索资源平台
    返回 API 响应，包含 success/remain 等字段
    """
    if not urls:
        return {'success': 0, 'remain': 0}

    try:
        full_urls = []
        for u in urls:
            if u.startswith('http'):
                full_urls.append(u)
            else:
                full_urls.append(f'{BAIDU_SITE}{u}' if u.startswith('/') else f'{BAIDU_SITE}/{u}')

        body = '\n'.join(full_urls)
        resp = requests.post(
            f'{BAIDU_PUSH_URL}?site={BAIDU_SITE}&token={BAIDU_TOKEN}',
            data=body.encode('utf-8'),
            headers={'Content-Type': 'text/plain'},
            timeout=10,
        )
        result = resp.json()
        logger.info(f'百度推送: success={result.get("success")}, remain={result.get("remain")}, urls={full_urls}')
        return result
    except Exception as e:
        logger.error(f'百度推送失败: {e}')
        return {'success': 0, 'remain': 0, 'error': str(e)}


def push_single_url(path: str) -> dict:
    """推送单个 URL"""
    return push_urls([path])
