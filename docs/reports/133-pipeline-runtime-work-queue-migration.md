# Pipeline RuntimeWorkQueue 架构迁移报告

## 摘要

- 本次将清洗、元数据分发、元数据 Provider 三类原 `PipelineValkeyQueue` 任务彻底迁移到统一 `RuntimeWorkQueue`。
- 删除旧 `PipelineValkeyQueue` 实现、旧 pipeline task/count DTO、旧 `PipelineCommand` / `PipelineStep` 契约，以及旧 `canSubmitPipeline` 兼容入口。
- expired inflight 回收统一由 ops-service 的 `RuntimeWorkExpiredInflightReaper` 处理，worker 本地不再各自执行 `requeueExpired`。
- RabbitMQ 仍保留为跨服务事件/业务消息通道；本次不把 RabbitMQ 事件全部改成 Valkey，也不改数据库 schema。

## 变更分类

1. 队列架构迁移
   - 新增 `PipelineRuntimeWorkTypes`，集中定义 pipeline runtime task type 与稳定 taskId 生成规则。
   - 清洗、元数据分发、Provider 抓取的发布与消费路径统一使用 `RuntimeWorkItemRequest` / `RuntimeWorkItem`。
   - `PendingNormalizationWatchdog` 通过 `RuntimeWorkQueue.hasOpenTask(...)` 去重，避免重复投递同一个待清洗视频。

2. 旧实现删除
   - 删除 `PipelineValkeyQueue`、`PipelineValkeyTask`、`PipelineValkeyTaskCounts`、`PipelineTaskTypes`。
   - 删除 `PipelineCommand`、`PipelineStep` 旧 contracts 类型。
   - 删除旧 pipeline queue rate metrics 测试，并把指标验证迁移到 runtime queue 测试。

3. 统一 watchdog
   - `NormalizeVideoWorkQueueWorker`、`MetadataDispatchWorkQueueWorker`、`MetadataProviderWorkQueueWorker` 不再本地 `requeueExpired`。
   - search、aggregation、magnet、storage、audit ES、LLM cleaning worker 也移除本地 expired inflight 回收。
   - 生产代码中仅保留 `RuntimeWorkExpiredInflightReaper` 调用 `RuntimeWorkQueue.requeueExpired(...)`。

4. 运维监控口径调整
   - ops dashboard 不再展示单独的 `Valkey Pipeline` 分组。
   - 清洗/元数据 pipeline 队列现在展示在 `Valkey Runtime Work` 分组，key 形如 `RUNTIME_PIPELINE_NORMALIZE_*`、`RUNTIME_PIPELINE_METADATA_PROVIDER_*`。
   - `RateMetricKeys` 将 pipeline 速率统一归入 runtime metric key，top card 与队列行使用同一 runtime 速率来源。

5. 代码洁癖
   - 生产类名从 `*ValkeyWorker` 收敛为 `*WorkQueueWorker`，避免实现已经迁移但命名仍指向旧架构。
   - 删除旧 pipeline submission gate 兼容方法，后续只允许 `canSubmitRuntime(...)` 表达提交控制。
   - `MetadataPipelineRunRepository` 继续保持数据库 step 值 `ENRICH_METADATA`，避免队列迁移污染历史数据语义。

## 关键模块

- `shared:ircs-common`
  - `PipelineRuntimeWorkTypes`
  - `RuntimeWorkQueue`
  - `RedisRuntimeWorkQueue`
  - `SystemConfigWorkSubmissionGate`
  - `RateMetricKeys`

- `services:ircs-normalization-worker`
  - `NormalizeVideoPublisher`
  - `MetadataEnrichPublisher`
  - `NormalizeVideoWorkQueueWorker`
  - `PendingNormalizationWatchdog`

- `services:ircs-metadata-worker`
  - `MetadataEnrichPublisher`
  - `MetadataProviderTaskPublisher`
  - `MetadataDispatchWorkQueueWorker`
  - `MetadataProviderWorkQueueWorker`
  - `MetadataPipelineRunRepository`

