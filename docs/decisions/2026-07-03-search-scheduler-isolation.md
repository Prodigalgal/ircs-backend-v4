# Search worker 调度隔离

## 背景

线上 Unified ES 全量重刷时，`search.sync.unified` 和 `audit.es.replication` 都由 `ircs-search-service` 内的 `@Scheduled` worker 消费。全局已经启用 `spring.threads.virtual.enabled=true`，但旧实现仍把 DB/ES/Valkey 阻塞工作直接放在 `@Scheduled(fixedDelay)` 方法里执行。审计队列积压时，调度线程和 ES 写入资源会与核心搜索同步链路互相影响。

## 决策

- 保留全局虚拟线程能力，继续使用 `spring.threads.virtual.enabled=true` 作为服务 I/O 基线。
- 新增显式 `taskScheduler`，只负责轻量触发，不承载批量 DB/ES 工作。
- `SearchSyncWorkQueueWorker` 的 RAW/UNIFIED scheduled 方法只提交任务，实际处理进入独立 `searchSyncWorkerExecutor`。
- `AuditEsReplicationWorkQueueWorker` 独立使用 `auditEsReplicationWorkerExecutor`，避免审计投影拖慢核心搜索索引同步。
- 两个 worker executor 均使用命名 virtual thread，便于线程 dump、日志和运行态定位。
- 调度语义从 `fixed-delay-ms` 迁为 `fixed-rate-ms`，因为触发器已经轻量化，真实 worker 仍由 lane 内 `AtomicBoolean` 防重入。

## 边界

- 不放开队列背压：`batch-size`、`visibility-seconds`、`max-retries`、`max-backoff-seconds` 语义保持不变。
- 不改 `RuntimeWorkQueue` 数据结构、不改 Valkey key、不改 ES 文档契约。
- 不把 audit replication 提升为核心链路；审计投影可独立限速、暂停或延后。
- `fixed-rate-ms` 仍是启动期调度配置，系统设置中标记为 `RESTART_REQUIRED`，修改后需要重启 `ircs-search-service`。

## 验证

- `SearchSchedulingConfigurationTest` 验证 search/audit executor 使用命名 virtual thread，scheduler 使用有限命名线程池。
- `SearchSyncWorkQueueWorkerTest` 和 `AuditEsReplicationWorkQueueWorkerTest` 验证 scheduled trigger 只提交任务，不在 scheduler 线程直接消费队列。
- `SystemConfigDefaultsTest` 验证新的 `fixed-rate-ms` key 暴露到系统参数并标记为重启生效。

## 后续

- 其他 worker 服务可以沿用该模式逐步迁移，优先处理长 I/O、批量 ES/DB 写入和审计类旁路任务。
- 如果需要真正热调整触发频率，应新增可重注册的动态 scheduler，而不是继续依赖 `@Scheduled` 注解属性。
