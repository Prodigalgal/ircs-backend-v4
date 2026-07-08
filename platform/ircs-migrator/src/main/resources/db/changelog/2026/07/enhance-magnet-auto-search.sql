--liquibase formatted sql

--changeset prodigalgal:2026-07-enhance-magnet-provider-title-search
UPDATE magnet_providers
SET supported_external_ids = '["IMDB","TITLE_YEAR","TITLE"]'::jsonb,
    content_policy = '优先使用 IMDb 查询；缺少 IMDb 时允许使用标题/年份兜底查询，结果仍通过 info_hash 去重。'
WHERE code = 'thepiratebay';

--changeset prodigalgal:2026-07-enable-magnet-auto-search-config
INSERT INTO system_configs (id, created_at, updated_at, version, config_key, config_value, description)
VALUES
    (gen_random_uuid(), NOW(), NOW(), 0, 'app.magnet.real-provider.enabled', 'true', '是否启用真实磁链 Provider 爬取'),
    (gen_random_uuid(), NOW(), NOW(), 0, 'app.magnet.real-provider.allowlist', 'YTS_BZ,THE_PIRATE_BAY', '允许调用的真实磁链 Provider 类型或编码'),
    (gen_random_uuid(), NOW(), NOW(), 0, 'app.magnet.auto-search.enabled', 'true', '是否启用聚合视频磁链自动补链'),
    (gen_random_uuid(), NOW(), NOW(), 0, 'app.magnet.auto-search.batch-size', '10', '磁链自动补链每轮聚合视频数量'),
    (gen_random_uuid(), NOW(), NOW(), 0, 'app.magnet.auto-search.cooldown', 'PT12H', '同一聚合视频自动补链冷却时间')
ON CONFLICT (config_key) DO UPDATE
SET config_value = EXCLUDED.config_value,
    description = EXCLUDED.description,
    updated_at = NOW(),
    version = COALESCE(system_configs.version, 0) + 1
WHERE system_configs.config_key IN (
    'app.magnet.real-provider.enabled',
    'app.magnet.real-provider.allowlist',
    'app.magnet.auto-search.enabled',
    'app.magnet.auto-search.batch-size',
    'app.magnet.auto-search.cooldown'
);
