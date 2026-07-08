# 132 运行态问题修复 Changelog：Traffic、Cover、Aggregation

## 背景

线上深度测试暴露出三类需修复问题：

- `GET /api/v1/ops/traffic/status` 在 Valkey 存在大量 key 时加载缓慢，根因是每次请求都对 `traffic:limit:*` 做全库 `SCAN`。
- 采集/清洗后的封面多停留在 `EXTERNAL/UNPROCESSED`，新建外链封面后缺少下载任务投递，历史候选也缺少安全回填调度。
- 聚合运行态存在 raw video 已处于 `PENDING` 但未补入运行队列，以及 unified video 缺封面但绑定 raw video 已有封面的补偿缺口。

## 变更分类

- 性能修复：Traffic limiter 状态读取从全库扫描改为活跃索引集合读取。
- 数据补偿：封面下载任务自动投递、定时安全回填、手工 backfill API。
- 聚合补偿：新增 pending raw 运行队列补投递、unified cover 回灌维护入口。
- 聚合补偿加固：线上 smoke 发现 `aggregation-cover-backfill` 在短超时内部请求内重跑逐条 pipeline 会超时，已改为 set-based 批量补齐 `unified_videos.cover_image_id`，后续 search/R2 同步继续走队列。
- 运维入口：将两个聚合补偿动作接入 ops maintenance runner，可通过 token API 初始化维护会话。
- 生产配置修复：`ircs-prod-config` 将 `APP_OPS_AGGREGATION_WORKER_REQUEST_TIMEOUT` 从 `PT2S` 调整为 `PT30S`，避免 ops 维护调用被生产 env 提前截断。
- 配置治理：补齐 aggregation config listener 与 storage image download backfill 的系统默认配置及重启语义。
- 回归测试：补齐 common、ops、storage、aggregation、config 的精准测试与模块测试。

## 关键模块

- `shared:ircs-common`
  - 新增 `TrafficLimitKeys`。
  - `RedisDistributedLockManager` 在 time-slice/token-bucket Lua 脚本中写入 `traffic:limit:index`。
- `services:ircs-ops-service`
  - `TrafficMonitorService` 改为读取 `traffic:limit:index`，遇到 stale/malformed key 时从索引清理。
  - `MaintenanceRunnerService` 新增 `aggregation-pending-backfill`、`aggregation-cover-backfill` 两个低风险维护任务。
  - `MaintenanceController` 新增两个 init endpoint。
- `services:ircs-storage-service`
  - `CoverImageAdminService#createFromUrl` 创建外链封面后立即投递下载任务。
  - 新增 `CoverImageDownloadBackfillScheduler` 定时投递 eligible external cover download。
  - `CoverImageController` 新增 `/api/v1/cover-images/download-backfill` 手工回填入口。
- `services:ircs-aggregation-worker`
  - 新增 `/internal/v1/aggregation/raw-videos/enqueue-pending`。
  - 新增 `/internal/v1/aggregation/unified-videos/backfill-covers`。
  - `aggregation-cover-backfill` 改为批量从绑定 raw video 选择首个封面并补齐 unified cover，避免维护请求内逐条重跑聚合 pipeline。
  - 新增 `q.aggregation.config_changed` 配置变更监听队列。
- `services:ircs-config-service`
  - 新增 `app.aggregation.config-listener.enabled` 默认项。
  - 新增 `app.storage.image.download-backfill.*` 默认项，并标记为重启生效。

## 行为变化

- Traffic 状态接口不再依赖 Valkey 全库 `SCAN`，只展示通过新版本限流脚本登记到 `traffic:limit:index` 的活跃限流 key，并继续展示已配置但尚未运行的 TMDB credential token bucket idle slot。
- 已存在但没有被新脚本登记的历史 traffic key 不会再被接口主动扫描展示；这些 key 会随 TTL 过期，新的限流请求会自动进入索引。
- 外链封面创建后会进入下载队列；历史 `EXTERNAL + UNPROCESSED/FAILED + retry due` 的封面会被定时补投递。
- 聚合维护入口只投递或回灌候选记录，不删除业务数据，不改变现有 aggregation reset 行为。
- `aggregation-cover-backfill` 现在只补齐缺失封面字段；若选择到的封面为 `LOCAL/LOCAL_STORED`，才追加 R2 promote 运行队列；`EXTERNAL/UNPROCESSED` 封面只刷新 unified search index，避免产生无效 R2 同步任务。
- aggregation config listener 默认启用，收到系统配置变更事件后驱逐本地运行时配置缓存。

