# 全仓 Autowired 专项迁移与 Guard

日期：2026-06-21

## 范围

- 后端模块：`shared/**`、`services/**`
- 迁移目标：清除生产和测试 Java 源码里的显式 Spring Autowired 注解、Autowired class 断言和
  `org.springframework.beans.factory.annotation.` + `Autowired` import/reference。
- 非目标：不修改 API 契约、数据库 schema、配置 key、部署清单或前端 UI。

## 变更分类

1. 注入方式迁移
   - 删除构造器上的显式 Spring 注入注解，保留单构造器自动注入。
   - 将可选依赖从字段注入迁移为构造器 `ObjectProvider<T>`。
   - 将测试替代路径收敛到 `forTest(...)` 静态工厂或测试侧 provider helper。

2. 生产构造器收敛
   - `RedisRuntimeWorkQueue`、`PipelineValkeyQueue` 删除 package-private 测试构造器，
     通过 `ObjectProvider<Clock>` 保留可测时钟注入能力。
   - `MetadataProviderTrafficLimiter`、metadata HTTP clients、`CoverImageDownloadService`、
     `AggregationPipelineRuntime`、`JdbcAggregationRepository` 删除测试专用构造器。
   - `SearchPortalReadModelCache` 保留单生产构造器，新增 `forTest(...)` 工厂。

3. 可选依赖迁移
   - `LlmCleaningWorkQueueWorker`
   - `SearchSyncWorkQueueWorker`
   - `AuditEsReplicationWorkQueueWorker`
   - `StorageR2WorkQueueWorker`
   - `MetadataProviderValkeyWorker`
   - `AdminApiTokenService`
   - `MailSendRateLimiter`

4. Repository 测试入口整理
   - 多 service `SystemConfigRepository` 的单参数测试构造器迁移为 `forTest(JdbcTemplate)`。
   - `JdbcConfigRepository` 迁移为 `forTest(JdbcTemplate)`。
   - `RawVideoNormalizationRepository` 迁移为 `forTest(NamedParameterJdbcTemplate)`。

5. 总仓级 guard
   - 新增 `shared/ircs-common/src/test/java/com/prodigalgal/ircs/common/architecture/NoAutowiredUsageTest.java`。
   - 扫描 `shared/**/src/main/java`、`shared/**/src/test/java`、`services/**/src/main/java`、
     `services/**/src/test/java`。
   - 失败信息输出具体文件路径、行号和源码行。

## 行为兼容性

- Spring Bean 注入路径保持构造器注入，不改变 Bean 名称、接口、配置 key、缓存 key 或数据库访问契约。
- `ObjectProvider<T>` 只用于保留原有“依赖不存在则 fallback”的行为。
- 测试时钟、测试 limiter、测试 repository 构造能力仍保留，但入口从多构造器迁移到 provider 或 `forTest(...)`。
- 无 schema/migration 变更，无前端变更，无部署清单变更。

## 验证结果

已通过：

```powershell
.\gradlew.bat :shared:ircs-common:test --tests com.prodigalgal.ircs.common.architecture.NoAutowiredUsageTest --tests com.prodigalgal.ircs.common.work.RedisRuntimeWorkQueueRateMetricsTest --tests com.prodigalgal.ircs.common.pipeline.PipelineValkeyQueueRateMetricsTest
.\gradlew.bat :services:ircs-notification-worker:test :services:ircs-metadata-worker:test :services:ircs-normalization-worker:test :services:ircs-search-service:test :services:ircs-storage-service:test :services:ircs-aggregation-worker:test :services:ircs-config-service:test :services:ircs-api-gateway:test
rg -n "<forbidden annotation/import pattern>" backend
rg -n "<forbidden annotation/import pattern>" --glob "*.java"
git diff --check
.\gradlew.bat test
```

验证结论：

- `rg` 无命中。
- `git diff --check` 无空白错误。
- 全仓 `.\gradlew.bat test` 通过。

## 部署和运行态状态

- 本次只修改 Java 源码和测试，不包含镜像构建、Kubernetes/Argo CD 同步或线上 Pod 重启。
- 生产运行态需要在后续镜像构建、推送、GitOps 同步后再验证。

## 遗留风险

- `@Value` 配置热迁移不是本次目标，仍按既有运行时配置迁移计划继续推进。
- Lombok/MapStruct 没有在本切片强行引入；本次迁移以最小行为变更为优先。
- Guard 基于源码文本扫描，覆盖显式注解/import/reference；不引入外部静态分析框架。

## 建议回滚点

- 若构造器迁移导致运行态 Bean 注入异常，可优先回滚本次 Java 注入迁移和新增 guard 测试。
- 由于未改 schema、配置 key 和部署清单，回滚不需要数据库回滚或配置回滚。
