-- liquibase formatted sql

-- changeset zzp84:1782000000001-remove-core-pipeline-switch-configs
-- 说明: 主流程环节不再允许通过系统参数关闭，仅增强/投影环节保留动态开关。
DELETE FROM system_configs
WHERE config_key IN (
    'app.normalization.valkey-worker.enabled',
    'app.aggregation.work-queue.worker.enabled',
    'app.metadata.enabled',
    'app.metadata.valkey-dispatcher.enabled',
    'app.metadata.valkey-provider-worker.enabled'
);
