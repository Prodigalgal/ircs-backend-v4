package com.prodigalgal.ircs.content.auxiliary.job;

import com.prodigalgal.ircs.common.lock.DistributedLockBusinessType;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.common.lock.DistributedLockProfile;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.ResolverLine;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.ResolverRequest;
import com.prodigalgal.ircs.content.auxiliary.infrastructure.JdbcAuxiliaryAdminRepository;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(50)
@Slf4j
class DefaultResolverPresetSeeder implements ApplicationRunner {

    private static final String DEFAULT_LINE_NAME = "默认线路";

    private static final List<ResolverPreset> DEFAULT_PRESETS = List.of(
            new ResolverPreset(
                    "金蝉解析",
                    "https://zy.jinchancaiji.com/?url=",
                    "支持多线路聚合解析"),
            new ResolverPreset(
                    "789解析",
                    "https://www.789jiexi.com/?url=",
                    "速度较快，广告较少"),
            new ResolverPreset(
                    "火花解析",
                    "https://cj.huohua.live/?url=",
                    "部分资源需授权"),
            new ResolverPreset(
                    "麒麟解析",
                    "https://www.qilinzyz.com/?url=",
                    "综合性解析接口"));

    private final JdbcAuxiliaryAdminRepository repository;
    private final DistributedLockManager lockManager;
    private final String workerId;
    private final boolean clusterLockEnabled;
    private final Duration clusterLockTtl;
    private final boolean enabled;

    DefaultResolverPresetSeeder(
            JdbcAuxiliaryAdminRepository repository,
            DistributedLockManager lockManager,
            @Value("${spring.application.name:ircs-content-service}") String applicationName,
            @Value("${app.content.cluster-lock.worker-id:${APP_CONTENT_CLUSTER_LOCK_WORKER_ID:}}") String configuredWorkerId,
            @Value("${app.content.cluster-lock.enabled:true}") boolean clusterLockEnabled,
            @Value("${app.content.cluster-lock.ttl:PT10M}") String clusterLockTtl,
            @Value("${app.content.resolver-preset-seed.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.lockManager = lockManager;
        this.workerId = WorkerInstanceIds.resolve(applicationName, configuredWorkerId);
        this.clusterLockEnabled = clusterLockEnabled;
        this.clusterLockTtl = parseDuration(clusterLockTtl, Duration.ofMinutes(10));
        this.enabled = enabled;
    }

    static DefaultResolverPresetSeeder forTest(JdbcAuxiliaryAdminRepository repository, boolean enabled) {
        return new DefaultResolverPresetSeeder(
                repository,
                null,
                "ircs-content-service",
                "local-test",
                false,
                "PT10M",
                enabled);
    }

    @Override
    public void run(ApplicationArguments args) {
        int inserted = seedMissingResolvers();
        if (enabled) {
            log.info("Default resolver presets checked={}, inserted={}", DEFAULT_PRESETS.size(), inserted);
        }
    }

    int seedMissingResolvers() {
        if (!enabled) {
            log.info("Default resolver preset seed is disabled.");
            return 0;
        }
        if (!clusterLockEnabled) {
            return seedMissingResolversLocked();
        }
        if (lockManager == null) {
            throw new IllegalStateException("content distributed lock manager is unavailable");
        }
        DistributedLockProfile profile = lockManager.profileFor(DistributedLockBusinessType.MAINTENANCE_RUNNER);
        return lockManager.callWithLock(profile.keyPrefix() + "content:resolver-preset-seed", workerId, clusterLockTtl,
                this::seedMissingResolversLocked).orElseGet(() -> {
                    log.debug("Default resolver preset seed skipped: distributed lock is held by another instance");
                    return 0;
                });
    }

    private int seedMissingResolversLocked() {
        int inserted = 0;
        for (ResolverPreset preset : DEFAULT_PRESETS) {
            if (repository.resolverExistsByName(preset.name())) {
                continue;
            }
            try {
                repository.createResolver(new ResolverRequest(
                        null,
                        preset.name(),
                        true,
                        null,
                        preset.remark(),
                        List.of(new ResolverLine(DEFAULT_LINE_NAME, preset.urlPattern()))));
                inserted++;
            } catch (DuplicateKeyException ex) {
                log.debug("Default resolver preset already exists: {}", preset.name());
            }
        }
        return inserted;
    }

    private static Duration parseDuration(String value, Duration fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        try {
            Duration duration = Duration.parse(trimmed);
            return duration.isPositive() ? duration : fallback;
        } catch (RuntimeException ignored) {
            // Continue with compact duration suffix parsing.
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith("ms")) {
                return positive(Duration.ofMillis(Long.parseLong(lower.substring(0, lower.length() - 2))), fallback);
            }
            if (lower.endsWith("s")) {
                return positive(Duration.ofSeconds(Long.parseLong(lower.substring(0, lower.length() - 1))), fallback);
            }
            if (lower.endsWith("m")) {
                return positive(Duration.ofMinutes(Long.parseLong(lower.substring(0, lower.length() - 1))), fallback);
            }
            if (lower.endsWith("h")) {
                return positive(Duration.ofHours(Long.parseLong(lower.substring(0, lower.length() - 1))), fallback);
            }
        } catch (RuntimeException ignored) {
            return fallback;
        }
        return fallback;
    }

    private static Duration positive(Duration duration, Duration fallback) {
        return duration != null && duration.isPositive() ? duration : fallback;
    }

    record ResolverPreset(String name, String urlPattern, String remark) {
    }
}
