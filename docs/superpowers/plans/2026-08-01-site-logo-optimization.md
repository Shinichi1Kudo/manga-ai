# Site Logo Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace every site-brand image with optimized local derivatives of the user-provided Logo while preserving the existing Chinese wordmark and backward-compatible Logo URLs.

**Architecture:** Generate purpose-sized WebP and PNG assets under Django's existing `static/images` directory. Templates load the small WebP files directly with a local PNG fallback, SEO uses the 512px PNG, and the legacy Logo/favicon endpoints stream local files with immutable cache headers instead of calling the backend or OSS.

**Tech Stack:** Django templates and test client, Pillow image processing, Nginx/Django static files, systemd deployment.

---

## File map

- Modify `frontend/static/images/logo.png`: 512px optimized PNG and legacy URL source.
- Modify `frontend/static/images/favicon.png`: 64px browser icon.
- Create `frontend/static/images/site-logo-128.webp`: routine page Logo.
- Create `frontend/static/images/site-logo-256.webp`: Retina and Hero Logo.
- Create `frontend/static/images/apple-touch-icon.png`: iOS icon.
- Modify `frontend/apps/series/tests.py`: asset, endpoint, template, SEO, and wordmark regression coverage.
- Modify `frontend/apps/series/views.py`: local cached Logo/favicon responses.
- Modify `frontend/manga_ai/urls.py`: route `/favicon.ico` to the local favicon response.
- Modify `frontend/templates/base.html`: local navbar Logo and icon declarations.
- Modify `frontend/templates/series/series_list.html`: local Hero Logo.
- Modify `frontend/templates/auth/login.html`: local login Logo.
- Modify `frontend/templates/auth/register.html`: local registration Logo.
- Modify `frontend/templates/auth/bind_phone.html`: local phone-binding Logo.
- Modify `frontend/apps/series/seo.py`: local SEO share image.

### Task 1: Generate optimized brand assets

**Files:**
- Modify: `frontend/apps/series/tests.py`
- Modify: `frontend/static/images/logo.png`
- Modify: `frontend/static/images/favicon.png`
- Create: `frontend/static/images/site-logo-128.webp`
- Create: `frontend/static/images/site-logo-256.webp`
- Create: `frontend/static/images/apple-touch-icon.png`

- [ ] **Step 1: Write the failing asset contract test**

Add `from PIL import Image` and this method to `SiteBrandingTests`:

```python
def test_brand_assets_are_local_and_optimized(self):
    image_root = Path(settings.BASE_DIR) / 'static/images'
    expected = {
        'site-logo-128.webp': ((128, 128), 'WEBP', 5_000),
        'site-logo-256.webp': ((256, 256), 'WEBP', 8_000),
        'logo.png': ((512, 512), 'PNG', 220_000),
        'favicon.png': ((64, 64), 'PNG', 8_000),
        'apple-touch-icon.png': ((180, 180), 'PNG', 40_000),
    }

    for filename, (size, image_format, max_bytes) in expected.items():
        with self.subTest(filename=filename):
            path = image_root / filename
            self.assertTrue(path.is_file())
            with Image.open(path) as image:
                self.assertEqual(image.size, size)
                self.assertEqual(image.format, image_format)
            self.assertLessEqual(path.stat().st_size, max_bytes)
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
/tmp/yzmovie-frontend-test-venv2/bin/python manage.py test \
  apps.series.tests.SiteBrandingTests.test_brand_assets_are_local_and_optimized
```

Expected: FAIL because the WebP and Apple icon files do not exist and the existing PNG files exceed their size limits.

- [ ] **Step 3: Generate exact-size derivatives from the supplied Logo**

Run from the repository root:

```bash
/tmp/yzmovie-frontend-test-venv2/bin/python - <<'PY'
from pathlib import Path
from PIL import Image

source = Path('/var/folders/m9/sblt9s2j3nggxhsyp8rsrzc40000gn/T/codex-clipboard-32310ce6-eaf4-4a6c-a134-2ad622aa091b.png')
output = Path('frontend/static/images')
image = Image.open(source).convert('RGB')

for size, filename in ((128, 'site-logo-128.webp'), (256, 'site-logo-256.webp')):
    resized = image.resize((size, size), Image.Resampling.LANCZOS)
    resized.save(output / filename, 'WEBP', quality=85, method=6)

for size, filename in ((512, 'logo.png'), (64, 'favicon.png'), (180, 'apple-touch-icon.png')):
    resized = image.resize((size, size), Image.Resampling.LANCZOS)
    resized.save(output / filename, 'PNG', optimize=True, compress_level=9)
PY
```

- [ ] **Step 4: Run the asset test and verify GREEN**

Run the Task 1 test again. Expected: `Ran 1 test ... OK`.

- [ ] **Step 5: Commit the assets and contract**

```bash
git add frontend/apps/series/tests.py frontend/static/images/logo.png \
  frontend/static/images/favicon.png frontend/static/images/site-logo-128.webp \
  frontend/static/images/site-logo-256.webp frontend/static/images/apple-touch-icon.png
git commit -m "feat: add optimized local brand assets"
```

