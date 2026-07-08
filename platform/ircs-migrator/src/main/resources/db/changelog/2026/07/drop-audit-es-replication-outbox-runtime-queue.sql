-- liquibase formatted sql

-- changeset prodigalgal:20260617-drop-audit-es-replication-outbox-runtime-queue
DROP TABLE IF EXISTS audit_es_replication_outbox;
