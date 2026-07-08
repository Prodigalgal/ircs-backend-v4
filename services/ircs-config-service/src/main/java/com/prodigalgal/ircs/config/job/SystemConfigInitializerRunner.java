package com.prodigalgal.ircs.config.job;

import com.prodigalgal.ircs.common.lock.DistributedLockBusinessType;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.common.lock.DistributedLockProfile;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import com.prodigalgal.ircs.config.application.SystemConfigDefaults;
import com.prodigalgal.ircs.config.infrastructure.JdbcConfigRepository;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SystemConfigInitializerRunner implements ApplicationRunner {

    private final JdbcConfigRepository repository;
    private final SystemConfigDefaults defaults;
    private final DistributedLockManager lockManager;
    private final Environment environment;
    private final String workerId;
    private final boolean clusterLockEnabled;
    private final Duration clusterLockTtl;

    SystemConfigInitializerRunner(
            JdbcConfigRepository repository,
            SystemConfigDefaults defaults,
            DistributedLockManager lockManager,
            Environment environment,
            @Value("${spring.application.name:ircs-config-service}") String applicationName,
            @Value("${app.config.cluster-lock.worker-id:${APP_CONFIG_CLUSTER_LOCK_WORKER_ID:}}") String configuredWorkerId,
            @Value("${app.config.cluster-lock.enabled:true}") boolean clusterLockEnabled,
            @Value("${app.config.cluster-lock.ttl:PT10M}") Duration clusterLockTtl) {
        this.repository = repository;
        this.defaults = defaults;
        this.lockManager = lockManager;
        this.environment = environment;
        this.workerId = WorkerInstanceIds.resolve(applicationName, configuredWorkerId);
        this.clusterLockEnabled = clusterLockEnabled;
        this.clusterLockTtl = clusterLockTtl == null || !clusterLockTtl.isPositive()
                ? Duration.ofMinutes(10)
                : clusterLockTtl;
    }

    public static SystemConfigInitializerRunner forTest(JdbcConfigRepository repository, SystemConfigDefaults defaults) {
        return new SystemConfigInitializerRunner(
                repository,
                defaults,
                null,
                null,
                "ircs-config-service",
                "local-test",
                false,
                Duration.ofMinutes(10));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!clusterLockEnabled) {
            initializeDefaults();
            return;
        }
        if (lockManager == null) {
            throw new IllegalStateException("config distributed lock manager is unavailable");
        }
        DistributedLockProfile profile = lockManager.profileFor(DistributedLockBusinessType.MAINTENANCE_RUNNER);
        if (!lockManager.runWithLock(profile.keyPrefix() + "config:default-initializer", workerId, clusterLockTtl,
                this::initializeDefaults)) {
            log.debug("SystemConfig defaults initializer skipped: distributed lock is held by another instance");
        }
    }

    private void initializeDefaults() {
        int checked = 0;
        int inserted = 0;
        int updatedDescriptions = 0;
        for (SystemConfigDefaults.DefaultConfig config : defaults.all()) {
            SystemConfigDefaults.ResolvedDefault resolved = defaults.resolve(config, environment);
            JdbcConfigRepository.UpsertResult result = repository.upsertDefault(resolved);
            checked++;
            if (result.inserted()) {
                inserted++;
            }
            if (result.descriptionUpdated()) {
                updatedDescriptions++;
            }
        }
        log.info(
                "SystemConfig defaults checked={}, inserted={}, descriptionUpdated={}",
                checked,
                inserted,
                updatedDescriptions);
    }
}