## 兼容性影响

- API 兼容：未删除现有接口；新增接口为 additive。
- 数据库兼容：无 DDL、无 Liquibase 变更。
- 运行时兼容：`traffic:limit:index` 是新增 Valkey Set，不影响旧 key TTL 与旧限流语义。
- 运维兼容：新增 storage download backfill 调度参数属于启动期配置，修改后需要重启 `ircs-storage-service`。
- 生产配置兼容：仅放宽 ops 调 aggregation 维护接口的请求等待上限，不改变普通 dashboard stats 的 `app.ops.maintenance.aggregation.stats-request-timeout`。

## 验证结果

- `./gradlew :shared:ircs-common:test`
- `./gradlew :services:ircs-ops-service:test`
- `./gradlew :services:ircs-storage-service:test`
- `./gradlew :services:ircs-aggregation-worker:test`
- `./gradlew :services:ircs-config-service:test`
- 二次修复精准验证：
  - `./gradlew :services:ircs-aggregation-worker:test --tests com.prodigalgal.ircs.aggregation.AggregationServiceTest --tests com.prodigalgal.ircs.aggregation.JdbcAggregationRepositoryTest --tests com.prodigalgal.ircs.aggregation.InternalAggregationControllerTest`
  - `./gradlew :services:ircs-ops-service:test --tests com.prodigalgal.ircs.ops.maintenance.infrastructure.AggregationMaintenanceClientTest --tests com.prodigalgal.ircs.ops.maintenance.application.MaintenanceRunnerServiceTest`
- `rg -n "@Autowired\\b|org\\.springframework\\.beans\\.factory\\.annotation\\.Autowired" shared services --glob '!**/build/**'`：零命中。
- `git diff --check`：通过。
- 线上验证：
  - backend commit `f80b339`、`ef9929d` 已推送并由 Gitea Actions 构建成功，生产镜像包含 `ircs-aggregation-worker:sha-ef9929d9492f`。
  - GitOps 配置 commit `d752f21` 已推送，Argo `ircs-prod-core` 同步到 revision `d752f211a3517786b4b68abc40c16de7876412c2`，状态为 `Synced/Healthy`。
  - `kubectl -n ircs-prod get pods` 显示 `ircs-ops-service`、`ircs-storage-service`、`ircs-aggregation-worker`、`ircs-config-service` 均为 `1/1 Running`。
  - 生产 `ircs-ops-service` env 已确认为 `APP_OPS_AGGREGATION_WORKER_REQUEST_TIMEOUT=PT30S`。
  - `GET /api/v1/ops/traffic/status` 预热后约 0.5-1.4s，返回 19-20 个活跃限流器。
  - `GET /api/v1/dashboard/metrics`、`task-runtime`、`search-ops`、`aggregation-ops` 均返回 200。
  - `q.aggregation.config_changed` 从 `231/0` 下降到 `0/1`，确认新增 listener 已消费积压。
  - storage-service 日志已出现 `Queued cover image download backfill` 与 `Downloaded cover image`，确认封面下载回填链路在线运行。
  - `aggregation-pending-backfill` 线上维护 smoke 成功选择并投递 5 条。
  - `aggregation-cover-backfill` 首次线上 smoke 暴露 request timeout；批量轻量回填修复与生产超时配置修复上线后，复测 `INIT_HTTP=200`、`STREAM_HTTP=200`，约 3.16s 完成，`selected=5 changed=5`。
  - 最新线上 HTTP smoke：`/dashboard/metrics` 200/0.99s，`/dashboard/task-runtime` 200/0.96s，`/dashboard/search-ops` 200/2.86s，`/dashboard/aggregation-ops` 200/0.75s，`/ops/traffic/status` 200/0.90s。
  - 最新只读 DB 统计：`unified_videos` 有封面 15、无封面 10059；仍有 `unified_missing_cover_from_raw=10053`，后续按维护 runner 小批次推进。

## 2026-06-24 一次性数据迁移

用户确认“服务可中断”后，执行了一次生产停服数据补偿：

