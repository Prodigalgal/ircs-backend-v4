# BFF 移除与 API Gateway 切换

日期：2026-07-02

## 目标

- 删除 `ircs-portal-bff` 与 `ircs-admin-bff` 两个历史 BFF 模块。
- 将后台管理和门户受保护接口统一切到 `ircs-api-gateway`。
- 不保留旧 `/api/backend/**` 路径兼容；旧路径应直接失效。
- 保留必要技术边界：认证、权限、审计、身份头清洗、principal 透传、内部服务 token 注入、SSE/流式响应代理。

## 架构结果

- 新增后端模块：`services:ircs-api-gateway`。
- `ircs-api-gateway` 负责：
  - 后台 `/api/v1/**` 的 admin role + permission 校验。
  - 门户 `/api/portal/**` 的可选 JWT 认证、受保护写接口权限校验，以及 principal 透传。
  - `/media/**` 的代理能力，供后台管理域统一访问存储资源。
  - ops 与 ops-alert 下游请求的内部服务身份注入。
  - 请求审计写入 `request_audit_logs`。
- 门户公网 HTTPRoute：
  - `/api/portal` -> `ircs-api-gateway`
  - `/media` -> `ircs-storage-service`
  - `/` -> `ircs-portal-frontend`
- 后台公网 HTTPRoute：
  - `/api/v1`、`/media` -> `ircs-api-gateway`
  - `/` -> `ircs-admin-frontend`

## 关键变更

- 后端：
  - `settings.gradle` 移除 `services:ircs-portal-bff`、`services:ircs-admin-bff`。
  - 新增 `services:ircs-api-gateway`。
  - 删除旧 BFF 源码、测试、smoke 脚本和 dev deployment。
  - 删除旧通用构建残留 `deploy/docker/bff.Dockerfile`。
  - `ProxyRequestAuditWriter` 替代旧 `BffRequestAuditWriter`。
  - `OutboundHttpPolicy.apiGatewayProxy` 替代旧 `bffProxy` 命名。
  - `SystemConfigDefaults` 与 `KubernetesDeploymentRestartService` 的可重启服务列表改为 `ircs-api-gateway`。
  - OAuth 登录回调起始地址从 `/api/backend/api/portal/...` 改为 `/api/portal/...`。

- 门户前端：
  - 浏览器 API base 改为 `/api/portal`。
  - Next proxy 改为转发 `/api/portal/**`。
  - sitemap 与服务端数据加载保持可配置 `API_INTERNAL_URL`，用于公开 SSR/SEO 数据兜底。

- 后台前端：
  - NGINX `/api/` 与 `/media/` upstream 改为 `ircs-api-gateway`。

- Cloudflare Worker：
  - 回源 API 前缀改为 `/api/portal`。
  - 旧 `/api/backend/**` 在边缘直接返回 `410 Gone`，不再回源。

- GitOps：
  - `frontend-bff.yaml` 替换为 `api-gateway.yaml`。
  - `APP_BFF_*` 运行环境变量替换为 `APP_GATEWAY_*`。
  - ServiceMonitor 和前端 gateway NGINX 配置同步切到 `ircs-api-gateway`。

## Break Changes

- 旧 `/api/backend/**` 路径不再提供兼容。
- `ircs-portal-bff` 与 `ircs-admin-bff` 镜像、Deployment、Service、模块名全部删除。
- CI 镜像矩阵不再构建旧 BFF 镜像，只构建 `ircs-api-gateway`。
- 旧 `APP_BFF_*` 配置 key 作废，生产配置改用 `APP_GATEWAY_*`。

## 验证结果

已通过：

