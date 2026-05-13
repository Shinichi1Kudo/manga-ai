# Chrome 不安全混合内容修复

## 背景

线上 HTTPS 页面仍有两类资源可能走 HTTP：

- 首页系列处理进度 WebSocket 写死为 `http://localhost:8081/api/ws`，部署后不会经由站点域名和 nginx 的 HTTPS/WSS 代理。
- 后端 OSS 签名 URL 直接返回 SDK 生成的地址，可能以 `http://` 开头，浏览器会把页面标记为不安全。

nginx 代理配置已在服务器侧处理，本次仓库只修改前后端代码。

## 改动

- `frontend/templates/series/series_list.html`
  - SockJS 地址从 `http://localhost:8081/api/ws` 改为 `/api/ws`。
  - 让浏览器按当前站点协议访问 WebSocket 代理，避免 HTTPS 页面主动加载 HTTP 连接。
- `backend/src/main/java/com/manga/ai/common/service/OssService.java`
  - 新增 OSS URL HTTPS 归一化逻辑。
  - `getPresignedUrl()` 和上传图片/视频后返回的签名 URL 都统一把开头的 `http://` 转为 `https://`。
- `frontend/apps/series/tests.py`
  - 增加模板回归测试，防止 WebSocket 地址重新写死到 `localhost:8081`。
- `backend/src/test/java/com/manga/ai/common/service/OssServiceTest.java`
  - 增加 OSS 签名 URL HTTPS 回归测试。

## 影响范围

- 首页处理中系列的 WebSocket 进度连接。
- 联系二维码、主体替换演示素材、上传图片/视频等经 `OssService` 返回的 OSS 签名 URL。
- 不修改本地或服务器 nginx 文件。

## 验证

- 新增测试先在旧代码上失败，再在修复后通过。
- 已运行 Django 检查与相关测试。
- 已运行 Maven 测试。
- 已重启本地前后端服务。
