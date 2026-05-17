"""
Django 管理命令：推送 URL 到百度
用法: python manage.py baidu_push [url1] [url2] ...
      python manage.py baidu_push --sitemap  # 推送 sitemap 中的所有 URL
"""
from django.core.management.base import BaseCommand
from apps.series.baidu_push import push_urls
from apps.series.seo import PUBLIC_PAGES, site_url


class Command(BaseCommand):
    help = '推送 URL 到百度搜索资源平台'

    def add_arguments(self, parser):
        parser.add_argument('urls', nargs='*', help='要推送的 URL（可多个）')
        parser.add_argument('--sitemap', action='store_true', help='推送 sitemap 中所有公开页面')

    def handle(self, *args, **options):
        urls = []

        if options['sitemap']:
            urls = [page['path'] for page in PUBLIC_PAGES]
            self.stdout.write(f'📄 从 sitemap 收集到 {len(urls)} 个页面')
        elif options['urls']:
            urls = options['urls']
        else:
            self.stderr.write('❌ 请提供 URL 或使用 --sitemap')
            return

        base = site_url()
        full_urls = []
        for u in urls:
            if u.startswith('http'):
                full_urls.append(u)
            elif u.startswith('/'):
                full_urls.append(f'{base}{u}')
            else:
                full_urls.append(f'{base}/{u}')

        self.stdout.write(f'🚀 推送 {len(full_urls)} 个 URL 到百度...')
        self.stdout.write('\n'.join(f'  {u}' for u in full_urls))

        result = push_urls(full_urls)
        self.stdout.write(self.style.SUCCESS(
            f'✅ 成功: {result.get("success", 0)}, 今日剩余: {result.get("remain", 0)}'
        ))

        if result.get('not_same_site'):
            self.stderr.write(f'⚠️  非本站URL: {result["not_same_site"]}')
        if result.get('not_valid'):
            self.stderr.write(f'⚠️  无效URL: {result["not_valid"]}')