```powershell
.\gradlew.bat --no-daemon :shared:ircs-common:test :services:ircs-api-gateway:test :services:ircs-identity-service:test :services:ircs-config-service:test :services:ircs-ops-service:test
.\gradlew.bat --no-daemon test
npm run build # frontends/huawai
npm run build # frontends/admin
kubectl kustomize ircs-prod-config\ircs-prod\core
kubectl kustomize ircs-prod-config\ircs-prod\edge-cutover
git -C backend diff --check
git -C ircs-prod-config diff --check
bash -lc 'BUILD_SCOPE=ircs-api-gateway DRY_RUN=true scripts/ci/jib-build-push-affected.sh'
```

验证结论：

- 后端全仓测试通过。
- 门户和后台前端生产构建通过。
- prod core 与 edge-cutover kustomize 渲染通过。
- CI dry-run 只选择 `services:ircs-api-gateway -> ircs-api-gateway`。
- 后端测试改为断言未知 API 前缀不会被 gateway 代理；旧路径硬废弃由 Cloudflare Worker 线上规则负责。

上线验证：

- backend commit：`4104f65 refactor: replace bff modules with api gateway`
- huawai commit：`621f898 refactor: route portal api through gateway`
- admin commit：`bee3862 refactor: proxy admin api through gateway`
- cloudflare-workers commit：`310b082 fix: reject removed portal api prefix at edge`
- ircs-prod-config 切换 commit：`97d7024 refactor: replace bff deployments with api gateway`
- ircs-prod-config 当前线上 revision：`e21f42eba6b649dfdb4d24b026a81b44b8dcff9f`
- Cloudflare Worker version：`9c3b2d39-2533-459b-b542-810ed6eac634`
- `ircs-api-gateway` 镜像：`registry.mnnu.eu.org/ircs/ircs-api-gateway:sha-4104f6561f17`
- ArgoCD：`ircs-prod-core`、`ircs-prod-edge` 均为 `Synced/Healthy`
- Deployment：`ircs-api-gateway` 为 `2/2 Ready`
- 旧 Service：`ircs-admin-bff`、`ircs-portal-bff` 已不存在
- 旧镜像包：
  - `ircs/ircs-admin-bff` 全部 manifest 删除，`tags=[]`，旧 tag 拉取探测 `404`
  - `ircs/ircs-portal-bff` 全部 manifest 删除，`tags=[]`，旧 tag 拉取探测 `404`
- 线上 smoke：
  - `GET https://huawai.mnnu.eu.org/api/portal/metadata` -> `200`
  - `GET https://huawai.mnnu.eu.org/api/portal/home` -> `200`
  - `GET https://huawai.mnnu.eu.org/api/portal/search/suggest?keyword=test` -> `200`
  - `GET https://huawai.mnnu.eu.org/api/backend/api/portal/home` -> `410`
  - `GET https://admin.mnnu.eu.org/api/v1/dashboard/statistics` with PAT -> `200`
  - `GET https://admin.mnnu.eu.org/api/v1/dashboard/statistics` without auth -> `401`

## 运行态注意

- 禁止手动构建镜像；镜像由自动 CI/CD 生成。
- `ircs-prod/core/manifests/api-gateway.yaml` 的镜像 tag 应在 backend 提交后由 CI 或发布提交更新到对应 backend commit tag。
- 上线后重点 smoke：
  - `GET https://huawai.mnnu.eu.org/api/portal/metadata`
  - `GET https://huawai.mnnu.eu.org/api/portal/home`
  - `GET https://huawai.mnnu.eu.org/api/portal/search/suggest?keyword=test`
  - `GET https://admin.mnnu.eu.org/api/v1/dashboard/statistics`
  - `POST https://admin.mnnu.eu.org/api/v1/auth/login`
  - `GET https://huawai.mnnu.eu.org/api/backend/api/portal/home` 应返回 `410 Gone`。

## 回滚点

- 应优先回滚 backend、frontends、cloudflare-workers 与 ircs-prod-config 的本次提交组合。
- 因本次是硬迁移，若必须恢复旧 BFF，需要回滚到本专项前的代码和 GitOps 配置组合；不建议只局部恢复旧路径。
- 本次没有数据库 schema 变更，不涉及数据回滚。
