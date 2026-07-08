-- liquibase formatted sql

-- changeset zzp84:1785800000000-add-portal-home-read-indexes
-- 说明: 优化门户首页、分类页和时下热门的公开读路径，减少冷缓存重建时的排序与回表成本。

CREATE INDEX IF NOT EXISTS idx_unified_videos_portal_visibility_published
    ON unified_videos (content_visibility, published_at DESC NULLS LAST, updated_at DESC NULLS LAST, id);

CREATE INDEX IF NOT EXISTS idx_unified_videos_portal_visibility_category_published
    ON unified_videos (content_visibility, category_code, published_at DESC NULLS LAST, updated_at DESC NULLS LAST, id);

CREATE INDEX IF NOT EXISTS idx_unified_videos_portal_visibility_trend
    ON unified_videos (content_visibility, last_trend_at DESC NULLS LAST, score DESC NULLS LAST, updated_at DESC NULLS LAST, id);

CREATE INDEX IF NOT EXISTS idx_unified_videos_portal_visibility_category_trend
    ON unified_videos (content_visibility, category_code, last_trend_at DESC NULLS LAST, score DESC NULLS LAST, updated_at DESC NULLS LAST, id);
