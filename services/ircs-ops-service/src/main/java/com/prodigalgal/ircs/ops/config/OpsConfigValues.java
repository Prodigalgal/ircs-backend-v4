package com.prodigalgal.ircs.ops.config;

import com.prodigalgal.ircs.ops.maintenance.application.MaintenanceReindexCommand;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class OpsConfigValues {

    static final String REINDEX_DEV_LIMIT_KEY = "app.ops.maintenance.reindex.dev-limit";
    static final String REINDEX_BATCH_SIZE_KEY = "app.maintenance.search.reindex-batch-size";
    static final String SCHEDULER_ENABLED_KEY = "app.ops.maintenance.scheduler.enabled";
    static final String SCHEDULER_DRY_RUN_KEY = "app.ops.maintenance.scheduler.dry-run";
    static final String SCHEDULER_EXECUTE_ENABLED_KEY = "app.ops.maintenance.scheduler.execute-enabled";
    static final String SCHEDULER_TASKS_KEY = "app.ops.maintenance.scheduler.tasks";
    static final String SCHEDULER_CLUSTER_LEASE_ENABLED_KEY =
            "app.ops.maintenance.scheduler.cluster-lease.enabled";
    static final String SCHEDULER_CLUSTER_LEASE_TTL_KEY = "app.ops.maintenance.scheduler.cluster-lease.ttl";
    static final String GATE_DEFAULT_TTL_KEY = "app.ops.maintenance.gate.default-ttl";
    static final String GATE_MAX_TTL_KEY = "app.ops.maintenance.gate.max-ttl";
    static final String SEARCH_OWNER_BASE_URL_KEY = "app.ops.maintenance.search.owner-base-url";
    static final String SEARCH_OWNER_REQUEST_TIMEOUT_KEY = "app.ops.maintenance.search.request-timeout";
    static final String SEARCH_OWNER_SERVICE_ID_KEY = "app.ops.maintenance.search.service-id";
    static final String SEARCH_OWNER_SERVICE_TOKEN_KEY = "app.ops.maintenance.search.service-token";
    static final String SEARCH_OWNER_SCOPES_KEY = "app.ops.maintenance.search.scopes";
    static final String NORMALIZATION_OWNER_BASE_URL_KEY = "app.ops.maintenance.normalization.owner-base-url";
    static final String NORMALIZATION_DEV_LIMIT_KEY = "app.ops.maintenance.normalization.dev-limit";
    static final String NORMALIZATION_BATCH_SIZE_KEY = "app.maintenance.sanitize.batch-size";
    static final String NORMALIZATION_OWNER_REQUEST_TIMEOUT_KEY = "app.ops.maintenance.normalization.request-timeout";
    static final String NORMALIZATION_LLM_OWNER_REQUEST_TIMEOUT_KEY =
            "app.ops.maintenance.normalization.llm-request-timeout";
    static final String NORMALIZATION_OWNER_SERVICE_ID_KEY = "app.ops.maintenance.normalization.service-id";
    static final String NORMALIZATION_OWNER_SERVICE_TOKEN_KEY = "app.ops.maintenance.normalization.service-token";
    static final String NORMALIZATION_OWNER_SCOPES_KEY = "app.ops.maintenance.normalization.scopes";
    static final String AGGREGATION_DEV_LIMIT_KEY = "app.ops.maintenance.aggregation.dev-limit";
    static final String AGGREGATION_OWNER_BASE_URL_KEY = "app.ops.maintenance.aggregation.owner-base-url";
    static final String AGGREGATION_OWNER_REQUEST_TIMEOUT_KEY = "app.ops.maintenance.aggregation.request-timeout";
    static final String AGGREGATION_STATS_REQUEST_TIMEOUT_KEY =
            "app.ops.maintenance.aggregation.stats-request-timeout";
    static final String AGGREGATION_OWNER_SERVICE_ID_KEY = "app.ops.maintenance.aggregation.service-id";
    static final String AGGREGATION_OWNER_SERVICE_TOKEN_KEY = "app.ops.maintenance.aggregation.service-token";
    static final String AGGREGATION_OWNER_SCOPES_KEY = "app.ops.maintenance.aggregation.scopes";
    static final String SCRAPER_OWNER_BASE_URL_KEY = "app.ops.maintenance.scraper.owner-base-url";
    static final String SCRAPER_OWNER_REQUEST_TIMEOUT_KEY = "app.ops.maintenance.scraper.request-timeout";
    static final String SCRAPER_OWNER_SERVICE_ID_KEY = "app.ops.maintenance.scraper.service-id";
    static final String SCRAPER_OWNER_SERVICE_TOKEN_KEY = "app.ops.maintenance.scraper.service-token";
    static final String SCRAPER_OWNER_SCOPES_KEY = "app.ops.maintenance.scraper.scopes";
    public static final int DEFAULT_REINDEX_DEV_LIMIT = 5;
    public static final int MAX_REINDEX_DEV_LIMIT = 100;
    public static final int DEFAULT_REINDEX_BATCH_SIZE = 500;
    public static final int MAX_REINDEX_BATCH_SIZE = 1000;
    public static final int DEFAULT_NORMALIZATION_DEV_LIMIT = 5;
    public static final int MAX_NORMALIZATION_DEV_LIMIT = 100;
    public static final int DEFAULT_NORMALIZATION_BATCH_SIZE = 1000;
    public static final int MAX_NORMALIZATION_BATCH_SIZE = 2000;
    public static final int DEFAULT_AGGREGATION_DEV_LIMIT = 5;
    public static final int MAX_AGGREGATION_DEV_LIMIT = 100;
    private static final Duration DEFAULT_SEARCH_OWNER_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_NORMALIZATION_OWNER_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_NORMALIZATION_LLM_OWNER_REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration DEFAULT_AGGREGATION_OWNER_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_AGGREGATION_STATS_REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_SCRAPER_OWNER_REQUEST_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration DEFAULT_GATE_DEFAULT_TTL = Duration.ofMinutes(15);
    private static final Duration DEFAULT_GATE_MAX_TTL = Duration.ofHours(6);
    private static final Duration DEFAULT_SCHEDULER_CLUSTER_LEASE_TTL = Duration.ofMinutes(10);
    private static final String DEFAULT_SEARCH_OWNER_BASE_URL =
            "http://ircs-search-service.ircs-dev.svc.cluster.local:8080";
    private static final String DEFAULT_NORMALIZATION_OWNER_BASE_URL =
            "http://ircs-normalization-worker.ircs-dev.svc.cluster.local:8080";
    private static final String DEFAULT_AGGREGATION_OWNER_BASE_URL =
            "http://ircs-aggregation-worker.ircs-dev.svc.cluster.local:8080";
    private static final String DEFAULT_SCRAPER_OWNER_BASE_URL =
            "http://ircs-scraper-service.ircs-dev.svc.cluster.local:8080";

    private final Environment environment;
    private final SystemConfigRepository repository;

    OpsConfigValues(Environment environment, SystemConfigRepository repository) {
        this.environment = environment;
        this.repository = repository;
    }

    public int reindexDevLimit() {
        return intValue(
                DEFAULT_REINDEX_DEV_LIMIT,
                0,
                MAX_REINDEX_DEV_LIMIT,
                REINDEX_DEV_LIMIT_KEY,
                "OPS_MAINTENANCE_REINDEX_DEV_LIMIT",
                "APP_OPS_MAINTENANCE_REINDEX_DEV_LIMIT");
    }

    public int reindexBatchSize() {
        return intValue(
                DEFAULT_REINDEX_BATCH_SIZE,
                1,
                MAX_REINDEX_BATCH_SIZE,
                REINDEX_BATCH_SIZE_KEY,
                "APP_MAINTENANCE_SEARCH_REINDEX_BATCH_SIZE",
                "APP_OPS_MAINTENANCE_REINDEX_BATCH_SIZE");
    }

    public int aggregationMaintenanceDevLimit() {
        return intValue(
                DEFAULT_AGGREGATION_DEV_LIMIT,
                0,
                MAX_AGGREGATION_DEV_LIMIT,
                AGGREGATION_DEV_LIMIT_KEY,
                "APP_OPS_MAINTENANCE_AGGREGATION_DEV_LIMIT");
    }

    public boolean maintenanceSchedulerEnabled() {
        return booleanValue(
                false,
                SCHEDULER_ENABLED_KEY,
                "APP_OPS_MAINTENANCE_SCHEDULER_ENABLED");
    }

    public boolean maintenanceSchedulerDryRun() {
        return booleanValue(
                true,
                SCHEDULER_DRY_RUN_KEY,
                "APP_OPS_MAINTENANCE_SCHEDULER_DRY_RUN");
    }

    public boolean maintenanceSchedulerExecuteEnabled() {
        return booleanValue(
                true,
                SCHEDULER_EXECUTE_ENABLED_KEY,
                "APP_OPS_MAINTENANCE_SCHEDULER_EXECUTE_ENABLED");
    }

    public List<String> maintenanceSchedulerTasks() {
        return listValue(
                List.of(MaintenanceReindexCommand.TASK_UNIFIED_REINDEX),
                SCHEDULER_TASKS_KEY,
                "APP_OPS_MAINTENANCE_SCHEDULER_TASKS");
    }

    public boolean maintenanceSchedulerClusterLeaseEnabled() {
        return booleanValue(
                true,
                SCHEDULER_CLUSTER_LEASE_ENABLED_KEY,
                "APP_OPS_MAINTENANCE_SCHEDULER_CLUSTER_LEASE_ENABLED");
    }

    public Duration maintenanceSchedulerClusterLeaseTtl() {
        Duration value = durationValue(
                DEFAULT_SCHEDULER_CLUSTER_LEASE_TTL,
                SCHEDULER_CLUSTER_LEASE_TTL_KEY,
                "APP_OPS_MAINTENANCE_SCHEDULER_CLUSTER_LEASE_TTL");
        return value.isZero() || value.isNegative() ? DEFAULT_SCHEDULER_CLUSTER_LEASE_TTL : value;
    }

    public Duration maintenanceGateDefaultTtl() {
        Duration value = durationValue(
                DEFAULT_GATE_DEFAULT_TTL,
                GATE_DEFAULT_TTL_KEY,
                "APP_OPS_MAINTENANCE_GATE_DEFAULT_TTL");
        return value.isZero() || value.isNegative() ? DEFAULT_GATE_DEFAULT_TTL : value;
    }

    public Duration maintenanceGateMaxTtl() {
        Duration value = durationValue(
                DEFAULT_GATE_MAX_TTL,
                GATE_MAX_TTL_KEY,
                "APP_OPS_MAINTENANCE_GATE_MAX_TTL");
        return value.compareTo(maintenanceGateDefaultTtl()) < 0 ? maintenanceGateDefaultTtl() : value;
    }

    public String searchOwnerBaseUrl() {
        return stringValue(
                DEFAULT_SEARCH_OWNER_BASE_URL,
                SEARCH_OWNER_BASE_URL_KEY,
                "APP_OPS_SEARCH_SERVICE_BASE_URL");
    }

    public Duration searchOwnerRequestTimeout() {
        return durationValue(
                DEFAULT_SEARCH_OWNER_REQUEST_TIMEOUT,
                SEARCH_OWNER_REQUEST_TIMEOUT_KEY,
                "APP_OPS_SEARCH_SERVICE_REQUEST_TIMEOUT");
    }

    public String searchOwnerServiceId() {
        return stringValue("ops-service", SEARCH_OWNER_SERVICE_ID_KEY, "APP_OPS_SERVICE_ID");
    }

    public String searchOwnerServiceToken() {
        return stringValue("", SEARCH_OWNER_SERVICE_TOKEN_KEY, "APP_OPS_SEARCH_SERVICE_TOKEN");
    }

    public String searchOwnerScopes() {
        return stringValue("search:sync", SEARCH_OWNER_SCOPES_KEY, "APP_OPS_SEARCH_SERVICE_SCOPES");
    }

    public String normalizationOwnerBaseUrl() {
        return stringValue(
                DEFAULT_NORMALIZATION_OWNER_BASE_URL,
                NORMALIZATION_OWNER_BASE_URL_KEY,
                "APP_OPS_NORMALIZATION_WORKER_BASE_URL");
    }

    public Duration normalizationOwnerRequestTimeout() {
        return durationValue(
                DEFAULT_NORMALIZATION_OWNER_REQUEST_TIMEOUT,
                NORMALIZATION_OWNER_REQUEST_TIMEOUT_KEY,
                "APP_OPS_NORMALIZATION_WORKER_REQUEST_TIMEOUT");
    }

    public Duration normalizationLlmOwnerRequestTimeout() {
        return durationValue(
                DEFAULT_NORMALIZATION_LLM_OWNER_REQUEST_TIMEOUT,
                NORMALIZATION_LLM_OWNER_REQUEST_TIMEOUT_KEY,
                "APP_OPS_NORMALIZATION_LLM_REQUEST_TIMEOUT");
    }

    public int normalizationMaintenanceDevLimit() {
        return intValue(
                DEFAULT_NORMALIZATION_DEV_LIMIT,
                0,
                MAX_NORMALIZATION_DEV_LIMIT,
                NORMALIZATION_DEV_LIMIT_KEY,
                "APP_OPS_MAINTENANCE_NORMALIZATION_DEV_LIMIT");
    }

    public int normalizationMaintenanceBatchSize() {
        return intValue(
                DEFAULT_NORMALIZATION_BATCH_SIZE,
                1,
                MAX_NORMALIZATION_BATCH_SIZE,
                NORMALIZATION_BATCH_SIZE_KEY,
                "APP_MAINTENANCE_SANITIZE_BATCH_SIZE",
                "APP_OPS_MAINTENANCE_NORMALIZATION_BATCH_SIZE");
    }

    public String normalizationOwnerServiceId() {
        return stringValue("ops-service", NORMALIZATION_OWNER_SERVICE_ID_KEY, "APP_OPS_SERVICE_ID");
    }

    public String normalizationOwnerServiceToken() {
        return stringValue(
                "",
                NORMALIZATION_OWNER_SERVICE_TOKEN_KEY,
                "APP_OPS_NORMALIZATION_SERVICE_TOKEN",
                "APP_NORMALIZATION_SERVICE_TOKEN");
    }

    public String normalizationOwnerScopes() {
        return stringValue(
                "normalization:maintenance",
                NORMALIZATION_OWNER_SCOPES_KEY,
                "APP_OPS_NORMALIZATION_WORKER_SCOPES");
    }

    public String aggregationOwnerBaseUrl() {
        return stringValue(
                DEFAULT_AGGREGATION_OWNER_BASE_URL,
                AGGREGATION_OWNER_BASE_URL_KEY,
                "APP_OPS_AGGREGATION_WORKER_BASE_URL");
    }

    public Duration aggregationOwnerRequestTimeout() {
        return durationValue(
                DEFAULT_AGGREGATION_OWNER_REQUEST_TIMEOUT,
                AGGREGATION_OWNER_REQUEST_TIMEOUT_KEY,
                "APP_OPS_AGGREGATION_WORKER_REQUEST_TIMEOUT");
    }

    public Duration aggregationStatsRequestTimeout() {
        return durationValue(
                DEFAULT_AGGREGATION_STATS_REQUEST_TIMEOUT,
                AGGREGATION_STATS_REQUEST_TIMEOUT_KEY,
                "APP_OPS_AGGREGATION_STATS_REQUEST_TIMEOUT");
    }

    public String aggregationOwnerServiceId() {
        return stringValue("ops-service", AGGREGATION_OWNER_SERVICE_ID_KEY, "APP_OPS_SERVICE_ID");
    }

    public String aggregationOwnerServiceToken() {
        return stringValue(
                "",
                AGGREGATION_OWNER_SERVICE_TOKEN_KEY,
                "APP_OPS_AGGREGATION_SERVICE_TOKEN",
                "APP_AGGREGATION_SERVICE_TOKEN");
    }

    public String aggregationOwnerScopes() {
        return stringValue(
                "aggregation:maintenance",
                AGGREGATION_OWNER_SCOPES_KEY,
                "APP_OPS_AGGREGATION_WORKER_SCOPES");
    }

    public String scraperOwnerBaseUrl() {
        return stringValue(
                DEFAULT_SCRAPER_OWNER_BASE_URL,
                SCRAPER_OWNER_BASE_URL_KEY,
                "APP_OPS_SCRAPER_SERVICE_BASE_URL");
    }

    public Duration scraperOwnerRequestTimeout() {
        return durationValue(
                DEFAULT_SCRAPER_OWNER_REQUEST_TIMEOUT,
                SCRAPER_OWNER_REQUEST_TIMEOUT_KEY,
                "APP_OPS_SCRAPER_SERVICE_REQUEST_TIMEOUT");
    }

    public String scraperOwnerServiceId() {
        return stringValue("ops-service", SCRAPER_OWNER_SERVICE_ID_KEY, "APP_OPS_SERVICE_ID");
    }

    public String scraperOwnerServiceToken() {
        return stringValue(
                "",
                SCRAPER_OWNER_SERVICE_TOKEN_KEY,
                "APP_OPS_SCRAPER_SERVICE_TOKEN",
                "APP_SCRAPER_SERVICE_TOKEN");
    }

    public String scraperOwnerScopes() {
        return stringValue(
                "scraper:maintenance",
                SCRAPER_OWNER_SCOPES_KEY,
                "APP_OPS_SCRAPER_SERVICE_SCOPES");
    }

    private boolean booleanValue(boolean defaultValue, String key, String... aliases) {
        String raw = value(key, aliases);
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "yes", "y", "on", "enabled" -> true;
            case "false", "0", "no", "n", "off", "disabled" -> false;
            default -> defaultValue;
        };
    }

    private int intValue(int defaultValue, int min, int max, String key, String... aliases) {
        String raw = value(key, aliases);
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(parsed, max));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private List<String> listValue(List<String> defaultValue, String key, String... aliases) {
        String raw = value(key, aliases);
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        List<String> values = Arrays.stream(StringUtils.commaDelimitedListToStringArray(raw))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        return values.isEmpty() ? defaultValue : values;
    }

    private Duration durationValue(Duration defaultValue, String key, String... aliases) {
        String raw = value(key, aliases);
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        try {
            return DurationStyle.detectAndParse(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return defaultValue;
        }
    }

    private String stringValue(String defaultValue, String key, String... aliases) {
        String raw = value(key, aliases);
        return StringUtils.hasText(raw) ? raw.trim() : defaultValue;
    }

    private String value(String key, String... aliases) {
        String[] runtimeKeys = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(key),
                        Arrays.stream(aliases))
                .toArray(String[]::new);
        return RuntimeInjectedConfig.find(environment, runtimeKeys)
                .or(() -> repository.findValue(key))
                .orElse(null);
    }
}
