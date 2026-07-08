--liquibase formatted sql

--changeset prodigalgal:2026-07-add-requested-magnet-providers
INSERT INTO magnet_providers (
    id,
    code,
    name,
    provider_type,
    base_url,
    enabled,
    priority,
    risk_level,
    supported_external_ids,
    min_delay_ms,
    max_delay_ms,
    timeout_ms,
    result_limit,
    auto_approve_allowed,
    content_policy
) VALUES
(
    gen_random_uuid(),
    'eztv',
    'EZTVx API',
    'EZTV',
    'https://eztvx.to',
    true,
    30,
    'HIGH',
    '["IMDB"]'::jsonb,
    2000,
    5000,
    12000,
    50,
    true,
    '使用 EZTV get-torrents API，IMDb ID 会去除 tt 前缀；标题搜索不作为默认策略，避免无过滤全量返回。'
),
(
    gen_random_uuid(),
    'ext_to',
    'EXT Torrents',
    'EXT_TO',
    'https://ext.to',
    true,
    40,
    'HIGH',
    '["TITLE_YEAR","TITLE"]'::jsonb,
    5000,
    12000,
    15000,
    20,
    false,
    '使用公开 HTML 搜索页解析磁力链接；遇到 Cloudflare challenge/403 时按 provider 受限失败降级，不绕过人机验证。'
),
(
    gen_random_uuid(),
    'thepiratebay_org',
    'The Pirate Bay Frontend',
    'THE_PIRATE_BAY_FRONTEND',
    'https://thepiratebay.org',
    true,
    45,
    'HIGH',
    '["TITLE_YEAR","TITLE"]'::jsonb,
    5000,
    12000,
    15000,
    20,
    false,
    '使用 thepiratebay.org 公开前端搜索页作为 APIBay 的补充路径；不可达或受限时降级为 provider 失败。'
)
ON CONFLICT (code) DO UPDATE
SET name = EXCLUDED.name,
    provider_type = EXCLUDED.provider_type,
    base_url = EXCLUDED.base_url,
    enabled = EXCLUDED.enabled,
    priority = EXCLUDED.priority,
    risk_level = EXCLUDED.risk_level,
    supported_external_ids = EXCLUDED.supported_external_ids,
    min_delay_ms = EXCLUDED.min_delay_ms,
    max_delay_ms = EXCLUDED.max_delay_ms,
    timeout_ms = EXCLUDED.timeout_ms,
    result_limit = EXCLUDED.result_limit,
    auto_approve_allowed = EXCLUDED.auto_approve_allowed,
    content_policy = EXCLUDED.content_policy,
    updated_at = NOW(),
    version = COALESCE(magnet_providers.version, 0) + 1;

--changeset prodigalgal:2026-07-expand-magnet-real-provider-allowlist
INSERT INTO system_configs (id, created_at, updated_at, version, config_key, config_value, description)
VALUES (
    gen_random_uuid(),
    NOW(),
    NOW(),
    0,
    'app.magnet.real-provider.allowlist',
    'YTS_BZ,THE_PIRATE_BAY,EZTV,EXT_TO,THE_PIRATE_BAY_FRONTEND',
    '允许调用的真实磁链 Provider 类型或编码'
)
ON CONFLICT (config_key) DO UPDATE
SET config_value = EXCLUDED.config_value,
    description = EXCLUDED.description,
    updated_at = NOW(),
    version = COALESCE(system_configs.version, 0) + 1;
