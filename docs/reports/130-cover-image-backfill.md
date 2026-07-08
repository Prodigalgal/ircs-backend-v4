# 采集封面历史数据安全回填

## 背景

采集链路已能从数据源解析 `coverImageUrl`，历史数据中大量记录已经把封面地址保存在 `raw_videos.raw_metadata.coverImageUrl`。旧版 `ingestion-worker` 没有把该地址落到 `cover_images`，也没有写入 `raw_videos.cover_image_id`，导致后台视频详情和聚合后视频详情无法通过现有封面资产链路展示封面。

## 回填范围

- 只处理 `raw_videos.cover_image_id is null` 的历史记录。
- 只处理 `raw_metadata.coverImageUrl` 非空、非 `null`、非 `undefined` 且长度不超过 2048 的记录。
- HTTP/HTTPS URL 会拆分为 `source_domains.domain_value` + `cover_images.original_url` 相对路径，保持与 storage-service 的外部封面引用模型一致。
- 非标准 URL 会放入 `EXTERNAL_COVER` 哨兵域名，不丢弃数据。
- 只给 `unified_videos.cover_image_id is null` 的聚合视频补封面；不覆盖已有封面，不处理 `locked_fields` 含 `coverImageUrl` 的聚合视频。

## 安全策略

- 所有写入均通过唯一约束幂等执行：
  - `source_domains.domain_hash`
  - `cover_images(original_url, source_domain_id)`
- 回填只补空值，不删除、不覆盖已存在封面。
- 不发布封面下载队列，不触发 R2 同步；后续下载仍通过 storage-service 既有流程控制。
- 迁移文件可重复安全执行，Liquibase 会按 changeset 保证生产环境只执行一次。

## 迁移文件

- `platform/ircs-migrator/src/main/resources/db/changelog/2026/07/backfill-raw-video-cover-images.sql`
- 已接入 `db.changelog-master.yaml`

## 执行前统计

```sql
select
    count(*) filter (
        where cover_image_id is null
          and raw_metadata ? 'coverImageUrl'
          and nullif(trim(raw_metadata ->> 'coverImageUrl'), '') is not null
    ) as raw_candidates,
    count(*) filter (
        where cover_image_id is null
          and raw_metadata ? 'coverImageUrl'
          and lower(trim(raw_metadata ->> 'coverImageUrl')) in ('null', 'undefined')
    ) as raw_invalid_literals,
    count(*) filter (
        where cover_image_id is null
          and raw_metadata ? 'coverImageUrl'
          and length(trim(raw_metadata ->> 'coverImageUrl')) > 2048
    ) as raw_too_long
from raw_videos;

select count(*) as unified_candidates
from unified_videos uv
where uv.cover_image_id is null
  and lower(coalesce(uv.locked_fields::text, '')) not like '%coverimageurl%'
  and exists (
      select 1
      from raw_video_unified_video rvuv
      join raw_videos rv on rv.id = rvuv.raw_video_id
      where rvuv.unified_video_id = uv.id
        and rv.cover_image_id is not null
  );
```

## 执行后验收

```sql
select
    count(*) as raw_missing_after_backfill
from raw_videos
where cover_image_id is null
  and raw_metadata ? 'coverImageUrl'
  and nullif(trim(raw_metadata ->> 'coverImageUrl'), '') is not null
  and lower(trim(raw_metadata ->> 'coverImageUrl')) not in ('null', 'undefined')
  and length(trim(raw_metadata ->> 'coverImageUrl')) <= 2048;

select
    count(*) as orphan_cover_refs
from raw_videos rv
left join cover_images ci on ci.id = rv.cover_image_id
where rv.cover_image_id is not null
  and ci.id is null;

select
    count(*) as unified_empty_with_raw_cover
from unified_videos uv
where uv.cover_image_id is null
  and lower(coalesce(uv.locked_fields::text, '')) not like '%coverimageurl%'
  and exists (
      select 1
      from raw_video_unified_video rvuv
      join raw_videos rv on rv.id = rvuv.raw_video_id
      where rvuv.unified_video_id = uv.id
        and rv.cover_image_id is not null
  );
```

## 回滚边界

本迁移只新增封面引用与补空关联。若需要回滚展示效果，优先回滚服务镜像或通过针对本 changeset 执行前记录的候选 ID 清空对应 `cover_image_id`。不建议删除 `cover_images`，因为后续可能已被手工关联、下载或同步。
