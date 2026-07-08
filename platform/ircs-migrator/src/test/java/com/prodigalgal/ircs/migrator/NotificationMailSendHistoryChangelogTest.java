package com.prodigalgal.ircs.migrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class NotificationMailSendHistoryChangelogTest {

    private static final String MAIL_HISTORY_CHANGELOG =
            "db/changelog/2026/07/add-notification-mail-send-history.sql";
    private static final String AUDIT_GOVERNANCE_CHANGELOG =
            "db/changelog/2026/07/add-audit-archive-retention-governance.sql";
    private static final String SEARCH_OUTBOX_REPAIR_CHANGELOG =
            "db/changelog/2026/07/repair-search-outbox-schema.sql";
    private static final String SEARCH_RUNTIME_QUEUE_DROP_CHANGELOG =
            "db/changelog/2026/07/drop-search-sync-tasks-runtime-queue.sql";
    private static final String AUDIT_RUNTIME_QUEUE_DROP_CHANGELOG =
            "db/changelog/2026/07/drop-audit-es-replication-outbox-runtime-queue.sql";
    private static final String LLM_CLEANING_RUNTIME_QUEUE_DROP_CHANGELOG =
            "db/changelog/2026/07/drop-llm-cleaning-claim-lease-runtime-queue.sql";
    private static final String AVATAR_SYNC_RUNTIME_QUEUE_DROP_CHANGELOG =
            "db/changelog/2026/07/drop-avatar-sync-claim-lease-runtime-queue.sql";
    private static final String ADMIN_API_TOKENS_CHANGELOG =
            "db/changelog/2026/07/add-admin-api-tokens.sql";
    private static final String OPS_ALERT_SELF_HEALING_CHANGELOG =
            "db/changelog/2026/07/add-ops-alert-self-healing.sql";
    private static final String COVER_IMAGE_BACKFILL_CHANGELOG =
            "db/changelog/2026/07/backfill-raw-video-cover-images.sql";

    @Test
    void masterChangelogIncludesNotificationMailSendHistory() throws IOException {
        String master = readClasspath("db/changelog/db.changelog-master.yaml");

        assertThat(master).contains("file: " + MAIL_HISTORY_CHANGELOG);
        assertThat(master).contains("file: " + AUDIT_GOVERNANCE_CHANGELOG);
        assertThat(master).contains("file: " + SEARCH_OUTBOX_REPAIR_CHANGELOG);
        assertThat(master).contains("file: " + SEARCH_RUNTIME_QUEUE_DROP_CHANGELOG);
        assertThat(master).contains("file: " + AUDIT_RUNTIME_QUEUE_DROP_CHANGELOG);
        assertThat(master).contains("file: " + LLM_CLEANING_RUNTIME_QUEUE_DROP_CHANGELOG);
        assertThat(master).contains("file: " + AVATAR_SYNC_RUNTIME_QUEUE_DROP_CHANGELOG);
        assertThat(master).contains("file: " + ADMIN_API_TOKENS_CHANGELOG);
        assertThat(master).contains("file: " + OPS_ALERT_SELF_HEALING_CHANGELOG);
        assertThat(master).contains("file: " + COVER_IMAGE_BACKFILL_CHANGELOG);
    }

    @Test
    void mailHistoryChangelogKeepsOriginalAppliedSchema() throws IOException {
        String migrator = normalize(readClasspath(MAIL_HISTORY_CHANGELOG));

        assertThat(migrator).contains("liquibase formatted sql");
        assertThat(migrator).contains("changeset prodigalgal:20260609-add-notification-mail-send-history");
        assertThat(migrator).contains("CREATE TABLE IF NOT EXISTS notification_mail_send_history");
        assertThat(migrator).contains("idx_notification_mail_send_history_created");
        assertThat(migrator).contains("idx_notification_mail_send_history_status_mode_created");
        assertThat(migrator).contains("idx_notification_mail_send_history_template_created");
        assertThat(migrator).contains("idx_notification_mail_send_history_correlation");
        assertThat(migrator).doesNotContain("audit_class");
        assertThat(migrator).doesNotContain("audit_es_replication_outbox");
        assertThat(migrator).doesNotContain("audit_archive_entries");
    }

    @Test
    void auditGovernanceChangelogUpgradesMailHistoryContract() throws IOException {
        String governance = normalize(readClasspath(AUDIT_GOVERNANCE_CHANGELOG));

        assertThat(governance).contains("ALTER TABLE notification_mail_send_history");
        assertThat(governance).contains("ADD COLUMN IF NOT EXISTS audit_class VARCHAR(32)");
        assertThat(governance).contains("idx_notification_mail_send_history_audit_class_created");
        assertThat(governance).contains("CREATE TABLE IF NOT EXISTS audit_es_replication_outbox");
        assertThat(governance).contains("CREATE TABLE IF NOT EXISTS audit_archive_entries");
    }

    @Test
    void searchOutboxRepairChangelogIsIdempotentAndRuntimeReady() throws IOException {
        String repair = normalize(readClasspath(SEARCH_OUTBOX_REPAIR_CHANGELOG));

        assertThat(repair).contains("liquibase formatted sql");
        assertThat(repair).contains("CREATE TABLE IF NOT EXISTS search_sync_tasks");
        assertThat(repair).contains("ADD COLUMN IF NOT EXISTS updated_at");
        assertThat(repair).contains("ADD COLUMN IF NOT EXISTS locked_by VARCHAR(128)");
        assertThat(repair).contains("ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP WITHOUT TIME ZONE");
        assertThat(repair).contains("ADD COLUMN IF NOT EXISTS last_error TEXT");
        assertThat(repair).contains("CREATE INDEX IF NOT EXISTS idx_search_sync_poll");
        assertThat(repair).contains("CREATE INDEX IF NOT EXISTS idx_search_sync_processing_lease");
        assertThat(repair).contains("CREATE INDEX IF NOT EXISTS idx_search_sync_entity_status");
    }

    @Test
    void searchRuntimeQueueDropChangelogRemovesDbHotQueueTable() throws IOException {
        String drop = normalize(readClasspath(SEARCH_RUNTIME_QUEUE_DROP_CHANGELOG));

        assertThat(drop).contains("liquibase formatted sql");
        assertThat(drop).contains("DROP TABLE IF EXISTS search_sync_tasks");
    }

    @Test
    void auditRuntimeQueueDropChangelogRemovesDbOutboxTable() throws IOException {
        String drop = normalize(readClasspath(AUDIT_RUNTIME_QUEUE_DROP_CHANGELOG));

        assertThat(drop).contains("liquibase formatted sql");
        assertThat(drop).contains("DROP TABLE IF EXISTS audit_es_replication_outbox");
    }

    @Test
    void llmCleaningRuntimeQueueDropChangelogRemovesDbClaimLeaseColumns() throws IOException {
        String drop = normalize(readClasspath(LLM_CLEANING_RUNTIME_QUEUE_DROP_CHANGELOG));

        assertThat(drop).contains("liquibase formatted sql");
        assertThat(drop).contains("DROP INDEX IF EXISTS idx_raw_genres_llm_cleaning_claim");
        assertThat(drop).contains("DROP COLUMN IF EXISTS llm_cleaning_claimed_by");
        assertThat(drop).contains("DROP COLUMN IF EXISTS llm_cleaning_claimed_until");
    }

    @Test
    void avatarSyncRuntimeQueueDropChangelogRemovesDbClaimLeaseColumns() throws IOException {
        String drop = normalize(readClasspath(AVATAR_SYNC_RUNTIME_QUEUE_DROP_CHANGELOG));

        assertThat(drop).contains("liquibase formatted sql");
        assertThat(drop).contains("DROP INDEX IF EXISTS idx_members_avatar_sync_claim");
        assertThat(drop).contains("DROP COLUMN IF EXISTS avatar_sync_claimed_by");
        assertThat(drop).contains("DROP COLUMN IF EXISTS avatar_sync_claimed_until");
    }

    @Test
    void adminApiTokenChangelogStoresOnlyHashedTokens() throws IOException {
        String changelog = normalize(readClasspath(ADMIN_API_TOKENS_CHANGELOG));

        assertThat(changelog).contains("liquibase formatted sql");
        assertThat(changelog).contains("CREATE TABLE IF NOT EXISTS admin_api_tokens");
        assertThat(changelog).contains("token_prefix VARCHAR(32) NOT NULL");
        assertThat(changelog).contains("token_hash VARCHAR(64) NOT NULL");
        assertThat(changelog).contains("ux_admin_api_tokens_hash");
        assertThat(changelog).contains("ck_admin_api_tokens_status");
        assertThat(changelog).doesNotContain("token_value");
        assertThat(changelog).doesNotContain("raw_token");
    }

    @Test
    void opsAlertSelfHealingChangelogCreatesControlPlaneTables() throws IOException {
        String changelog = normalize(readClasspath(OPS_ALERT_SELF_HEALING_CHANGELOG));

        assertThat(changelog).contains("liquibase formatted sql");
        assertThat(changelog).contains("CREATE TABLE IF NOT EXISTS ops_alert_events");
        assertThat(changelog).contains("CREATE TABLE IF NOT EXISTS ops_incidents");
        assertThat(changelog).contains("CREATE TABLE IF NOT EXISTS ops_healing_actions");
        assertThat(changelog).contains("idx_ops_alert_events_fingerprint_created");
        assertThat(changelog).contains("idx_ops_incidents_fingerprint_status");
        assertThat(changelog).contains("ux_ops_incidents_open_fingerprint");
        assertThat(changelog).contains("idx_ops_healing_actions_incident_created");
        assertThat(changelog).contains("dry_run BOOLEAN NOT NULL DEFAULT true");
    }

    @Test
    void coverImageBackfillChangelogIsSafeAndIdempotent() throws IOException {
        String changelog = normalize(readClasspath(COVER_IMAGE_BACKFILL_CHANGELOG));

        assertThat(changelog).contains("liquibase formatted sql");
        assertThat(changelog).contains("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        assertThat(changelog).contains("backfill-raw-video-cover-images splitStatements:false");
        assertThat(changelog).contains("rv.cover_image_id IS NULL");
        assertThat(changelog).contains("uv.cover_image_id IS NULL");
        assertThat(changelog).contains("raw_metadata ? 'coverImageUrl'");
        assertThat(changelog).contains("ON CONFLICT (domain_hash) DO UPDATE");
        assertThat(changelog).contains("ON CONFLICT (original_url, source_domain_id) DO UPDATE");
        assertThat(changelog).contains("lower(coalesce(uv.locked_fields::text, '')) NOT LIKE '%coverimageurl%'");
        assertThat(changelog).doesNotContain("publishImageDownload");
        assertThat(changelog).doesNotContain("delete from cover_images");
    }

    private static String readClasspath(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        assertThat(resource.exists()).as("classpath resource %s exists", path).isTrue();
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    private static String normalize(String value) {
        return value.replace("\r\n", "\n").trim();
    }
}