- 临时关闭 Argo `ircs-prod-core` automated sync/self-heal，记录原策略为 `prune=true,selfHeal=true`，迁移后已恢复。
- 将 IRCS 应用 Deployment 缩容到 0，并临时通过 DaemonSet nodeSelector 暂停 `ircs-scraper-service`，避免迁移期间继续写 raw/unified 相关数据。
- 迁移前确认 `unified_missing_cover_from_raw=10053`，且候选中 `locked_fields` 未出现 `coverImageUrl`，不会覆盖用户锁定封面。
- 在 `ops_backups.unified_cover_backfill_20260624` 写入备份记录，`migration_id=20260624_unified_cover_backfill_full`，共备份 10053 条：
  - `unified_video_id`
  - `old_cover_image_id`
  - `new_cover_image_id`
  - `selected_raw_video_id`
  - `selected_cover_status`
  - `selected_cover_storage_type`
- 使用备份表一次性更新 `unified_videos.cover_image_id`，实际更新 10053 条。
- 迁移后只读校验：
  - `remaining_unified_missing_cover_from_raw=0`
  - `unified_videos` 有封面 10068，无封面 6
  - 备份表记录数 10053
- 服务恢复后，分 11 批调用 `ircs-search-service` 内部 batch API，投递 `UNIFIED_VIDEO/INDEX` 搜索同步任务 10053 条，全部返回 `accepted`。
- 调用 `/api/v1/cover-images/download-backfill?limit=5000`，一次性投递剩余封面下载候选 1284 条。
- 恢复后验证：
  - Argo `ircs-prod-core` 状态 `Synced/Healthy`，automated sync/self-heal 已恢复。
  - 所有 IRCS Deployment、`ircs-scraper-service` DaemonSet、Postgres、RabbitMQ、Valkey、Elasticsearch 均为 Running。
  - `/dashboard/task-runtime`、`/dashboard/search-ops`、`/dashboard/aggregation-ops`、`/dashboard/metrics` 均返回 200。
  - `search.sync.unified` 队列从 9903 降到 9803，worker 每轮处理 50 条，`failed=0`，确认搜索同步在持续消费。
  - `cover_download_candidates` 从 1284 投递后继续下降到 1054，storage 日志持续出现 `Downloaded cover image` 与 R2 同步记录。

## 部署与运行态状态

- 本次变更需要重新构建并滚动以下镜像：
  - `ircs-common` 下游使用方。
  - `ircs-ops-service`
  - `ircs-storage-service`
  - `ircs-aggregation-worker`
  - `ircs-config-service`
- 生产配置仓库已额外滚动 `ircs-ops-service`，用于应用 `APP_OPS_AGGREGATION_WORKER_REQUEST_TIMEOUT=PT30S`。
- 部署后建议优先验证：
  - `/api/v1/ops/traffic/status` 响应时长。
  - `/api/v1/ops/maintenance/runners` 是否包含两个新增 runner。
  - storage-service 日志是否出现封面下载 backfill 投递记录。
  - aggregation-worker 是否声明并消费 `q.aggregation.config_changed`。

## 遗留风险

- `traffic:limit:index` 只覆盖新版本写入后的 traffic key；刚部署后短时间内历史 key 展示可能少于旧接口，但性能风险显著降低。
- 封面下载和聚合补偿会增加下游队列压力；默认 batch 较小，若线上积压较大，应分批观察 Rabbit/Valkey 与 storage-service 处理速度。
- 2026-06-24 已一次性清零 unified cover 可回灌候选；剩余风险转为异步链路消化速度，`search.sync.unified` 与 cover download/R2 sync 仍需继续观察至积压归零。
- audit ES backlog、Rabbit PVC 容量属于运行容量问题，本次未修改部署资源。

## 建议回滚点

- 回滚代码镜像即可恢复旧行为。
- 若生产维护调用需回退等待上限，可回滚 GitOps commit `d752f21`，恢复 `APP_OPS_AGGREGATION_WORKER_REQUEST_TIMEOUT=PT2S`。
- 若只需临时停用封面安全回填，可将 `app.storage.image.download-backfill.enabled=false` 并重启 `ircs-storage-service`。
- 若新增 ops maintenance runner 不适合暴露，可回滚 `MaintenanceRunnerService`/`MaintenanceController` 相关提交；内部 aggregation endpoint 不会被普通外部调用直接触达。
