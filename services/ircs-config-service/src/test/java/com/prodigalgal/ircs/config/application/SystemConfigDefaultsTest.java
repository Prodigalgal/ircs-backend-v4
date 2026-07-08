package com.prodigalgal.ircs.config.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;

class SystemConfigDefaultsTest {

    private final SystemConfigDefaults defaults = new SystemConfigDefaults(NoOpPasswordEncoder.getInstance());

    @Test
    void includesCurrentSystemConfigKeyCatalog() {
        Set<String> actual = defaults.all().stream()
                .map(SystemConfigDefaults.DefaultConfig::key)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> expected = java.util.stream.Stream.concat(
                        CURRENT_SYSTEM_CONFIG_KEYS.stream(), V3_OAUTH_CONFIG_KEYS.stream())
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(expected, actual);
        assertEquals(expected.size(), defaults.all().size());
    }

    @Test
    void frontendSystemSettingsKeysAreCoveredByBackendDefaults() throws Exception {
        Path frontendConfig = workspaceRoot().resolve(
                Path.of("frontends", "admin", "src", "features", "settings", "SystemConfigList.jsx"));
        String source = Files.readString(frontendConfig);
        Pattern keyPattern = Pattern.compile("key:\\s*'([^']+)'");
        Set<String> frontendKeys = keyPattern.matcher(source).results()
                .map(match -> match.group(1))
                .collect(Collectors.toUnmodifiableSet());
        Set<String> backendKeys = defaults.all().stream()
                .map(SystemConfigDefaults.DefaultConfig::key)
                .collect(Collectors.toUnmodifiableSet());

        Set<String> missing = frontendKeys.stream()
                .filter(key -> !backendKeys.contains(key))
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        assertTrue(missing.isEmpty(), "Frontend system config keys missing backend defaults: " + missing);
    }

