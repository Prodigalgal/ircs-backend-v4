--liquibase formatted sql

--changeset prodigalgal:2026-07-configure-magnet-provider-traffic-limits
INSERT INTO system_configs (id, created_at, updated_at, version, config_key, config_value, description)
VALUES
    (gen_random_uuid(), NOW(), NOW(), 0, 'app.magnet.traffic.enabled', 'true', '是否启用磁链 Provider 流量闸门'),
    (gen_random_uuid(), NOW(), NOW(), 0, 'app.magnet.traffic.default-gap-ms', '3000', '磁链 Provider 同出口默认请求间隔(毫秒)'),
    (
        gen_random_uuid(),
        NOW(),
        NOW(),
        0,
        'app.magnet.traffic.provider-gap-ms',
        'YTS_BZ=3000,THE_PIRATE_BAY=8000,EZTV=3000,EXT_TO=15000,THE_PIRATE_BAY_FRONTEND=15000',
        '磁链 Provider 单站请求间隔覆盖(毫秒)，格式 PROVIDER=MS'
    ),
    (gen_random_uuid(), NOW(), NOW(), 0, 'app.magnet.traffic.max-wait', 'PT2M', '磁链 Provider 流量闸门最大等待时间'),
    (gen_random_uuid(), NOW(), NOW(), 0, 'app.magnet.traffic.ttl', 'PT10M', '磁链 Provider 流量闸门 Valkey 状态 TTL')
ON CONFLICT (config_key) DO UPDATE
SET config_value = EXCLUDED.config_value,
    description = EXCLUDED.description,
    updated_at = NOW(),
    version = COALESCE(system_configs.version, 0) + 1
WHERE system_configs.config_key IN (
    'app.magnet.traffic.enabled',
    'app.magnet.traffic.default-gap-ms',
    'app.magnet.traffic.provider-gap-ms',
    'app.magnet.traffic.max-wait',
    'app.magnet.traffic.ttl'
);
