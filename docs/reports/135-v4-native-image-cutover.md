# IRCS V4 Native Image Cutover

日期：2026-07-08

## 目标

将 V4 的两个 Java runtime 从默认 JVM 镜像构建切换为 GraalVM native image 构建：

- `ircs-platform-api`
- `ircs-worker-runtime`

本次不改变 API 契约、数据库结构、配置 key、队列语义或部署拓扑。生产部署仍由 GitHub Actions 构建 Docker Hub 镜像，再由 GitOps 仓库触发 Argo CD 同步。

## 变更

- GitHub Actions `image_mode` 默认值从 `jvm` 改为 `native`。
- push 触发场景也默认导出 `IMAGE_MODE=native`，避免非手动触发时回退到 JVM 镜像。
- CI 镜像脚本的内部默认值改为 `native`，保留 `IMAGE_MODE=jvm` 作为显式回退开关。
- CI 拆分为原生分架构构建：
  - `amd64` 使用 `ubuntu-24.04` runner，推送 `:<tag>-amd64`。
  - `arm64` 使用 `ubuntu-24.04-arm` runner，推送 `:<tag>-arm64`。
  - `publish-multiarch-images` job 使用 `docker buildx imagetools create` 合并成同一个 `:<tag>` 多架构 manifest。
- 两个 native Dockerfile 使用 BuildKit Gradle cache mount，减少 CI 中 native 构建的重复依赖下载成本。
- native runtime base 从 distroless base 调整为 `debian:12-slim` 并安装 `zlib1g`、`ca-certificates`；首轮 distroless base 缺 `libz.so.1`，会导致 `/app/ircs-platform-api` 启动时报 shared library error。
- GraalVM native build 启用 `resource` URL protocol，允许 Log4j2 从 native image classpath 加载 `log4j2-spring.xml`。
- 控制台日志 pattern 改为纯 Log4j2 converter，移除 Spring Boot 颜色包装 `%clr`，避免 native runtime 下 Log4j2 plugin 解析失败。
- runtime main 方法在 `SpringApplication.run` 前设置 Log4j2 `allowedProtocols` 默认值，允许 `resource:` 配置加载，同时保留部署显式 override。
- native runtime hints 显式包含 `Log4j2Plugins.dat`，避免 PatternLayout、RollingFile、Filter 等 Log4j2 core plugin 在 native image 中被裁剪。
- native build 增加可配置 `nativeTargetMachine`，CI 中 amd64 默认使用 `x86-64-v2`，arm64 默认使用 `compatibility`，避免 GitHub runner 产物使用 edge 节点不支持的 CPU ISA。
- storage 图片安全校验移除启动期 Apache Tika 初始化，改为固定 allowlist 图片格式的 magic-number 检测，降低 native image 资源裁剪风险。
- 本地 Docker buildx 辅助脚本与 CI 保持一致，构建时关闭 provenance，降低 registry manifest 兼容风险。

## 行为兼容性

- HTTP 端口仍为 `8080`。
- Spring profiles、环境变量、数据库、RabbitMQ、Valkey、ES、R2 等外部依赖配置保持不变。
- Kubernetes readiness/liveness/startup probe 路径保持不变。
- 若 native 构建或运行态存在 GraalVM reachability 问题，可通过 workflow_dispatch 显式传入 `image_mode=jvm` 回退到 JVM 镜像。

## 验收标准

- GitHub Actions 能构建并推送 `docker.io/speedproxy/ircs-platform-api:<tag>` 和 `docker.io/speedproxy/ircs-worker-runtime:<tag>`。
- 同时存在架构后缀镜像：
  - `docker.io/speedproxy/ircs-platform-api:<tag>-amd64`
  - `docker.io/speedproxy/ircs-platform-api:<tag>-arm64`
  - `docker.io/speedproxy/ircs-worker-runtime:<tag>-amd64`
  - `docker.io/speedproxy/ircs-worker-runtime:<tag>-arm64`
- `build-image-manifest.txt` 中 `image_mode=native`。
- CI 自动回写 `ircs-prod-config/ircs-v4` 镜像 tag。
- Argo CD `ircs-v4-runtime` 同步后两个 runtime Pod Ready。
- 内部 readiness：
  - `http://ircs-platform-api.ircs-prod.svc.cluster.local:8080/actuator/health/readiness`
  - `http://ircs-worker-runtime.ircs-prod.svc.cluster.local:8080/actuator/health/readiness`
- 外部 portal/admin/API smoke 测试通过。

## 回滚点

- 短期回滚：在 GitHub Actions 手动触发时设置 `image_mode=jvm`，重新构建并回写 GitOps tag。
- 配置回滚：将 `.github/workflows/build-and-push.yml` 与 `scripts/ci/jib-build-push-affected.sh` 默认值恢复为 `jvm`。
- 运行态回滚：将 GitOps 仓库中 `ircs-platform-api` 和 `ircs-worker-runtime` 镜像 tag 回退到最近一次已验证 JVM tag。
