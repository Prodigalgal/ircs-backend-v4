package com.prodigalgal.ircs.ops.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OpsConfigValuesTest {

    private final SystemConfigRepository repository = org.mockito.Mockito.mock(SystemConfigRepository.class);

    @Test
    void usesDevSafeFallbackWhenNoRuntimeOrDbValueExists() {
        OpsConfigValues values = values(new MockEnvironment());

        assertEquals(5, values.reindexDevLimit());
        assertEquals(500, values.reindexBatchSize());
        assertEquals(1000, values.normalizationMaintenanceBatchSize());
    }

    @Test
    void readsDbCanonicalKeyWhenRuntimeIsAbsent() {
        when(repository.findValue(OpsConfigValues.REINDEX_DEV_LIMIT_KEY)).thenReturn(Optional.of("12"));

        OpsConfigValues values = values(new MockEnvironment());

        assertEquals(12, values.reindexDevLimit());
    }

    @Test
    void runtimeAliasOverridesDbAndClampsAtMaxLimit() {
        when(repository.findValue(OpsConfigValues.REINDEX_DEV_LIMIT_KEY)).thenReturn(Optional.of("12"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("OPS_MAINTENANCE_REINDEX_DEV_LIMIT", "1000");

        OpsConfigValues values = values(environment);

        assertEquals(100, values.reindexDevLimit());
    }

    @Test
    void negativeRuntimeValueIsClampedToZero() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_OPS_MAINTENANCE_REINDEX_DEV_LIMIT", "-10");

        OpsConfigValues values = values(environment);

        assertEquals(0, values.reindexDevLimit());
    }

    @Test
    void invalidValueFallsBackSafely() {
        when(repository.findValue(OpsConfigValues.REINDEX_DEV_LIMIT_KEY)).thenReturn(Optional.of("bad"));

        OpsConfigValues values = values(new MockEnvironment());

        assertEquals(5, values.reindexDevLimit());
    }

    @Test
    void batchSizesReadRuntimeValuesAndClampAtMax() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_MAINTENANCE_SEARCH_REINDEX_BATCH_SIZE", "9999")
                .withProperty("APP_MAINTENANCE_SANITIZE_BATCH_SIZE", "750");

        OpsConfigValues values = values(environment);

        assertEquals(1000, values.reindexBatchSize());
        assertEquals(750, values.normalizationMaintenanceBatchSize());
    }

    @Test
    void schedulerDefaultsAreDevSafeAndExecutableOnlyWhenExplicitlyEnabled() {
        OpsConfigValues values = values(new MockEnvironment());

        assertEquals(false, values.maintenanceSchedulerEnabled());
        assertEquals(true, values.maintenanceSchedulerDryRun());
        assertEquals(true, values.maintenanceSchedulerExecuteEnabled());
        assertEquals(List.of("search-reindex-unified"), values.maintenanceSchedulerTasks());
    }

    @Test
    void schedulerRuntimeValuesOverrideDbValues() {
        when(repository.findValue(OpsConfigValues.SCHEDULER_ENABLED_KEY)).thenReturn(Optional.of("false"));
        when(repository.findValue(OpsConfigValues.SCHEDULER_DRY_RUN_KEY)).thenReturn(Optional.of("true"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_OPS_MAINTENANCE_SCHEDULER_ENABLED", "true")
                .withProperty("APP_OPS_MAINTENANCE_SCHEDULER_DRY_RUN", "false")
                .withProperty("APP_OPS_MAINTENANCE_SCHEDULER_TASKS", " search-reindex-unified, retention-purge ");

        OpsConfigValues values = values(environment);

        assertEquals(true, values.maintenanceSchedulerEnabled());
        assertEquals(false, values.maintenanceSchedulerDryRun());
        assertEquals(List.of("search-reindex-unified", "retention-purge"), values.maintenanceSchedulerTasks());
    }

    @Test
    void maintenanceGateTtlDefaultsAndRuntimeOverridesAreBounded() {
        OpsConfigValues defaults = values(new MockEnvironment());

        assertEquals(Duration.ofMinutes(15), defaults.maintenanceGateDefaultTtl());
        assertEquals(Duration.ofHours(6), defaults.maintenanceGateMaxTtl());

        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_OPS_MAINTENANCE_GATE_DEFAULT_TTL", "30s")
                .withProperty("APP_OPS_MAINTENANCE_GATE_MAX_TTL", "10s");

        OpsConfigValues values = values(environment);

        assertEquals(Duration.ofSeconds(30), values.maintenanceGateDefaultTtl());
        assertEquals(Duration.ofSeconds(30), values.maintenanceGateMaxTtl());
    }

    @Test
    void searchOwnerConfigReadsRuntimeValues() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_OPS_SEARCH_SERVICE_BASE_URL", "http://search-service:8080")
                .withProperty("APP_OPS_SEARCH_SERVICE_REQUEST_TIMEOUT", "250ms")
                .withProperty("APP_OPS_SERVICE_ID", "ops-dev")
                .withProperty("APP_OPS_SEARCH_SERVICE_TOKEN", "token")
                .withProperty("APP_OPS_SEARCH_SERVICE_SCOPES", "search:sync search:ops");

        OpsConfigValues values = values(environment);

        assertEquals("http://search-service:8080", values.searchOwnerBaseUrl());
        assertEquals(Duration.ofMillis(250), values.searchOwnerRequestTimeout());
        assertEquals("ops-dev", values.searchOwnerServiceId());
        assertEquals("token", values.searchOwnerServiceToken());
        assertEquals("search:sync search:ops", values.searchOwnerScopes());
    }

    @Test
    void normalizationOwnerConfigReadsRuntimeValues() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_OPS_MAINTENANCE_NORMALIZATION_DEV_LIMIT", "8")
                .withProperty("APP_MAINTENANCE_SANITIZE_BATCH_SIZE", "250")
                .withProperty("APP_OPS_NORMALIZATION_WORKER_BASE_URL", "http://normalization-worker:8080")
                .withProperty("APP_OPS_NORMALIZATION_WORKER_REQUEST_TIMEOUT", "750ms")
                .withProperty("APP_OPS_NORMALIZATION_LLM_REQUEST_TIMEOUT", "5m")
                .withProperty("APP_OPS_SERVICE_ID", "ops-dev")
                .withProperty("APP_OPS_NORMALIZATION_SERVICE_TOKEN", "normalization-token")
                .withProperty("APP_OPS_NORMALIZATION_WORKER_SCOPES", "normalization:maintenance ops:maintenance");

        OpsConfigValues values = values(environment);

        assertEquals(8, values.normalizationMaintenanceDevLimit());
        assertEquals(250, values.normalizationMaintenanceBatchSize());
        assertEquals("http://normalization-worker:8080", values.normalizationOwnerBaseUrl());
        assertEquals(Duration.ofMillis(750), values.normalizationOwnerRequestTimeout());
        assertEquals(Duration.ofMinutes(5), values.normalizationLlmOwnerRequestTimeout());
        assertEquals("ops-dev", values.normalizationOwnerServiceId());
        assertEquals("normalization-token", values.normalizationOwnerServiceToken());
        assertEquals("normalization:maintenance ops:maintenance", values.normalizationOwnerScopes());
    }

    @Test
    void aggregationOwnerConfigReadsRuntimeValues() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_OPS_MAINTENANCE_AGGREGATION_DEV_LIMIT", "9")
                .withProperty("APP_OPS_AGGREGATION_WORKER_BASE_URL", "http://aggregation-worker:8080")
                .withProperty("APP_OPS_AGGREGATION_WORKER_REQUEST_TIMEOUT", "900ms")
                .withProperty("APP_OPS_SERVICE_ID", "ops-dev")
                .withProperty("APP_OPS_AGGREGATION_SERVICE_TOKEN", "aggregation-token")
                .withProperty("APP_OPS_AGGREGATION_WORKER_SCOPES", "aggregation:maintenance ops:maintenance");

        OpsConfigValues values = values(environment);

        assertEquals(9, values.aggregationMaintenanceDevLimit());
        assertEquals("http://aggregation-worker:8080", values.aggregationOwnerBaseUrl());
        assertEquals(Duration.ofMillis(900), values.aggregationOwnerRequestTimeout());
        assertEquals("ops-dev", values.aggregationOwnerServiceId());
        assertEquals("aggregation-token", values.aggregationOwnerServiceToken());
        assertEquals("aggregation:maintenance ops:maintenance", values.aggregationOwnerScopes());
    }

    private OpsConfigValues values(MockEnvironment environment) {
        return new OpsConfigValues(environment, repository);
    }
}
