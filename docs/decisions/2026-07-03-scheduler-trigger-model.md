# Scheduler Trigger 统一模型

## 背景

历史定时任务里，`@Scheduled` 或 `TaskScheduler.schedule(...)` 回调经常直接执行数据库扫描、队列领取、外部调用、缓存预热和自愈修复。这样会让 Spring scheduler 线程承担业务耗时，一旦某个任务阻塞，就可能拖慢同服务内其他调度入口。

上一轮 `ircs-search-service` 已经将搜索同步与审计 ES 投影改成“调度触发 + 虚拟线程 worker 执行”。本次把该模型推广到全仓生产源码。

## 决策

统一采用以下边界：

```text
Scheduler Trigger
  -> ScheduledTriggers.submit(...)
  -> named virtual-thread executor
  -> worker / watchdog / warmup / reaper
  -> RuntimeWorkQueue / state machine / domain service
```

- `@Scheduled` 方法只负责时间触发，不直接执行业务行为。
- `TaskScheduler.schedule(...)` 的 cron 回调也只负责提交 trigger，不直接调用业务服务。
- 具体行为保留在已有 worker、watchdog、warmup、reaper、service 方法中。
- 对原来依赖 `fixedDelay` 隐式防重入的任务，补显式 `AtomicBoolean running` 或保留已有分布式锁/lease，避免虚拟线程化后扩大实际并发。
- RuntimeWorkQueue 的 batch size、visibility timeout、heartbeat、retry、DLQ、限流和 lease 语义不改变。

## 本次覆盖

- `shared:ircs-common`
  - `LogRetentionScheduler`
  - 新增 `ScheduledTriggers`
  - 新增 architecture guard
- `ircs-task-service`
  - `TaskWatchdog`
  - `TaskSnapshotFlushScheduler`
  - `MediaRequestScheduler`
  - `DefaultCollectionTaskSeeder`
  - `CollectionTaskCronScheduler`
- `ircs-normalization-worker`
  - `NormalizeVideoWorkQueueWorker`
  - `LlmCleaningWorkQueueWorker`
  - `PendingNormalizationWatchdog`
- `ircs-metadata-worker`
  - `MetadataDispatchWorkQueueWorker`
  - `MetadataProviderWorkQueueWorker`
- `ircs-aggregation-worker`
  - `AggregationWorkQueueWorker`
- `ircs-magnet-service`
  - `MagnetWorkQueueWorker`
  - `MagnetAutoSearchScheduler`
- `ircs-storage-service`
  - `StorageR2WorkQueueWorker`
  - `CoverImageDownloadBackfillScheduler`
- `ircs-ops-service`
  - `RabbitManagementRateProbe`
  - `DashboardSnapshotWarmupService`
  - `RequestAuditSummaryWarmupService`
  - `MaintenanceScheduler`
  - `RuntimeWorkExpiredInflightReaper`
  - `RuntimeWorkDlqReplayWorker`
- `ircs-ops-alert-service`
  - `OpsAlertFirstPageWarmupService`
- `ircs-search-service`
  - `SearchSyncWorkQueueWorker`
  - `AuditEsReplicationWorkQueueWorker`
  - `SearchReconciliationRunner`

## Guard

`ScheduledTriggerModelTest` 扫描 `shared/**/src/main/java` 与 `services/**/src/main/java`：

- 生产源码中的 `@Scheduled` 方法体必须调用 `ScheduledTriggers.submit(...)`。
- 使用 Spring `TaskScheduler.schedule(...)` 的方法体也必须通过 `ScheduledTriggers.submit(...)` 提交。
- 失败信息输出具体文件和行号。

## 非目标

- 不把所有调度频率 key 统一改名。
- 不改变现有运行队列协议、数据库结构、API 契约和 UI 信息架构。
- 不用虚拟线程替代业务并发上限、Provider 限流、分布式锁、lease 或任务可见性超时。

## 验证

- `./gradlew.bat :shared:ircs-common:test --no-daemon --tests com.prodigalgal.ircs.common.architecture.ScheduledTriggerModelTest`
- `./gradlew.bat :services:ircs-normalization-worker:test :services:ircs-metadata-worker:test :services:ircs-task-service:test :services:ircs-search-service:test :services:ircs-ops-service:test --no-daemon`

后续交付前仍需要跑全仓 `./gradlew.bat test --no-daemon` 和线上 smoke。