    private static Path workspaceRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("backend"))
                    && Files.exists(current.resolve("frontends").resolve("admin"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate workspace root from " + Path.of("").toAbsolutePath());
    }

    @Test
    void exposesCoreAndRequiredKeyBoundaries() {
        assertTrue(defaults.isCoreKey("security.admin.password"));
        assertTrue(defaults.isCoreKey("security.jwt.secret"));
        assertTrue(defaults.isCoreKey("app.ai.llm.prompt.language"));
        assertTrue(defaults.isCoreKey("app.storage.r2.enabled"));
        assertTrue(defaults.isCoreKey("app.aggregation.work-queue.batch-size"));
        assertTrue(defaults.isRequiredKey("app.storage.r2.bucket-name"));
        assertTrue(defaults.isRequiredKey("app.storage.r2.public-domain"));
        assertFalse(defaults.isRequiredKey("app.metadata.tmdb.proxy.password"));
        assertFalse(defaults.isCoreKey("custom.user.config"));
    }

    @Test
    void keepsV1ImportantDefaultValues() {
        assertEquals("100", defaults.staticDefaultValue("normalization.batch-size.min").orElseThrow());
        assertEquals("classpath:presets/ratelimit_rules.json",
                defaults.staticDefaultValue("security.ratelimit.rules").orElseThrow());
        assertEquals("3000", defaults.staticDefaultValue("global.traffic.safety-floor-ms").orElseThrow());
        assertEquals("HTTP", defaults.staticDefaultValue("app.metadata.douban.proxy.type").orElseThrow());
        assertEquals("0 0/5 * * * ?", defaults.staticDefaultValue("app.search.sync.cron").orElseThrow());
        assertEquals("true", defaults.staticDefaultValue("app.storage.image.download.enabled").orElseThrow());
        assertEquals("true", defaults.staticDefaultValue("app.storage.r2.enabled").orElseThrow());
        assertEquals("60000", defaults.staticDefaultValue("app.mail.rate-limit.max-wait-ms").orElseThrow());
        assertEquals("", defaults.staticDefaultValue("app.scraper.traffic.egress-id").orElseThrow());
        assertEquals("true", defaults.staticDefaultValue("app.ai.llm.enabled").orElseThrow());
        assertEquals("true", defaults.staticDefaultValue("app.maintenance.playlist-repair.enabled").orElseThrow());
        assertEquals("false", defaults.staticDefaultValue("app.metadata.tmdb.proxy.enabled").orElseThrow());
        assertEquals("150", defaults.staticDefaultValue("app.metadata.public-traffic.default-gap-ms").orElseThrow());
        assertEquals("40", defaults.staticDefaultValue("app.metadata.valkey-provider-worker.batch-size").orElseThrow());
        assertEquals("16", defaults.staticDefaultValue("app.metadata.valkey-provider-worker.parallelism").orElseThrow());
        assertEquals("PT2S", defaults.staticDefaultValue("app.runtime-config.local-cache.ttl").orElseThrow());
        assertEquals("PT3S", defaults.staticDefaultValue("app.ops.dashboard.stream.metrics.interval").orElseThrow());
        assertEquals("PT5S", defaults.staticDefaultValue("app.ops.dashboard.stream.search-ops.interval").orElseThrow());
        assertEquals("PT1M", defaults.staticDefaultValue("app.ops.dashboard.snapshot.refresh-interval").orElseThrow());
        assertEquals("PT2M", defaults.staticDefaultValue("app.ops.dashboard.snapshot.fresh-ttl").orElseThrow());
        assertEquals("PT15M", defaults.staticDefaultValue("app.ops.dashboard.snapshot.stale-grace").orElseThrow());
        assertEquals("PT2M", defaults.staticDefaultValue("app.ops.dashboard.read-model-snapshot.fresh-ttl").orElseThrow());
        assertEquals("PT15M", defaults.staticDefaultValue("app.ops.dashboard.read-model-snapshot.stale-grace").orElseThrow());
        assertEquals("PT15S", defaults.staticDefaultValue(
                "app.ops-alert.first-page-cache.warmup.refresh-interval").orElseThrow());
        assertEquals("20", defaults.staticDefaultValue("app.ops-alert.first-page-cache.warmup.page-size").orElseThrow());
        assertEquals("PT15S", defaults.staticDefaultValue("app.ops.metrics.cache-ttl").orElseThrow());
        assertEquals("PT10M", defaults.staticDefaultValue("app.ops.metrics.consumer-no-progress-grace").orElseThrow());
        assertEquals("PT10M", defaults.staticDefaultValue(
                "app.aggregation.work-queue.worker.processing-stall-threshold").orElseThrow());
        assertEquals("true", defaults.staticDefaultValue(
                "app.normalization.pending-watchdog.enabled").orElseThrow());
        assertEquals("200", defaults.staticDefaultValue(
                "app.normalization.pending-watchdog.batch-size").orElseThrow());
        assertEquals("PT5M", defaults.staticDefaultValue(
                "app.normalization.pending-watchdog.min-pending-age").orElseThrow());
        assertEquals("PT5S", defaults.staticDefaultValue("app.ops.task-runtime.overview.cache-ttl").orElseThrow());
        assertEquals("PT5S", defaults.staticDefaultValue("app.ops.runtime-dlq.cache-ttl").orElseThrow());
        assertEquals("3", defaults.staticDefaultValue("app.ops.rabbit-dlq.sampled-queue-limit").orElseThrow());
        assertEquals("PT3S", defaults.staticDefaultValue("app.ops.rabbit-dlq.queue-snapshot-cache-ttl").orElseThrow());
        assertEquals("PT5S", defaults.staticDefaultValue("app.ops.rabbit-dlq.sample-cache-ttl").orElseThrow());
        assertEquals("true", defaults.staticDefaultValue("app.log-retention.enabled").orElseThrow());
        assertEquals("P30D", defaults.staticDefaultValue("app.log-retention.default-retention").orElseThrow());
        assertEquals("P30D", defaults.staticDefaultValue("app.log-retention.target.audit-es.retention").orElseThrow());
        assertEquals("true", defaults.staticDefaultValue("app.audit.es-replication.enabled").orElseThrow());
        assertEquals("PT10S", defaults.staticDefaultValue("app.rate-metrics.bucket-size").orElseThrow());
        assertEquals("false", defaults.staticDefaultValue("app.notification.webhook.enabled").orElseThrow());
        assertEquals("PT5S", defaults.staticDefaultValue("app.notification.webhook.request-timeout").orElseThrow());
        assertEquals("65536", defaults.staticDefaultValue("app.notification.webhook.max-payload-bytes").orElseThrow());
        assertEquals("true", defaults.staticDefaultValue("app.ops.config-listener.enabled").orElseThrow());
        assertEquals("true", defaults.staticDefaultValue("app.aggregation.config-listener.enabled").orElseThrow());
        assertEquals("true", defaults.staticDefaultValue("app.storage.image.download-backfill.enabled").orElseThrow());
        assertEquals("", defaults.staticDefaultValue("app.storage.image.traffic.egress-id").orElseThrow());
        assertEquals("25", defaults.staticDefaultValue("app.storage.image.download-backfill.batch-size").orElseThrow());
        assertEquals("false", defaults.staticDefaultValue("app.ops.service-restart.enabled").orElseThrow());
        assertEquals("false", defaults.staticDefaultValue("member.register.time-window.enabled").orElseThrow());
        assertEquals("1", defaults.staticDefaultValue("member.message.point-cost").orElseThrow());
        assertEquals("5", defaults.staticDefaultValue("member.media-request.daily-limit").orElseThrow());
        assertEquals("3", defaults.staticDefaultValue("member.media-request.point-cost").orElseThrow());
        assertEquals("false", defaults.staticDefaultValue("member.oauth.enabled").orElseThrow());
        assertEquals("true", defaults.staticDefaultValue("app.task.scheduler.enabled").orElseThrow());
        assertEquals("false", defaults.staticDefaultValue("app.search.work-queue.worker.enabled").orElseThrow());
        assertEquals("false", defaults.staticDefaultValue("app.storage.r2.work-queue.worker.enabled").orElseThrow());
        assertEquals("false",
                defaults.staticDefaultValue("app.normalization.llm-cleaning.work-queue.worker.enabled").orElseThrow());
        assertEquals("false", defaults.staticDefaultValue("app.magnet.real-provider.enabled").orElseThrow());
        assertEquals("", defaults.staticDefaultValue("app.magnet.traffic.egress-id").orElseThrow());
        assertEquals("YTS_BZ,THE_PIRATE_BAY,EZTV,EXT_TO,THE_PIRATE_BAY_FRONTEND",
                defaults.staticDefaultValue("app.magnet.real-provider.allowlist").orElseThrow());
        assertEquals("false", defaults.staticDefaultValue("app.magnet.auto-search.enabled").orElseThrow());
        assertEquals("10", defaults.staticDefaultValue("app.magnet.auto-search.batch-size").orElseThrow());
        assertEquals("PT12H", defaults.staticDefaultValue("app.magnet.auto-search.cooldown").orElseThrow());
        assertEquals("true", defaults.staticDefaultValue("app.magnet.work-queue.submission.enabled").orElseThrow());
        assertEquals("true", defaults.staticDefaultValue("app.magnet.work-queue.worker.enabled").orElseThrow());
        assertEquals("5", defaults.staticDefaultValue("app.magnet.work-queue.worker.batch-size").orElseThrow());
        assertEquals("true", defaults.staticDefaultValue("app.task.media-request.enabled").orElseThrow());
        assertEquals("20", defaults.staticDefaultValue("app.task.media-request.batch-size").orElseThrow());
        assertEquals("60000", defaults.staticDefaultValue("app.task.media-request.fixed-delay-ms").orElseThrow());
        assertEquals("true", defaults.staticDefaultValue("member.oauth.auto-register.enabled").orElseThrow());
        assertEquals("false", defaults.staticDefaultValue("member.oauth.bind-existing-email.enabled").orElseThrow());
        assertEquals("true",
                defaults.staticDefaultValue("member.oauth.email-ownership-verification.enabled").orElseThrow());
        assertEquals("https://huawai.mnnu.eu.org", defaults.staticDefaultValue("app.frontend.url").orElseThrow());
        assertEquals("https://huawai.mnnu.eu.org",
                defaults.staticDefaultValue("member.oauth.redirect-base-url").orElseThrow());
        assertEquals("google,x,github,gitee,wechat,qq",
                defaults.staticDefaultValue("member.oauth.allowed-providers").orElseThrow());
        assertEquals("PT15M", defaults.staticDefaultValue("app.portal.cache.detail-ttl").orElseThrow());
        assertEquals("PT30M", defaults.staticDefaultValue("app.portal.cache.dictionary-ttl").orElseThrow());
    }

    @Test
    void keepsV1LlmPromptRawPreservationContract() {
        String prompt = defaults.staticDefaultValue("app.ai.llm.prompt.language").orElseThrow();

        assertTrue(prompt.contains("Do not normalize Traditional Chinese"));
        assertTrue(prompt.contains("{rawItems}"));
        assertTrue(prompt.contains("{standardItems}"));
    }

    @Test
    void resolvesSensitiveDefaultsWithoutBlankValues() {
        SystemConfigDefaults.ResolvedDefault adminPassword = defaults.resolve(new SystemConfigDefaults.DefaultConfig(
                "security.admin.password",
                "",
                "admin password"));
        SystemConfigDefaults.ResolvedDefault jwtSecret = defaults.resolve(new SystemConfigDefaults.DefaultConfig(
                "security.jwt.secret",
                "",
                "jwt secret"));

        assertFalse(adminPassword.value().isBlank());
        assertEquals(64, jwtSecret.value().length());
    }

    @Test
    void marksNewEnabledDefaultsForLegacyFalseUpgrade() {
        SystemConfigDefaults.DefaultConfig imageDownload = defaults.all().stream()
                .filter(config -> config.key().equals("app.storage.image.download.enabled"))
                .findFirst()
                .orElseThrow();
        SystemConfigDefaults.DefaultConfig proxy = defaults.all().stream()
                .filter(config -> config.key().equals("app.metadata.tmdb.proxy.enabled"))
                .findFirst()
                .orElseThrow();

        assertTrue(imageDownload.upgradeLegacyFalse());
        assertFalse(proxy.upgradeLegacyFalse());
    }

    @Test
    void upgradesLegacyUnknownTrafficEgressDefaults() {
        assertLegacyUnknownUpgrade("app.scraper.traffic.egress-id");
        assertLegacyUnknownUpgrade("app.magnet.traffic.egress-id");
        assertLegacyUnknownUpgrade("app.storage.image.traffic.egress-id");
    }

    @Test
    void upgradesLegacyPortalDetailCacheTtl() {
        SystemConfigDefaults.ResolvedDefault resolved = defaults.all().stream()
                .filter(config -> config.key().equals("app.portal.cache.detail-ttl"))
                .findFirst()
                .map(defaults::resolve)
                .orElseThrow();

        assertEquals("PT15M", resolved.value());
        assertEquals("PT5M", resolved.upgradeLegacyValue());
    }

    private void assertLegacyUnknownUpgrade(String key) {
        SystemConfigDefaults.ResolvedDefault resolved = defaults.all().stream()
                .filter(config -> config.key().equals(key))
                .findFirst()
                .map(defaults::resolve)
                .orElseThrow();

        assertEquals("", resolved.value());
        assertEquals("unknown", resolved.upgradeLegacyValue());
    }

    @Test
    void classifiesHotAndRestartRequiredConfigs() {
        SystemConfigDefaults.ConfigMetadata hot = defaults.metadata("app.ops.metrics.cache-ttl");
        SystemConfigDefaults.ConfigMetadata taskRuntimeCacheHot =
                defaults.metadata("app.ops.task-runtime.overview.cache-ttl");
        SystemConfigDefaults.ConfigMetadata runtimeDlqCacheHot = defaults.metadata("app.ops.runtime-dlq.cache-ttl");
        SystemConfigDefaults.ConfigMetadata serviceRestartHot = defaults.metadata("app.ops.service-restart.kubernetes-api-base-url");
        SystemConfigDefaults.ConfigMetadata cacheRestart = defaults.metadata("app.portal.cache.metadata-ttl");
        SystemConfigDefaults.ConfigMetadata taskRestart = defaults.metadata("app.task.queue.enabled");
        SystemConfigDefaults.ConfigMetadata restart = defaults.metadata("security.jwt.secret");
        SystemConfigDefaults.ConfigMetadata storageRestart = defaults.metadata("app.storage.r2.secret-key");
        SystemConfigDefaults.ConfigMetadata listenerRestart = defaults.metadata("app.search.config-listener.enabled");
        SystemConfigDefaults.ConfigMetadata logRetentionHot = defaults.metadata("app.log-retention.target.audit-es.retention");
        SystemConfigDefaults.ConfigMetadata logRetentionScheduleRestart = defaults.metadata("app.log-retention.fixed-delay-ms");
        SystemConfigDefaults.ConfigMetadata notificationWebhookHot = defaults.metadata("app.notification.webhook.enabled");
        SystemConfigDefaults.ConfigMetadata rabbitDlqCacheHot = defaults.metadata("app.ops.rabbit-dlq.sample-cache-ttl");
        SystemConfigDefaults.ConfigMetadata magnetRealProviderHot = defaults.metadata("app.magnet.real-provider.enabled");
        SystemConfigDefaults.ConfigMetadata magnetAutoSearchHot = defaults.metadata("app.magnet.auto-search.enabled");
        SystemConfigDefaults.ConfigMetadata magnetWorkQueueHot =
                defaults.metadata("app.magnet.work-queue.worker.enabled");
        SystemConfigDefaults.ConfigMetadata adminVideoSearchHot =
                defaults.metadata("app.content.admin-video-search.es-enabled");
        SystemConfigDefaults.ConfigMetadata dashboardSnapshotHot =
                defaults.metadata("app.ops.dashboard.snapshot.refresh-interval");
        SystemConfigDefaults.ConfigMetadata magnetAutoSearchScheduleRestart =
                defaults.metadata("app.magnet.auto-search.fixed-delay-ms");
        SystemConfigDefaults.ConfigMetadata magnetWorkQueueScheduleRestart =
                defaults.metadata("app.magnet.work-queue.worker.fixed-delay-ms");
        SystemConfigDefaults.ConfigMetadata normalizationWatchdogScheduleRestart =
                defaults.metadata("app.normalization.pending-watchdog.fixed-delay-ms");
        SystemConfigDefaults.ConfigMetadata searchWorkQueueScheduleRestart =
                defaults.metadata("app.search.work-queue.worker.fixed-rate-ms");
        SystemConfigDefaults.ConfigMetadata searchAuditScheduleRestart =
                defaults.metadata("app.search.audit-es-replication.worker.fixed-rate-ms");

        assertEquals(SystemConfigDefaults.ActivationMode.HOT, hot.activationMode());
        assertEquals(SystemConfigDefaults.ActivationMode.HOT, taskRuntimeCacheHot.activationMode());
        assertEquals(SystemConfigDefaults.ActivationMode.HOT, runtimeDlqCacheHot.activationMode());
        assertEquals(SystemConfigDefaults.ActivationMode.HOT, serviceRestartHot.activationMode());
        assertEquals(SystemConfigDefaults.ActivationMode.HOT, logRetentionHot.activationMode());
        assertEquals(SystemConfigDefaults.ActivationMode.HOT, notificationWebhookHot.activationMode());
        assertEquals(SystemConfigDefaults.ActivationMode.HOT, rabbitDlqCacheHot.activationMode());
        assertEquals(SystemConfigDefaults.ActivationMode.HOT, magnetRealProviderHot.activationMode());
        assertEquals(SystemConfigDefaults.ActivationMode.HOT, magnetAutoSearchHot.activationMode());
        assertEquals(SystemConfigDefaults.ActivationMode.HOT, magnetWorkQueueHot.activationMode());
        assertEquals(SystemConfigDefaults.ActivationMode.HOT, adminVideoSearchHot.activationMode());
        assertEquals(SystemConfigDefaults.ActivationMode.HOT, dashboardSnapshotHot.activationMode());
        assertEquals(SystemConfigDefaults.ActivationMode.HOT,
                defaults.metadata("app.magnet.work-queue.worker.batch-size").activationMode());
        assertEquals(SystemConfigDefaults.ActivationMode.HOT,
                defaults.metadata("app.magnet.work-queue.worker.visibility-seconds").activationMode());
        assertEquals(SystemConfigDefaults.ActivationMode.HOT,
                defaults.metadata("app.normalization.pending-watchdog.enabled").activationMode());
        assertEquals(SystemConfigDefaults.ActivationMode.HOT,
                defaults.metadata("app.normalization.pending-watchdog.batch-size").activationMode());
        assertEquals(SystemConfigDefaults.ActivationMode.RESTART_REQUIRED, cacheRestart.activationMode());
        assertTrue(cacheRestart.restartServices().contains("ircs-portal-service"));
        assertEquals(SystemConfigDefaults.ActivationMode.RESTART_REQUIRED, taskRestart.activationMode());
        assertTrue(taskRestart.restartServices().contains("ircs-task-service"));
        assertEquals(SystemConfigDefaults.ActivationMode.RESTART_REQUIRED, restart.activationMode());
        assertTrue(restart.restartServices().contains("ircs-identity-service"));
        assertEquals(SystemConfigDefaults.ActivationMode.RESTART_REQUIRED, storageRestart.activationMode());
        assertTrue(storageRestart.restartServices().contains("ircs-storage-service"));
        assertEquals(SystemConfigDefaults.ActivationMode.RESTART_REQUIRED, listenerRestart.activationMode());
        assertTrue(listenerRestart.restartServices().contains("ircs-search-service"));
        assertEquals(SystemConfigDefaults.ActivationMode.RESTART_REQUIRED, logRetentionScheduleRestart.activationMode());
        assertTrue(logRetentionScheduleRestart.restartServices().contains("ircs-search-service"));
        assertEquals(SystemConfigDefaults.ActivationMode.RESTART_REQUIRED,
                magnetAutoSearchScheduleRestart.activationMode());
        assertTrue(magnetAutoSearchScheduleRestart.restartServices().contains("ircs-magnet-service"));
        assertEquals(SystemConfigDefaults.ActivationMode.RESTART_REQUIRED,
                magnetWorkQueueScheduleRestart.activationMode());
        assertTrue(magnetWorkQueueScheduleRestart.restartServices().contains("ircs-magnet-service"));
        assertEquals(SystemConfigDefaults.ActivationMode.RESTART_REQUIRED,
                normalizationWatchdogScheduleRestart.activationMode());
        assertTrue(normalizationWatchdogScheduleRestart.restartServices().contains("ircs-normalization-worker"));
        assertEquals(SystemConfigDefaults.ActivationMode.RESTART_REQUIRED,
                searchWorkQueueScheduleRestart.activationMode());
        assertTrue(searchWorkQueueScheduleRestart.restartServices().contains("ircs-search-service"));
        assertEquals(SystemConfigDefaults.ActivationMode.RESTART_REQUIRED,
                searchAuditScheduleRestart.activationMode());
        assertTrue(searchAuditScheduleRestart.restartServices().contains("ircs-search-service"));
        assertConfigListenerRequiresRestart("app.aggregation.config-listener.enabled", "ircs-aggregation-worker");
        assertConfigListenerRequiresRestart("app.content.config-listener.enabled", "ircs-content-service");
        assertConfigListenerRequiresRestart("app.identity.config-listener.enabled", "ircs-identity-service");
        assertConfigListenerRequiresRestart("app.interaction.config-listener.enabled", "ircs-interaction-service");
        assertConfigListenerRequiresRestart("app.metadata.config-listener.enabled", "ircs-metadata-worker");
        assertConfigListenerRequiresRestart("app.normalization.config-listener.enabled", "ircs-normalization-worker");
        assertConfigListenerRequiresRestart("app.notification.config-listener.enabled", "ircs-notification-worker");
        assertConfigListenerRequiresRestart("app.ops.config-listener.enabled", "ircs-ops-service");
        assertConfigListenerRequiresRestart("app.portal.config-listener.enabled", "ircs-portal-service");
        assertConfigListenerRequiresRestart("app.scraper.config-listener.enabled", "ircs-scraper-service");
        assertConfigListenerRequiresRestart("app.storage.config-listener.enabled", "ircs-storage-service");
    }

    private void assertConfigListenerRequiresRestart(String key, String service) {
        SystemConfigDefaults.ConfigMetadata metadata = defaults.metadata(key);

        assertEquals(SystemConfigDefaults.ActivationMode.RESTART_REQUIRED, metadata.activationMode());
        assertTrue(metadata.restartServices().contains(service), key + " should restart " + service);
    }

    private static final Set<String> V3_OAUTH_CONFIG_KEYS = Set.of(
            "member.oauth.enabled",
            "member.oauth.auto-register.enabled",
            "member.oauth.bind-existing-email.enabled",
            "member.oauth.email-ownership-verification.enabled",
            "member.oauth.redirect-base-url",
            "member.oauth.allowed-providers",
            "member.oauth.github.enabled",
            "member.oauth.github.client-id",
            "member.oauth.github.client-secret",
            "member.oauth.github.scope",
            "member.oauth.github.redirect-uri",
            "member.oauth.google.enabled",
            "member.oauth.google.client-id",
            "member.oauth.google.client-secret",
            "member.oauth.google.scope",
            "member.oauth.google.redirect-uri",
            "member.oauth.x.enabled",
            "member.oauth.x.client-id",
            "member.oauth.x.client-secret",
            "member.oauth.x.scope",
            "member.oauth.x.redirect-uri",
            "member.oauth.gitee.enabled",
            "member.oauth.gitee.client-id",
            "member.oauth.gitee.client-secret",
            "member.oauth.gitee.scope",
            "member.oauth.gitee.redirect-uri",
            "member.oauth.wechat.enabled",
            "member.oauth.wechat.app-id",
            "member.oauth.wechat.app-secret",
            "member.oauth.wechat.scope",
            "member.oauth.wechat.redirect-uri",
            "member.oauth.qq.enabled",
            "member.oauth.qq.app-id",
            "member.oauth.qq.app-key",
            "member.oauth.qq.scope",
            "member.oauth.qq.redirect-uri");

    private static final Set<String> CURRENT_SYSTEM_CONFIG_KEYS = Set.of(
            "normalization.timeout.processing-min",
            "normalization.timeout.pending-min",
            "normalization.max-retries",
            "normalization.backoff.base-seconds",
            "normalization.batch-size",
            "normalization.batch-size.min",
            "normalization.batch-size.max",
            "app.normalization.valkey-worker.batch-size",
            "app.normalization.valkey-worker.visibility-timeout",
            "app.normalization.valkey-worker.initial-delay-ms",
            "app.normalization.valkey-worker.fixed-delay-ms",
            "app.normalization.pending-watchdog.enabled",
            "app.normalization.pending-watchdog.batch-size",
            "app.normalization.pending-watchdog.min-pending-age",
            "app.normalization.pending-watchdog.lease-ttl",
            "app.normalization.pending-watchdog.initial-delay-ms",
            "app.normalization.pending-watchdog.fixed-delay-ms",
            "app.metadata.timeout.processing-min",
            "app.metadata.timeout.pending-min",
            "app.metadata.cron",
            "app.metadata.interval.ms",
            "app.metadata.batch-size",
            "app.metadata.batch-size.min",
            "app.metadata.batch-size.max",
            "app.search.sync.batch-size",
            "app.frontend.url",
            "security.admin.username",
            "security.admin.password",
            "security.jwt.secret",
            "security.jwt.iat-floor",
            "security.ratelimit.enabled",
            "security.ratelimit.rules",
            "member.auth.code.validity-seconds",
            "member.auth.code.rate-limit-seconds",
            "member.register.email-verify.enabled",
            "member.register.time-window.enabled",
            "member.register.time-window.start",
            "member.register.time-window.end",
            "member.register.timezone",
            "member.message.daily-limit",
            "member.message.point-cost",
            "member.media-request.daily-limit",
            "member.media-request.point-cost",
            "app.mail.enabled",
            "app.mail.host",
            "app.mail.port",
            "app.mail.protocol",
            "app.mail.from",
            "app.mail.properties.auth",
            "app.mail.properties.starttls",
            "app.mail.properties.ssl",
            "app.mail.timeout",
            "app.mail.debug",
            "app.mail.rate-limit.min-ms",
            "app.mail.rate-limit.max-ms",
            "app.mail.rate-limit.max-wait-ms",
            "app.notification.webhook.enabled",
            "app.notification.webhook.request-timeout",
            "app.notification.webhook.max-retries",
            "app.notification.webhook.max-payload-bytes",
            "app.notification.webhook.user-agent",
            "app.notification.webhook.allow-private-addresses",
            "app.adaptive.enabled",
            "app.adaptive.load-threshold",
            "app.adaptive.check-interval-ms",
            "app.runtime-config.local-cache.ttl",
            "app.ops.config-listener.enabled",
            "app.aggregation.config-listener.enabled",
            "app.content.config-listener.enabled",
            "app.content.admin-video-search.es-enabled",
            "app.identity.config-listener.enabled",
            "app.interaction.config-listener.enabled",
            "app.metadata.config-listener.enabled",
            "app.normalization.config-listener.enabled",
            "app.notification.config-listener.enabled",
            "app.portal.config-listener.enabled",
            "app.scraper.config-listener.enabled",
            "app.search.config-listener.enabled",
            "app.storage.config-listener.enabled",
            "app.ops.dashboard.stream.interval",
            "app.ops.dashboard.stream.metrics.interval",
            "app.ops.dashboard.stream.task-runtime.interval",
            "app.ops.dashboard.stream.search-ops.interval",
            "app.ops.dashboard.stream.aggregation-ops.interval",
            "app.ops.dashboard.stream.timeout",
            "app.ops.dashboard.snapshot.enabled",
            "app.ops.dashboard.snapshot.refresh-interval",
            "app.ops.dashboard.snapshot.refresh-budget",
            "app.ops.dashboard.snapshot.fresh-ttl",
            "app.ops.dashboard.snapshot.stale-grace",
            "app.ops.dashboard.read-model-snapshot.fresh-ttl",
            "app.ops.dashboard.read-model-snapshot.stale-grace",
            "app.ops.dashboard.snapshot.default-task-runtime-limit",
            "app.ops.metrics.cache-ttl",
            "app.ops.metrics.instant-window",
            "app.ops.metrics.stable-window",
            "app.ops.metrics.consumer-no-progress-grace",
            "app.ops.metrics.ewma-alpha",
            "app.aggregation.work-queue.worker.processing-stall-threshold",
            "app.ops.task-runtime.overview.max-limit",
            "app.ops.task-runtime.overview.cache-ttl",
            "app.ops.runtime-dlq.cache-ttl",
            "app.ops.request-audit.summary-sample-limit",
            "app.ops.worker-job-audit.summary-sample-limit",
            "app.ops.rabbit-management.enabled",
            "app.ops.rabbit-management.base-url",
            "app.ops.rabbit-management.vhost",
            "app.ops.rabbit-management.connect-timeout",
            "app.ops.rabbit-management.request-timeout",
            "app.ops.rabbit-management.initial-delay-ms",
            "app.ops.rabbit-management.fixed-delay-ms",
            "app.ops.rabbit-dlq.sampled-queue-limit",
            "app.ops.rabbit-dlq.queue-snapshot-cache-ttl",
            "app.ops.rabbit-dlq.sample-cache-ttl",
            "app.ops.service-restart.enabled",
            "app.ops.service-restart.kubernetes-api-base-url",
            "app.ops.service-restart.namespace",
            "app.ops.service-restart.allowed-services",
            "app.ops.service-restart.request-timeout",
            "app.ops-alert.first-page-cache.warmup.enabled",
            "app.ops-alert.first-page-cache.warmup.refresh-interval",
            "app.ops-alert.first-page-cache.warmup.page-size",
            "app.rate-metrics.key-prefix",
            "app.rate-metrics.bucket-size",
            "app.rate-metrics.bucket-ttl",
            "app.rate-metrics.bucket-index-retention",
            "app.log-retention.enabled",
            "app.log-retention.default-retention",
            "app.log-retention.initial-delay-ms",
            "app.log-retention.fixed-delay-ms",
            "app.log-retention.target.audit-es.enabled",
            "app.log-retention.target.audit-es.retention",
            "app.portal.cache.enabled",
            "app.portal.cache.metadata-ttl",
            "app.portal.cache.home-ttl",
            "app.portal.cache.explore-ttl",
            "app.portal.cache.detail-ttl",
            "app.portal.cache.dictionary-ttl",
            "app.search.cache.enabled",
            "app.search.cache.portal-suggest-ttl",
            "app.search.cache.portal-recommend-ttl",
            "app.search.cache.portal-public-version-key",
            "app.search.cache.portal-version-refresh-interval",
            "app.catalog.cache.enabled",
            "app.catalog.cache.standard-dictionary-ttl",
            "app.credential.cache.enabled",
            "app.credential.cache.summary-ttl",
            "app.credential.lease-cache.ttl",
            "app.magnet.cache.enabled",
            "app.magnet.cache.provider-ttl",
            "app.magnet.cache.approved-links-ttl",
            "app.magnet.real-provider.enabled",
            "app.magnet.real-provider.allowlist",
            "app.magnet.auto-search.enabled",
            "app.magnet.auto-search.batch-size",
            "app.magnet.auto-search.cooldown",
            "app.magnet.auto-search.initial-delay-ms",
            "app.magnet.auto-search.fixed-delay-ms",
            "app.magnet.work-queue.submission.enabled",
            "app.magnet.work-queue.worker.enabled",
            "app.magnet.work-queue.worker.batch-size",
            "app.magnet.work-queue.worker.visibility-seconds",
            "app.magnet.work-queue.worker.max-retries",
            "app.magnet.work-queue.worker.max-backoff-seconds",
            "app.magnet.work-queue.worker.initial-delay-ms",
            "app.magnet.work-queue.worker.fixed-delay-ms",
            "app.magnet.work-queue.worker.heartbeat-initial-delay-ms",
            "app.magnet.work-queue.worker.heartbeat-fixed-delay-ms",
            "app.task.cluster-lock.enabled",
            "app.task.cluster-lock.ttl",
            "app.task.cluster-lock.worker-id",
            "app.task.default-seed.enabled",
            "app.task.internal-access.require-token",
            "app.task.internal-access.token",
            "app.task.internal-access.required-scope",
            "app.task.queue.enabled",
            "app.task.queue.max-pages-per-run",
            "app.task.queue.retry.max-retries",
            "app.task.queue.dispatch.concurrency",
            "app.task.queue.dispatch.queue-capacity",
            "app.task.queue.dispatch.await-termination-seconds",
            "app.task.runner.concurrency",
            "app.task.runner.queue-capacity",
            "app.task.runner.await-termination-seconds",
            "app.task.runner.scraper-base-url",
            "app.task.runner.scraper-request-timeout",
            "app.task.snapshot.flush.min-dirty-age",
            "app.task.snapshot.flush.batch-size",
            "app.task.snapshot.ttl",
            "app.task.runtime.event-stream-maxlen",
            "app.task.runtime.repair.batch-size",
            "app.task.trend-discovery.enabled",
            "app.task.trend-discovery.max-keywords",
            "app.task.trend-discovery.max-data-sources",
            "app.task.media-request.enabled",
            "app.task.media-request.initial-delay-ms",
            "app.task.media-request.fixed-delay-ms",
            "app.task.media-request.batch-size",
            "app.task.media-request.start-page",
            "app.task.media-request.end-page",
            "app.task.media-request.request-fixed-delay-ms",
            "app.task.media-request.max-data-sources",
            "app.task.scheduler.enabled",
            "app.aggregation.work-queue.batch-size",
            "app.aggregation.work-queue.batch-size.min",
            "app.aggregation.work-queue.batch-size.max",
            "app.aggregation.work-queue.worker.batch-size",
            "app.aggregation.work-queue.worker.visibility-seconds",
            "app.aggregation.work-queue.worker.max-retries",
            "app.aggregation.work-queue.worker.max-backoff-seconds",
            "app.aggregation.work-queue.worker.initial-delay-ms",
            "app.aggregation.work-queue.worker.fixed-delay-ms",
            "app.maintenance.sanitize.batch-size",
            "app.maintenance.aggregation-reset.batch-size",
            "app.maintenance.rule-cleaning.batch-size",
            "app.maintenance.search.reindex-batch-size",
            "app.maintenance.batch-size.min",
            "app.maintenance.batch-size.max",
            "app.maintenance.playlist-repair.enabled",
            "app.maintenance.playlist-repair.batch-size",
            "app.maintenance.playlist-repair.batch-size.min",
            "app.maintenance.playlist-repair.batch-size.max",
            "app.search.work-queue.batch-size",
            "app.search.work-queue.batch-size.min",
            "app.search.work-queue.batch-size.max",
            "app.search.work-queue.worker.enabled",
            "app.search.work-queue.worker.batch-size",
            "app.search.work-queue.worker.visibility-seconds",
            "app.search.work-queue.worker.max-retries",
            "app.search.work-queue.worker.max-backoff-seconds",
            "app.search.work-queue.worker.initial-delay-ms",
            "app.search.work-queue.worker.fixed-rate-ms",
            "app.audit.es-replication.enabled",
            "app.search.audit-es-replication.worker.enabled",
            "app.search.audit-es-replication.worker.batch-size",
            "app.search.audit-es-replication.worker.visibility-seconds",
            "app.search.audit-es-replication.worker.max-retries",
            "app.search.audit-es-replication.worker.max-backoff-seconds",
            "app.search.audit-es-replication.worker.initial-delay-ms",
            "app.search.audit-es-replication.worker.fixed-rate-ms",
            "app.search.audit-es-replication.worker.heartbeat-fixed-rate-ms",
            "app.search.reconciliation.enabled",
            "app.storage.base-path",
            "app.storage.public-path",
            "app.storage.path.prefix.cover",
            "app.storage.path.prefix.avatar",
            "app.maintenance.queue-retention-days",
            "app.storage.image.download.enabled",
            "app.storage.image.max-retries",
            "app.storage.image.timeout-min",
            "app.storage.image.download.batch-size",
            "app.storage.image.download.batch-size.min",
            "app.storage.image.download.batch-size.max",
            "app.storage.image.download-backfill.enabled",
            "app.storage.image.download-backfill.batch-size",
            "app.storage.image.download-backfill.initial-delay-ms",
            "app.storage.image.download-backfill.fixed-delay-ms",
            "app.storage.image.cleanup.batch-size",
            "app.storage.image.cleanup.batch-size.min",
            "app.storage.image.cleanup.batch-size.max",
            "app.storage.r2.work-queue.worker.enabled",
            "app.storage.r2.work-queue.worker.batch-size",
            "app.storage.r2.work-queue.worker.visibility-seconds",
            "app.storage.r2.work-queue.worker.max-retries",
            "app.storage.r2.work-queue.worker.max-backoff-seconds",
            "app.storage.r2.work-queue.worker.initial-delay-ms",
            "app.storage.r2.work-queue.worker.fixed-delay-ms",
            "app.storage.r2.enabled",
            "app.storage.r2.bucket-name",
            "app.storage.r2.public-domain",
            "app.ai.llm.enabled",
            "app.ai.llm.model",
            "app.ai.llm.rate-limit.rpm",
            "app.normalization.llm-cleaning.work-queue.worker.enabled",
            "app.normalization.llm-cleaning.work-queue.worker.batch-size",
            "app.normalization.llm-cleaning.work-queue.worker.visibility-seconds",
            "app.normalization.llm-cleaning.work-queue.worker.max-retries",
            "app.normalization.llm-cleaning.work-queue.worker.max-backoff-seconds",
            "app.normalization.llm-cleaning.work-queue.worker.initial-delay-ms",
            "app.normalization.llm-cleaning.work-queue.worker.fixed-delay-ms",
            "app.ai.llm.prompt.language",
            "app.ai.llm.prompt.area",
            "app.ai.llm.prompt.genre",
            "app.ai.llm.prompt.category",
            "global.traffic.safety-floor-ms",
            "global.traffic.max-wait-ms",
            "app.identity.auth-rate-limit.enabled",
            "app.identity.auth-rate-limit.max-attempts",
            "app.identity.auth-rate-limit.window",
            "app.identity.pow.enabled",
            "app.scraper.traffic.source-enabled",
            "app.scraper.traffic.enabled",
            "app.scraper.traffic.egress-id",
            "app.scraper.traffic.default-gap-ms",
            "app.scraper.traffic.max-wait",
            "app.scraper.traffic.ttl",
            "app.metadata.public-traffic.enabled",
            "app.metadata.public-traffic.default-gap-ms",
            "app.metadata.public-traffic.max-wait",
            "app.metadata.public-traffic.ttl",
            "app.metadata.tmdb.enabled",
            "app.metadata.tmdb.min-delay-ms",
            "app.metadata.tmdb.max-delay-ms",
            "app.metadata.tmdb.proxy.enabled",
            "app.metadata.tmdb.proxy.type",
            "app.metadata.tmdb.proxy.host",
            "app.metadata.tmdb.proxy.port",
            "app.metadata.tmdb.proxy.username",
            "app.metadata.tmdb.proxy.password",
            "app.metadata.douban.enabled",
            "app.metadata.douban.min-delay-ms",
            "app.metadata.douban.max-delay-ms",
            "app.metadata.douban.proxy.enabled",
            "app.metadata.douban.proxy.type",
            "app.metadata.douban.proxy.host",
            "app.metadata.douban.proxy.port",
            "app.metadata.douban.proxy.username",
            "app.metadata.douban.proxy.password",
            "app.metadata.rotten-tomatoes.enabled",
            "app.metadata.rotten-tomatoes.min-delay-ms",
            "app.metadata.rotten-tomatoes.max-delay-ms",
            "app.metadata.rotten-tomatoes.proxy.enabled",
            "app.metadata.rotten-tomatoes.proxy.type",
            "app.metadata.rotten-tomatoes.proxy.host",
            "app.metadata.rotten-tomatoes.proxy.port",
            "app.metadata.rotten-tomatoes.proxy.username",
            "app.metadata.rotten-tomatoes.proxy.password",
            "app.magnet.traffic.enabled",
            "app.magnet.traffic.egress-id",
            "app.magnet.traffic.default-gap-ms",
            "app.magnet.traffic.provider-gap-ms",
            "app.magnet.traffic.max-wait",
            "app.magnet.traffic.ttl",
            "app.storage.image.traffic.enabled",
            "app.storage.image.traffic.egress-id",
            "app.storage.image.traffic.global-gap-ms",
            "app.storage.image.traffic.domain-gap-ms",
            "app.storage.image.traffic.max-wait",
            "app.storage.image.traffic.ttl",
            "app.metadata.valkey-dispatcher.batch-size",
            "app.metadata.valkey-dispatcher.visibility-timeout",
            "app.metadata.valkey-dispatcher.retry-delay",
            "app.metadata.valkey-dispatcher.initial-delay-ms",
            "app.metadata.valkey-dispatcher.fixed-delay-ms",
            "app.metadata.valkey-provider-worker.batch-size",
            "app.metadata.valkey-provider-worker.parallelism",
            "app.metadata.valkey-provider-worker.visibility-timeout",
            "app.metadata.valkey-provider-worker.retry-delay",
            "app.metadata.valkey-provider-worker.initial-delay-ms",
            "app.metadata.valkey-provider-worker.fixed-delay-ms",
            "app.scraper.trend-sync.enabled",
            "app.scraper.trend-sync.cron",
            "app.scraper.trend-sync.timezone",
            "app.search.sync.enabled",
            "app.search.sync.cron");
}
