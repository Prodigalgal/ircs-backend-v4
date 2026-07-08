package com.prodigalgal.ircs.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.prodigalgal.ircs.common.lock.DistributedLockBusinessType;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.common.lock.DistributedLockProfile;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
@Slf4j
class CatalogDefaultInitializerRunner implements ApplicationRunner {

    private final JdbcCatalogRepository repository;
    private final CatalogDefaultSeedCatalog defaults;
    private final ObjectMapper objectMapper;
    private final DistributedLockManager lockManager;
    private final String workerId;
    private final boolean clusterLockEnabled;
    private final Duration clusterLockTtl;
    private final boolean enabled;

    CatalogDefaultInitializerRunner(
            JdbcCatalogRepository repository,
            CatalogDefaultSeedCatalog defaults,
            ObjectMapper objectMapper,
            DistributedLockManager lockManager,
            @Value("${spring.application.name:ircs-catalog-service}") String applicationName,
            @Value("${app.catalog.cluster-lock.worker-id:${APP_CATALOG_CLUSTER_LOCK_WORKER_ID:}}") String configuredWorkerId,
            @Value("${app.catalog.cluster-lock.enabled:true}") boolean clusterLockEnabled,
            @Value("${app.catalog.cluster-lock.ttl:PT10M}") Duration clusterLockTtl,
            @Value("${app.catalog.default-seed.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.defaults = defaults;
        this.objectMapper = objectMapper;
        this.lockManager = lockManager;
        this.workerId = WorkerInstanceIds.resolve(applicationName, configuredWorkerId);
        this.clusterLockEnabled = clusterLockEnabled;
        this.clusterLockTtl = clusterLockTtl == null || !clusterLockTtl.isPositive()
                ? Duration.ofMinutes(10)
                : clusterLockTtl;
        this.enabled = enabled;
    }

    static CatalogDefaultInitializerRunner forTest(
            JdbcCatalogRepository repository,
            CatalogDefaultSeedCatalog defaults,
            ObjectMapper objectMapper,
            boolean enabled) {
        return new CatalogDefaultInitializerRunner(
                repository,
                defaults,
                objectMapper,
                null,
                "ircs-catalog-service",
                "local-test",
                false,
                Duration.ofMinutes(10),
                enabled);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("Catalog default seed is disabled.");
            return;
        }
        if (!clusterLockEnabled) {
            seedDefaults();
            return;
        }
        if (lockManager == null) {
            throw new IllegalStateException("catalog distributed lock manager is unavailable");
        }
        DistributedLockProfile profile = lockManager.profileFor(DistributedLockBusinessType.MAINTENANCE_RUNNER);
        if (!lockManager.runWithLock(profile.keyPrefix() + "catalog:default-seed", workerId, clusterLockTtl,
                this::seedDefaults)) {
            log.debug("Catalog default seed skipped: distributed lock is held by another instance");
        }
    }

    private void seedDefaults() {
        SeedStats stats = new SeedStats();

        for (CatalogDefaultSeedCatalog.CategorySeed category : defaults.categories()) {
            stats.record(repository.seedStandardCategory(category.name(), category.slug()));
        }
        for (CatalogDefaultSeedCatalog.GenreSeed genre : defaults.genres()) {
            JdbcCatalogRepository.SeedResult result = repository.seedStandardGenre(genre.name());
            stats.record(result);
        }
        for (CatalogDefaultSeedCatalog.AreaSeed area : defaults.areas()) {
            JdbcCatalogRepository.SeedResult result = repository.seedStandardArea(
                    area.code(),
                    area.name(),
                    area.region());
            stats.record(result);
        }

        for (CatalogDefaultSeedCatalog.LanguageSeed language : defaults.languages()) {
            JdbcCatalogRepository.SeedResult result = repository.seedStandardLanguage(
                    language.code(),
                    language.name(),
                    language.englishName(),
                    language.nativeName());
            stats.record(result);
        }

        for (CatalogDefaultSeedCatalog.DataSourceSeed dataSource : defaults.dataSources()) {
            stats.record(repository.seedDataSource(
                    dataSource.name(),
                    dataSource.baseUrl(),
                    dataSource.apiPath(),
                    defaults.defaultListParams(),
                    defaults.defaultDetailParams(),
                    defaults.defaultFieldMapping()));
        }
        patchExistingDataSourceMappings(stats);

        log.info(
                "Catalog defaults checked={}, inserted={}, updated={}",
                stats.checked,
                stats.inserted,
                stats.updated);
    }

    private void patchExistingDataSourceMappings(SeedStats stats) {
        JsonNode defaultDetail = parseDefaultDetailMapping().orElse(null);
        if (defaultDetail == null) {
            return;
        }
        for (JdbcCatalogRepository.DataSourceSeedMappingRow dataSource : repository.listDataSourceSeedMappings()) {
            if (!StringUtils.hasText(dataSource.fieldMapping())) {
                continue;
            }
            try {
                JsonNode currentRoot = objectMapper.readTree(dataSource.fieldMapping());
                if (!(currentRoot instanceof ObjectNode currentObject)) {
                    continue;
                }
                boolean changed = false;
                if (!currentObject.has("detail_mapping")) {
                    currentObject.set("detail_mapping", defaultDetail.deepCopy());
                    changed = true;
                }
                JsonNode detailNode = currentObject.path("detail_mapping");
                if (!(detailNode instanceof ObjectNode currentDetail)) {
                    continue;
                }
                if (currentDetail.has("subTitle")) {
                    JsonNode subTitle = currentDetail.get("subTitle");
                    if (!currentDetail.has("aliasTitle")) {
                        currentDetail.set("aliasTitle", subTitle);
                    }
                    currentDetail.remove("subTitle");
                    changed = true;
                }
                changed |= patchFieldIfMissing(currentDetail, defaultDetail, "aliasTitle");
                changed |= patchFieldIfMissing(currentDetail, defaultDetail, "totalEpisodes");
                changed |= patchFieldIfMissing(currentDetail, defaultDetail, "duration");
                if (changed) {
                    stats.record(repository.updateDataSourceFieldMapping(dataSource.id(), objectMapper.writeValueAsString(currentObject)));
                }
            } catch (Exception ex) {
                log.warn("Skip data source mapping patch for [{}]: {}", dataSource.name(), ex.getMessage());
            }
        }
    }

    private Optional<JsonNode> parseDefaultDetailMapping() {
        try {
            JsonNode defaultRoot = objectMapper.readTree(defaults.defaultFieldMapping());
            JsonNode defaultDetail = defaultRoot.path("detail_mapping");
            return defaultDetail.isObject() ? Optional.of(defaultDetail) : Optional.empty();
        } catch (Exception ex) {
            log.warn("Skip default data source mapping patch: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private boolean patchFieldIfMissing(ObjectNode currentDetail, JsonNode defaultDetail, String fieldName) {
        if (currentDetail.has(fieldName) || !defaultDetail.has(fieldName)) {
            return false;
        }
        currentDetail.set(fieldName, defaultDetail.get(fieldName).deepCopy());
        return true;
    }

    private static final class SeedStats {
        private int checked;
        private int inserted;
        private int updated;

        private void record(JdbcCatalogRepository.SeedResult result) {
            checked++;
            if (result.inserted()) {
                inserted++;
            }
            if (result.updated()) {
                updated++;
            }
        }
    }
}
