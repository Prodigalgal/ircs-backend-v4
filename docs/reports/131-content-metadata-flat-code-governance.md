# Content 元数据模型扁平化与标准 Code 化治理

## 目标

- 将 `RawVideo` / `UnifiedVideo` 的主读写路径彻底迁移到扁平字段：`actor_names`、`director_names`、`area_codes`、`language_codes`、`genre_codes`、`category_code`。
- `actor/director` 作为高基数字符串属性存储，避免后台编辑、聚合匹配、搜索同步继续依赖高成本 JOIN。
- `area/language/genre/category` 使用稳定 code 作为主契约，名称由标准字典解析展示。
- 下线 legacy raw 属性表与内容关系表的运行时依赖，不再提供 `raw-genres`、`raw-areas`、`raw-languages` 管理 API。

## 关键变更

- 数据库：
  - `flatten-content-metadata-code-model.sql` 回填扁平字段和 `standard_genre.code`。
  - `remove-legacy-content-metadata-relations.sql` 将 legacy 关系数据并入 flat/code 字段后 drop legacy 表。
  - 物理删除范围：`raw_genres`、`raw_languages`、`raw_areas`、`video_raw_*`、`video_actors`、`video_directors`、`unified_video_actors`、`unified_video_directors`、`unified_video_genres`、`unified_video_standard_languages`、`unified_video_standard_areas`。
- 后端：
  - normalization 只写 `raw_videos` flat/code 字段；`LlmCleaningRepository` 仅保留 category 映射。
  - aggregation 只从 flat/code 字段聚合，重复合并时合并 JSON 字段并保留 `category_code`。
  - content/search/portal/ops/metadata 主读路径统一使用 `category_code -> standard_category.slug`。
  - catalog/api-gateway/ops maintenance 删除 raw genre/area/language API 与维护入口。
  - 新增 `JsonStringArrays` 作为 JSON string array 的统一读写和兜底解析工具。
- 前端：
  - 标准分类、地区、语言、类型页面只展示标准字典和 code 字段。
  - 视频编辑提交 `actorNames/directorNames/areaCodes/languageCodes/genreCodes/categoryCode`。
  - 删除 raw genre/area/language 表格、API slice 和维护任务入口。
- Guard：
  - `NoLegacyContentMetadataTablesTest` 禁止 `shared/**/src/main/java` 和 `services/**/src/main/java` 重新引用 legacy 内容元数据表。

## 边界说明

- `raw_category` 仍保留为“采集源分类映射表”，用于资源站 `type_id/type_name` 与标准分类的人工/自动映射，以及数据源 fallback。
- 业务内容主数据不再通过 `raw_category.category_id` 读取标准分类；展示、搜索、门户、聚合和监控统一看 `RawVideo/UnifiedVideo.category_code`。
- `UnifiedVideo.category_id` 仍作为过渡辅助字段存在，但主查询、筛选和展示以 `category_code` 为准。

## 兼容性影响

- 移除接口：
  - `/api/v1/raw-genres`
  - `/api/v1/raw-areas`
  - `/api/v1/raw-languages`
  - `/api/v1/raw-languages/{id}/trace`
- 移除维护任务：
  - `area/clean-init`
  - `language/clean-init`
- 数据库迁移不可无损回退到 legacy 表结构；回滚点应选择迁移前数据库快照或发布前备份。
- 外部调用方应改用标准字典接口和视频 flat/code 字段。

## 验证

- `./gradlew :services:ircs-aggregation-worker:test`
- `./gradlew :shared:ircs-common:test --tests com.prodigalgal.ircs.common.architecture.NoLegacyContentMetadataTablesTest :services:ircs-catalog-service:test :services:ircs-normalization-worker:test :services:ircs-content-service:test :services:ircs-search-service:test :services:ircs-portal-service:test :services:ircs-api-gateway:test :services:ircs-ops-service:test`
- `npm run build` in `frontends/admin`

## 回滚建议

- 发布前备份数据库，特别是 `raw_videos`、`unified_videos`、标准字典表、legacy raw 属性表和 legacy 关系表。
- 若迁移后需要回滚，优先回滚应用镜像和数据库快照；不要在已 drop legacy 表的数据库上直接运行旧版本服务。
- 若只出现展示名称异常，可优先修复标准字典 code/slug 数据，再触发搜索重建和 dashboard 刷新。