### Task 2: Remove backend and OSS dependencies from legacy Logo endpoints

**Files:**
- Modify: `frontend/apps/series/tests.py`
- Modify: `frontend/apps/series/views.py`
- Modify: `frontend/manga_ai/urls.py`

- [ ] **Step 1: Replace the endpoint tests with local-file expectations**

Use this helper inside `AnonymousPublicEndpointTests`:

```python
def assert_local_brand_response(self, url, filename):
    expected = (Path(settings.BASE_DIR) / 'static/images' / filename).read_bytes()
    with patch('apps.series.views.BackendClient') as backend_client, \
            patch('apps.series.views.requests.get') as requests_get:
        response = self.client.get(url)
        body = b''.join(response.streaming_content)

    self.assertEqual(response.status_code, 200)
    self.assertEqual(body, expected)
    self.assertEqual(response['Content-Type'], 'image/png')
    self.assertEqual(response['Cache-Control'], 'public, max-age=31536000, immutable')
    backend_client.assert_not_called()
    requests_get.assert_not_called()

def test_site_logo_endpoint_is_public(self):
    self.assert_local_brand_response('/api/v1/common/site-logo/', 'logo.png')

def test_site_logo_png_endpoint_is_public(self):
    self.assert_local_brand_response('/site-logo.png', 'logo.png')

def test_favicon_endpoint_is_public(self):
    self.assert_local_brand_response('/favicon.ico', 'favicon.png')
```

- [ ] **Step 2: Run the endpoint tests and verify RED**

Run:

```bash
/tmp/yzmovie-frontend-test-venv2/bin/python manage.py test \
  apps.series.tests.AnonymousPublicEndpointTests.test_site_logo_endpoint_is_public \
  apps.series.tests.AnonymousPublicEndpointTests.test_site_logo_png_endpoint_is_public \
  apps.series.tests.AnonymousPublicEndpointTests.test_favicon_endpoint_is_public
```

Expected: FAIL because Logo endpoints still call the backend/OSS and favicon returns 204.

- [ ] **Step 3: Implement local immutable image responses**

In `frontend/apps/series/views.py`, import `settings`, `FileResponse`, and `Path`, then replace `site_logo` and add `site_favicon`:

```python
BRAND_CACHE_CONTROL = 'public, max-age=31536000, immutable'


def _brand_asset_response(filename, download_name):
    asset_path = Path(settings.BASE_DIR) / 'static/images' / filename
    response = FileResponse(asset_path.open('rb'), content_type='image/png')
    response['Content-Disposition'] = f'inline; filename="{download_name}"'
    response['Cache-Control'] = BRAND_CACHE_CONTROL
    return response


def site_logo(request):
    return _brand_asset_response('logo.png', 'site-logo.png')


def site_favicon(request):
    return _brand_asset_response('favicon.png', 'favicon.png')
```

In `frontend/manga_ai/urls.py`, import `apps.series.views` as `series_views` and replace the 204 lambda:

```python
path('favicon.ico', series_views.site_favicon, name='site_favicon'),
```

- [ ] **Step 4: Run the endpoint tests and verify GREEN**

Run the Task 2 tests again. Expected: `Ran 3 tests ... OK`.

- [ ] **Step 5: Commit the local endpoint implementation**

```bash
git add frontend/apps/series/tests.py frontend/apps/series/views.py frontend/manga_ai/urls.py
git commit -m "fix: serve brand images locally"
```

### Task 3: Replace every visible and SEO Logo reference

**Files:**
- Modify: `frontend/apps/series/tests.py`
- Modify: `frontend/templates/base.html`
- Modify: `frontend/templates/series/series_list.html`
- Modify: `frontend/templates/auth/login.html`
- Modify: `frontend/templates/auth/register.html`
- Modify: `frontend/templates/auth/bind_phone.html`
- Modify: `frontend/apps/series/seo.py`

- [ ] **Step 1: Write failing template and SEO assertions**

Rename the old OSS test to `test_brand_uses_local_optimized_logo_images`. Include all six templates and assert:

```python
self.assertNotIn('/site-logo.png?v=20260515', combined)
self.assertIn("images/site-logo-128.webp", combined)
self.assertIn("images/site-logo-256.webp", combined)
self.assertIn("images/logo.png", combined)
self.assertIn("images/favicon.png", combined)
self.assertIn("images/apple-touch-icon.png", combined)
self.assertGreaterEqual(combined.count('decoding="async"'), 5)
self.assertIn('class="brand-logo-img', combined)
self.assertIn('brand-name">海带</span>', combined)
self.assertIn('brand-subtitle">AI 内容智能创作平台</span>', combined)
```

Extend `test_home_page_has_search_engine_meta_tags` with:

```python
self.assertContains(
    response,
    '<meta property="og:image" content="https://www.yzmovie.cn/static/images/logo.png?v=20260801">',
    html=False,
)
```

- [ ] **Step 2: Run the branding and SEO tests and verify RED**

Run:

```bash
/tmp/yzmovie-frontend-test-venv2/bin/python manage.py test \
  apps.series.tests.SiteBrandingTests.test_brand_uses_local_optimized_logo_images \
  apps.series.tests.SearchEngineVisibilityTests.test_home_page_has_search_engine_meta_tags
```

Expected: FAIL because templates and SEO still use `/site-logo.png?v=20260515`.

- [ ] **Step 3: Replace template image markup**

Load the static tag library in the base, login, register, bind-phone, and series-list templates. Use this exact navbar/login/register image markup while preserving its surrounding shell:

```html
<img src="{% static 'images/logo.png' %}?v=20260801"
     srcset="{% static 'images/site-logo-128.webp' %}?v=20260801 1x, {% static 'images/site-logo-256.webp' %}?v=20260801 2x"
     alt="海带 AI 内容智能创作平台 Logo"
     class="brand-logo-img"
     width="128" height="128" decoding="async" fetchpriority="high">
```

For the homepage Hero image use the same `src` and `srcset` with this exact tail:

```html
alt="海带 AI 内容智能创作平台 Logo"
class="brand-logo-img hero-site-logo mx-auto mb-5"
width="256" height="256" decoding="async" fetchpriority="high">
```

For the bind-phone image use the same `src`, `srcset`, alt text, and `width="128" height="128" decoding="async" fetchpriority="high"` without adding a class. In `base.html`, replace the two icon links with:

```html
<link rel="icon" type="image/png" sizes="64x64" href="{% static 'images/favicon.png' %}?v=20260801">
<link rel="apple-touch-icon" sizes="180x180" href="{% static 'images/apple-touch-icon.png' %}?v=20260801">
```

- [ ] **Step 4: Point SEO metadata at the local optimized PNG**

In both branches of `seo_context`, set:

```python
'image_url': absolute_url('/static/images/logo.png?v=20260801'),
```

- [ ] **Step 5: Run the branding and SEO tests and verify GREEN**

Run the Task 3 tests again. Expected: `Ran 2 tests ... OK`.

- [ ] **Step 6: Commit template and SEO references**

```bash
git add frontend/apps/series/tests.py frontend/templates/base.html \
  frontend/templates/series/series_list.html frontend/templates/auth/login.html \
  frontend/templates/auth/register.html frontend/templates/auth/bind_phone.html \
  frontend/apps/series/seo.py
git commit -m "feat: use optimized logo across site"
```

### Task 4: Full verification and static collection

**Files:**
- Verify all files from Tasks 1-3.

- [ ] **Step 1: Run the complete frontend suite**

```bash
/tmp/yzmovie-frontend-test-venv2/bin/python manage.py test \
  apps.auth.tests apps.role.tests apps.series.tests apps.series.test_payment_qr
```

Expected: all tests pass with no failures.

- [ ] **Step 2: Collect static files into an isolated directory**

```bash
/tmp/yzmovie-frontend-test-venv2/bin/python manage.py collectstatic --noinput
```

Expected: all five brand assets exist in `frontend/staticfiles/images/`.

- [ ] **Step 3: Verify source and collected asset sizes**

```bash
file frontend/static/images/{logo.png,favicon.png,apple-touch-icon.png,site-logo-128.webp,site-logo-256.webp}
stat -f '%N %z bytes' frontend/static/images/{logo.png,favicon.png,apple-touch-icon.png,site-logo-128.webp,site-logo-256.webp}
git diff --check
git status -sb
```

Expected: dimensions and formats match Task 1, each asset stays under its test limit, and only intended files are changed.

### Task 5: Push and deploy the public frontend

**Files:**
- No new source files; deploy committed changes from `dev_20260629_pay`.

- [ ] **Step 1: Push the branch**

```bash
git push origin dev_20260629_pay
```

Expected: remote branch advances to the final Logo implementation commit.

- [ ] **Step 2: Deploy through Tencent OrcaTerm**

Run on the public server:

```bash
sudo git -C /root/.openclaw/workspace/manga-ai pull --ff-only origin dev_20260629_pay
sudo /usr/bin/python3 /root/.openclaw/workspace/manga-ai/frontend/manage.py collectstatic --noinput
sudo systemctl restart manga-ai-frontend.service
sudo systemctl is-active manga-ai-frontend.service
sudo git -C /root/.openclaw/workspace/manga-ai rev-parse HEAD
```

Expected: fast-forward succeeds, service prints `active`, and server HEAD matches the pushed commit.

- [ ] **Step 3: Verify public HTTP behavior and payload sizes**

Check `/`, `/auth/login/`, `/auth/register/`, `/auth/bind-phone/`, `/favicon.ico`, `/site-logo.png`, and all five `/static/images/` assets. Expected: page and image requests return 200, HTML references the new versioned static paths, Logo/favicon content types are images, and the routine WebP Logo is below 8 KB.

- [ ] **Step 4: Visually inspect representative pages**

Open the homepage, login page, registration page, and phone-binding page. Expected: all use the supplied Logo, existing “海带 / AI 内容智能创作平台” text remains, and no layout shift or stretched Logo is visible.
