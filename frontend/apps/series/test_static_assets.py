from html.parser import HTMLParser
from pathlib import Path
import re

from django.conf import settings
from django.test import SimpleTestCase


class HeadAssetParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.in_head = False
        self.elements = []

    def handle_starttag(self, tag, attrs):
        if tag == 'head':
            self.in_head = True
        elif self.in_head and tag in ('link', 'script'):
            self.elements.append((tag, dict(attrs)))

    def handle_endtag(self, tag):
        if tag == 'head':
            self.in_head = False


def is_expected_static_asset_url(url, token):
    if not isinstance(url, str) or not url.startswith(token):
        return False
    suffix = url[len(token) :]
    return not any(character.isspace() for character in suffix) and (
        not suffix or suffix.startswith(('?', '#'))
    )


def is_external_render_dependency_url(url):
    return isinstance(url, str) and url.strip().lower().startswith(
        ('http://', 'https://', '//')
    )


class SiteStaticAssetTests(SimpleTestCase):
    template_paths = (
        'templates/base.html',
        'templates/auth/login.html',
        'templates/auth/register.html',
        'templates/auth/bind_phone.html',
    )

    def template_source(self, relative_path):
        template_path = Path(settings.BASE_DIR) / relative_path
        self.assertTrue(template_path.is_file(), f'Missing template: {relative_path}')
        return template_path.read_text(encoding='utf-8')

    def head_elements(self, source):
        parser = HeadAssetParser()
        parser.feed(source)
        parser.close()
        return parser.elements

    def test_static_asset_url_matches_only_the_asset_with_optional_query_or_fragment(self):
        token = "{% static 'css/site.css' %}"
        for url in (token, f'{token}?v=1', f'{token}#critical'):
            with self.subTest(url=url):
                self.assertTrue(is_expected_static_asset_url(url, token))
        for url in (
            f'{token}.missing',
            f'{token}/broken',
            f'{token} ?v=1',
            f' {token}',
            f'{token}?v=one two',
        ):
            with self.subTest(url=url):
                self.assertFalse(is_expected_static_asset_url(url, token))

    def test_external_render_dependency_url_includes_protocol_relative_urls(self):
        for url in ('http://example.com/site.css', ' HTTPS://example.com/app.js ', '//cdn.example.com/app.js'):
            with self.subTest(url=url):
                self.assertTrue(is_external_render_dependency_url(url))
        self.assertFalse(is_external_render_dependency_url('/static/site.css'))

    def test_entry_templates_load_local_css_before_local_deferred_icons(self):
        for relative_path in self.template_paths:
            with self.subTest(template=relative_path):
                elements = self.head_elements(self.template_source(relative_path))
                css_index = next(
                    (
                        index
                        for index, (tag, attrs) in enumerate(elements)
                        if tag == 'link'
                        and 'stylesheet' in (attrs.get('rel') or '').lower().split()
                        and is_expected_static_asset_url(
                            attrs.get('href'), "{% static 'css/site.css' %}"
                        )
                    ),
                    None,
                )
                lucide_index = next(
                    (
                        index
                        for index, (tag, attrs) in enumerate(elements)
                        if tag == 'script'
                        and is_expected_static_asset_url(
                            attrs.get('src'),
                            "{% static 'vendor/lucide/lucide.min.js' %}",
                        )
                        and 'defer' in attrs
                    ),
                    None,
                )
                self.assertIsNotNone(
                    css_index,
                    f'{relative_path}: missing local stylesheet link in <head>',
                )
                self.assertIsNotNone(
                    lucide_index,
                    f'{relative_path}: missing deferred local Lucide script in <head>',
                )
                if css_index is not None and lucide_index is not None:
                    self.assertLess(
                        css_index,
                        lucide_index,
                        f'{relative_path}: local stylesheet must precede Lucide script',
                    )

    def test_entry_templates_bootstrap_lucide_before_deferred_bundle_load(self):
        bootstrap = re.compile(
            r"window\.lucide\s*=\s*window\.lucide\s*\|\|\s*"
            r"\{\s*createIcons\(\)\s*\{\s*\}\s*\}",
            re.DOTALL,
        )
        deferred_bundle = re.compile(
            r'<script\s+defer\s+src="\{% static \'vendor/lucide/lucide\.min\.js\' %\}\?v=20260801"'
            r'\s+onload="if \(window\.lucide && typeof window\.lucide\.createIcons === \'function\'\) '
            r'window\.lucide\.createIcons\(\);"></script>',
            re.DOTALL,
        )
        for relative_path in self.template_paths:
            with self.subTest(template=relative_path):
                source = self.template_source(relative_path)
                bootstrap_match = bootstrap.search(source)
                bundle_match = deferred_bundle.search(source)
                self.assertIsNotNone(
                    bootstrap_match,
                    f'{relative_path}: missing safe Lucide compatibility bootstrap',
                )
                self.assertIsNotNone(
                    bundle_match,
                    f'{relative_path}: missing deferred Lucide onload icon render',
                )
                if bootstrap_match is not None and bundle_match is not None:
                    self.assertLess(
                        bootstrap_match.start(),
                        bundle_match.start(),
                        f'{relative_path}: Lucide compatibility bootstrap must precede bundle',
                    )

    def test_entry_templates_have_no_render_time_external_dependencies(self):
        forbidden = (
            'cdn.tailwindcss.com',
            'fonts.googleapis.com',
            'fonts.gstatic.com',
            'unpkg.com/lucide',
            'tailwind.config =',
        )
        for relative_path in self.template_paths:
            with self.subTest(template=relative_path):
                source = self.template_source(relative_path)
                for tag, attrs in self.head_elements(source):
                    if tag == 'link' and 'stylesheet' in (attrs.get('rel') or '').lower().split():
                        url = attrs.get('href') or ''
                    elif tag == 'script':
                        url = attrs.get('src') or ''
                    else:
                        continue
                    self.assertFalse(
                        is_external_render_dependency_url(url),
                        f'{relative_path}: external {tag} dependency in <head>: {url}',
                    )
                for marker in forbidden:
                    self.assertFalse(
                        marker in source,
                        f'{relative_path}: forbidden render-time dependency: {marker}',
                    )

    def test_compiled_assets_exist_and_contain_critical_layout_rules(self):
        static_root = Path(settings.BASE_DIR) / 'static'
        css_path = static_root / 'css/site.css'
        self.assertTrue(css_path.is_file(), f'Missing compiled stylesheet: {css_path}')
        if not css_path.is_file():
            return

        css = css_path.read_text(encoding='utf-8')
        for selector, declaration in (
            ('.fixed', 'position:fixed'),
            ('.flex', 'display:flex'),
            ('.grid', 'display:grid'),
            ('.max-w-7xl', 'max-width:80rem'),
            ('.min-h-screen', 'min-height:100vh'),
        ):
            property_name, value = declaration.split(':', 1)
            self.assertRegex(
                css,
                rf'{re.escape(selector)}\s*\{{\s*{property_name}\s*:\s*{value}\s*;?\s*\}}',
                f'Missing generated rule: {selector}{{{declaration}}}',
            )
        self.assertRegex(
            css,
            r"@font-face\s*\{[^}]*url\((?:['\"])?\.\./fonts/inter-latin-variable\.woff2",
            'Missing @font-face reference to the local Inter font',
        )

        font_path = static_root / 'fonts/inter-latin-variable.woff2'
        self.assertTrue(font_path.is_file(), f'Missing local Inter font: {font_path}')
        if font_path.is_file():
            font = font_path.read_bytes()
            self.assertTrue(font, f'Local Inter font is empty: {font_path}')
            self.assertTrue(font.startswith(b'wOF2'), 'Local Inter font is not WOFF2')

        lucide_path = static_root / 'vendor/lucide/lucide.min.js'
        self.assertTrue(lucide_path.is_file(), f'Missing local Lucide bundle: {lucide_path}')
        if lucide_path.is_file():
            lucide = lucide_path.read_text(encoding='utf-8')
            self.assertTrue(lucide, f'Local Lucide bundle is empty: {lucide_path}')
            self.assertIn('createIcons', lucide, 'Local Lucide bundle lacks createIcons API')

    def test_lucide_placeholders_reserve_space_without_overriding_tailwind_sizes(self):
        static_root = Path(settings.BASE_DIR) / 'static'
        source_path = static_root / 'css/site.src.css'
        css_path = static_root / 'css/site.css'
        self.assertTrue(source_path.is_file(), f'Missing stylesheet source: {source_path}')
        self.assertTrue(css_path.is_file(), f'Missing compiled stylesheet: {css_path}')
        if not source_path.is_file() or not css_path.is_file():
            return

        source = source_path.read_text(encoding='utf-8')
        fallback_rule = re.compile(
            r'\[data-lucide\]\s*\{\s*width\s*:\s*1em\s*;\s*'
            r'height\s*:\s*1em\s*;\s*vertical-align\s*:\s*middle\s*;\s*\}',
            re.DOTALL,
        )
        display_rule = re.compile(
            r'\[data-lucide\]\s*\{\s*display\s*:\s*inline-block\s*;\s*'
            r'flex-shrink\s*:\s*0\s*;\s*\}',
            re.DOTALL,
        )
        fallback_match = fallback_rule.search(source)
        display_match = display_rule.search(source)
        utilities_index = source.find('@tailwind utilities;')
        self.assertIsNotNone(fallback_match, 'Missing pre-utility Lucide size fallback')
        self.assertIsNotNone(display_match, 'Missing post-utility Lucide display fallback')
        self.assertNotEqual(utilities_index, -1, 'Missing Tailwind utilities directive')
        if fallback_match is not None and display_match is not None and utilities_index != -1:
            self.assertLess(
                fallback_match.start(),
                utilities_index,
                'Lucide fallback dimensions must precede Tailwind utilities',
            )
            self.assertGreater(
                display_match.start(),
                utilities_index,
                'Lucide display fallback must follow Tailwind utilities',
            )

        compiled = css_path.read_text(encoding='utf-8')
        self.assertRegex(
            compiled,
            re.compile(
                r'\[data-lucide\]\s*\{\s*width\s*:\s*1em\s*;\s*'
                r'height\s*:\s*1em\s*;\s*vertical-align\s*:\s*middle\s*;?\s*\}',
                re.DOTALL,
            ),
            'Compiled stylesheet is missing Lucide size fallback',
        )
        self.assertRegex(
            compiled,
            re.compile(
                r'\[data-lucide\]\s*\{\s*display\s*:\s*inline-block\s*;\s*'
                r'flex-shrink\s*:\s*0\s*;?\s*\}',
                re.DOTALL,
            ),
            'Compiled stylesheet is missing Lucide display fallback',
        )

    def test_tailwind_license_is_distributed_and_build_script_copies_it(self):
        static_root = Path(settings.BASE_DIR) / 'static'
        license_path = static_root / 'vendor/licenses/tailwind-MIT.txt'
        self.assertTrue(license_path.is_file(), f'Missing Tailwind license: {license_path}')
        if license_path.is_file():
            license_text = license_path.read_text(encoding='utf-8')
            self.assertTrue(license_text.strip(), 'Tailwind license is empty')
            self.assertIn('MIT License', license_text)

            package_license_path = (
                Path(settings.BASE_DIR) / 'node_modules/tailwindcss/LICENSE'
            )
            if package_license_path.is_file():
                self.assertEqual(
                    license_text,
                    package_license_path.read_text(encoding='utf-8'),
                    'Distributed Tailwind license must match the pinned package license',
                )

        package_path = Path(settings.BASE_DIR) / 'package.json'
        package = package_path.read_text(encoding='utf-8')
        self.assertRegex(
            package,
            r'"build:vendor"\s*:\s*"[^"]*mkdir -p static/fonts static/vendor/lucide '
            r'static/vendor/licenses[^"]*cp node_modules/tailwindcss/LICENSE '
            r'static/vendor/licenses/tailwind-MIT\.txt[^"]*"',
            'build:vendor must copy the pinned Tailwind license into distributed assets',
        )