- `services:ircs-ops-service`
  - `SystemMetricsService`
  - `SystemMetricsDataReader`
  - `RuntimeWorkQueueCatalog`
  - `QueueBlockedReasonResolver`
  - `RuntimeWorkExpiredInflightReaper`

## 行为变化

- 新提交的 pipeline 任务进入 `RuntimeWorkQueue`，不再进入旧 `PipelineValkeyQueue` key 空间。
- ops dashboard 的队列展示与 DLQ 展示以 runtime work 为唯一来源。
- 旧 pipeline contracts 被移除，内部调用方必须使用 `RuntimeWorkItemRequest` 或对应 publisher。
- worker 不再重复扫描 expired inflight，减少多服务重复 requeue、误判 STALLED 和指标口径不一致风险。

## 兼容性影响

- 无数据库 DDL、无 Liquibase、无前端接口删除。
- 现有 `app.*.valkey-*` 配置 key 暂时保留，这是部署和系统设置的运维契约；代码类名已迁移为 `WorkQueueWorker`。
- 旧 `PipelineValkeyQueue` 里的历史积压不会被新 worker 消费。发布前若旧 key 仍有未处理任务，需要先通过线上脚本检查并按 raw video 状态补投到 runtime work，或等待 `PendingNormalizationWatchdog` 对 `PENDING` 数据补偿。
- `raw_video_pipeline_runs.step` 仍使用 `ENRICH_METADATA`，避免破坏历史 pipeline run 查询。

## 验证结果

已通过以下命令：

```powershell
./gradlew :shared:ircs-common:test :services:ircs-normalization-worker:test :services:ircs-metadata-worker:test :services:ircs-ops-service:test
```

```powershell
./gradlew :shared:ircs-common:test :services:ircs-content-service:test :services:ircs-ingestion-worker:test :services:ircs-normalization-worker:test :services:ircs-metadata-worker:test :services:ircs-ops-service:test :services:ircs-search-service:test :services:ircs-aggregation-worker:test :services:ircs-storage-service:test :services:ircs-magnet-service:test
```

已通过旧引用扫描：

```powershell
rg -n "canSubmitPipeline|pipelineSubmissionFlags|pipelineConsumerFlags|PipelineCommand|PipelineStep|PipelineTaskTypes|PipelineValkey|pipelineMetric\\(|Valkey Pipeline" backend -g "*.java"
```

扫描结果：无旧引用。

## 部署与运行态状态

- 本报告只记录代码侧迁移，尚未包含线上 rollout 结果。
- 推送后由既有 CI/CD 构建镜像并通过 GitOps 部署；不需要手动构建镜像。
- 线上验证建议：
  - 查看 ops dashboard `Valkey Runtime Work` 是否出现 `PIPELINE_NORMALIZE`、`PIPELINE_METADATA_DISPATCH`、`PIPELINE_METADATA_PROVIDER`。
  - 用 token API 检查 `taskRuntime`、`metrics`、`searchOps`、`aggregationOps` 是否仍可局部加载。
  - 检查 `PendingNormalizationWatchdog` 日志是否能对待清洗数据补投 runtime work。
  - 检查 `RuntimeWorkExpiredInflightReaper` 日志，确认 expired inflight 由 ops-service 单点统一回收。

## 回滚点

- 代码回滚：回滚本次提交即可恢复旧 `PipelineValkeyQueue` 代码路径。
- 运行态回滚：若新 runtime pipeline worker 异常，应先暂停相关 worker deployment 或关闭对应 worker 开关，再回滚镜像。
- 数据回滚：本次无 schema 变更，无需数据库回滚；但旧 Valkey key 与新 runtime key 不共享，回滚前需要确认未处理任务所在 key 空间。

## 遗留风险

- 配置 key 名称仍保留 `valkey-*` 历史命名，后续如要彻底重命名，需要做系统设置 key 迁移、环境变量别名和文档同步。
- 旧 Valkey pipeline key 若线上还有未消费任务，需要一次性巡检或通过业务状态补投。
- 本次未做线上真实 API/Pod 验证，需等待自动 CI/CD 发布后执行运行态 smoke。
