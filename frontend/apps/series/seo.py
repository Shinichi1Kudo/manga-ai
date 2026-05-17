from datetime import date
from html import escape

from django.conf import settings
from django.http import HttpResponse


SITE_NAME = '海带 AI 内容智能创作平台'
DEFAULT_DESCRIPTION = (
    '海带 AI 内容智能创作平台提供AI短剧创作、分镜生成、角色资产、主体替换、'
    'GPT-Image2生图等内容智能创作工具，帮助创作者提升视频和图片生产效率。'
)
DEFAULT_KEYWORDS = '海带AI,AI短剧创作,AI内容创作,分镜生成,角色资产,主体替换,GPT-Image2生图,AI视频生成'

PUBLIC_PAGES = [
    {
        'path': '/',
        'title': SITE_NAME,
        'description': DEFAULT_DESCRIPTION,
        'keywords': DEFAULT_KEYWORDS,
        'changefreq': 'daily',
        'priority': '1.0',
    },
    {
        'path': '/subject-replacement/',
        'title': f'主体替换 - {SITE_NAME}',
        'description': '主体替换工具支持上传原视频和参考图，为人物或物品添加替换对象组，适合短视频、电商素材和AI内容创作场景。',
        'keywords': '主体替换,AI视频替换,视频主体替换,AI短视频工具,海带AI',
        'changefreq': 'weekly',
        'priority': '0.8',
    },
    {
        'path': '/gpt-image2/',
        'title': f'GPT-Image2 生图 - {SITE_NAME}',
        'description': 'GPT-Image2 生图工具支持文生图和图生图，可上传参考图并在后台生成高质量图片资产。',
        'keywords': 'GPT-Image2,AI生图,文生图,图生图,AI图片生成,海带AI',
        'changefreq': 'weekly',
        'priority': '0.8',
    },
]

PUBLIC_PAGE_BY_PATH = {page['path']: page for page in PUBLIC_PAGES}


def site_url():
    return getattr(settings, 'SITE_URL', 'https://www.yzmovie.cn').rstrip('/')


def absolute_url(path):
    if not path.startswith('/'):
        path = f'/{path}'
    return f'{site_url()}{path}'


def seo_context(request):
    path = request.path
    page = PUBLIC_PAGE_BY_PATH.get(path)
    if page:
        return {
            'seo': {
                'title': page['title'],
                'description': page['description'],
                'keywords': page['keywords'],
                'robots': 'index,follow',
                'canonical_url': absolute_url(page['path']),
                'image_url': absolute_url('/site-logo.png?v=20260515'),
                'site_name': SITE_NAME,
            }
        }

    return {
        'seo': {
            'title': SITE_NAME,
            'description': DEFAULT_DESCRIPTION,
            'keywords': DEFAULT_KEYWORDS,
            'robots': 'noindex,nofollow',
            'canonical_url': absolute_url(path),
            'image_url': absolute_url('/site-logo.png?v=20260515'),
            'site_name': SITE_NAME,
        }
    }


def robots_txt(request):
    body = '\n'.join([
        'User-agent: *',
        'Allow: /$',
        'Allow: /subject-replacement/$',
        'Allow: /gpt-image2/$',
        'Disallow: /api/',
        'Disallow: /admin/',
        'Disallow: /assets/',
        'Disallow: /credits/',
        'Disallow: /user/',
        'Disallow: /init/',
        'Disallow: /episodes/',
        '',
        f'Sitemap: {absolute_url("/sitemap.xml")}',
        '',
    ])
    return HttpResponse(body, content_type='text/plain; charset=utf-8')


def sitemap_xml(request):
    today = date.today().isoformat()
    urls = []
    for page in PUBLIC_PAGES:
        urls.append(
            '  <url>\n'
            f'    <loc>{escape(absolute_url(page["path"]))}</loc>\n'
            f'    <lastmod>{today}</lastmod>\n'
            f'    <changefreq>{page["changefreq"]}</changefreq>\n'
            f'    <priority>{page["priority"]}</priority>\n'
            '  </url>'
        )

    body = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
        + '\n'.join(urls)
        + '\n</urlset>\n'
    )
    return HttpResponse(body, content_type='application/xml; charset=utf-8')
