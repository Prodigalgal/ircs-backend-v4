-- liquibase formatted sql

-- changeset zzp84:1781000000003-adjust-global-traffic-max-wait
-- 说明: 将采集流量控制最大排队等待时间从过短的 15 秒提升到 120 秒，降低高并发采集详情页的误失败率
UPDATE system_configs
SET config_value = '120000',
    updated_at = NOW()
WHERE config_key = 'global.traffic.max-wait-ms'
  AND CASE
          WHEN config_value ~ '^[0-9]+$' THEN config_value::BIGINT < 120000
          ELSE TRUE
      END;

INSERT INTO system_configs (id, created_at, updated_at, version, config_key, config_value, description)
SELECT gen_random_uuid(),
       NOW(),
       NOW(),
       0,
       'global.traffic.max-wait-ms',
       '120000',
       '流量控制最大容忍等待时间(毫秒)'
WHERE NOT EXISTS (
    SELECT 1
    FROM system_configs
    WHERE config_key = 'global.traffic.max-wait-ms'
);
