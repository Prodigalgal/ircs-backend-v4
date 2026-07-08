package com.prodigalgal.ircs.common.work;

import com.prodigalgal.ircs.common.aggregation.AggregationWorkTypes;
import com.prodigalgal.ircs.common.audit.AuditReplicationWorkTypes;
import com.prodigalgal.ircs.common.config.SystemConfigValkeyCache;
import com.prodigalgal.ircs.common.magnet.MagnetWorkTypes;
import com.prodigalgal.ircs.common.normalization.LlmCleaningWorkTypes;
import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.search.SearchSyncWorkTypes;
import com.prodigalgal.ircs.common.storage.StorageWorkTypes;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.env.Environment;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnClass({JdbcTemplate.class, StringRedisTemplate.class})
@ConditionalOnBean(JdbcTemplate.class)
public class SystemConfigWorkSubmissionGate implements WorkSubmissionGate {

    private static final Map<String, List<ConfigFlag>> RUNTIME_SUBMISSION_FLAGS = Map.ofEntries(
            Map.entry(SearchSyncWorkTypes.RAW, flags(
                    flag("app.search.sync.enabled", true),
                    flag("app.search.work-queue.worker.enabled", false))),
            Map.entry(SearchSyncWorkTypes.UNIFIED, flags(
                    flag("app.search.sync.enabled", true),
                    flag("app.search.work-queue.worker.enabled", false))),
            Map.entry(LlmCleaningWorkTypes.RAW_TERM, flags(
                    flag("app.ai.llm.enabled", false),
                    flag("app.normalization.llm-cleaning.work-queue.worker.enabled", false))),
            Map.entry(StorageWorkTypes.AVATAR_SYNC, flags(
                    flag("app.storage.r2.enabled", false),
                    flag("app.storage.r2.work-queue.worker.enabled", false))),
            Map.entry(StorageWorkTypes.COVER_R2_SYNC, flags(
                    flag("app.storage.r2.enabled", false),
                    flag("app.storage.r2.work-queue.worker.enabled", false))),
            Map.entry(AuditReplicationWorkTypes.ES_REPLICATION, flags(
                    flag("app.audit.es-replication.enabled", true),
                    flag("app.search.audit-es-replication.worker.enabled", true))),
            Map.entry(MagnetWorkTypes.SEARCH, flags(
                    flag("app.magnet.work-queue.submission.enabled", true))),
            Map.entry(PipelineRuntimeWorkTypes.NORMALIZE_VIDEO, List.of()),
            Map.entry(PipelineRuntimeWorkTypes.ENRICH_METADATA, List.of()),
            Map.entry(PipelineRuntimeWorkTypes.METADATA_PROVIDER, List.of()));

    private static final Map<String, List<ConfigFlag>> RUNTIME_CONSUMER_FLAGS = Map.ofEntries(
            Map.entry(AggregationWorkTypes.RAW_VIDEO, List.of()),
            Map.entry(SearchSyncWorkTypes.RAW, runtimeSubmissionFlags(SearchSyncWorkTypes.RAW)),
            Map.entry(SearchSyncWorkTypes.UNIFIED, runtimeSubmissionFlags(SearchSyncWorkTypes.UNIFIED)),
            Map.entry(LlmCleaningWorkTypes.RAW_TERM, runtimeSubmissionFlags(LlmCleaningWorkTypes.RAW_TERM)),
            Map.entry(StorageWorkTypes.AVATAR_SYNC, runtimeSubmissionFlags(StorageWorkTypes.AVATAR_SYNC)),
            Map.entry(StorageWorkTypes.COVER_R2_SYNC, runtimeSubmissionFlags(StorageWorkTypes.COVER_R2_SYNC)),
            Map.entry(AuditReplicationWorkTypes.ES_REPLICATION,
                    runtimeSubmissionFlags(AuditReplicationWorkTypes.ES_REPLICATION)),
            Map.entry(MagnetWorkTypes.SEARCH, flags(
                    flag("app.magnet.work-queue.worker.enabled", true))),
            Map.entry(PipelineRuntimeWorkTypes.NORMALIZE_VIDEO, List.of()),
            Map.entry(PipelineRuntimeWorkTypes.ENRICH_METADATA, List.of()),
            Map.entry(PipelineRuntimeWorkTypes.METADATA_PROVIDER, List.of()));

    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;
    private final SystemConfigValkeyCache cache;
    public SystemConfigWorkSubmissionGate(
            JdbcTemplate jdbcTemplate,
            Environment environment,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${app.system-config.valkey-cache.key-prefix:"
                    + SystemConfigValkeyCache.DEFAULT_KEY_PREFIX + "}") String cacheKeyPrefix,
            @Value("${app.system-config.valkey-cache.ttl:PT12H}") Duration cacheTtl,
            @Value("${app.system-config.local-cache.ttl:PT5M}") Duration localCacheTtl) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
        this.cache = new SystemConfigValkeyCache(redisTemplateProvider, cacheKeyPrefix, cacheTtl, localCacheTtl);
    }

    @Override
    public boolean canSubmitRuntime(String taskType) {
        return enabled(RUNTIME_SUBMISSION_FLAGS.get(taskType));
    }

    public static List<ConfigFlag> runtimeSubmissionFlags(String taskType) {
        return RUNTIME_SUBMISSION_FLAGS.getOrDefault(taskType, List.of());
    }

    public static Optional<List<ConfigFlag>> runtimeConsumerFlags(String taskType) {
        return Optional.ofNullable(RUNTIME_CONSUMER_FLAGS.get(taskType));
    }

    private boolean enabled(List<ConfigFlag> flags) {
        if (flags == null || flags.isEmpty()) {
            return true;
        }
        return flags.stream().allMatch(this::enabled);
    }

    private boolean enabled(ConfigFlag flag) {
        return cache.findValue(flag.key(), () -> findValueFromDatabase(flag.key()))
                .map(value -> parseBoolean(value, flag.defaultEnabled()))
                .orElseGet(() -> environment.getProperty(flag.key(), Boolean.class, flag.defaultEnabled()));
    }

    private Optional<String> findValueFromDatabase(String key) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "select config_value from system_configs where config_key = ?",
                    String.class,
                    key));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return switch (value.trim().toLowerCase()) {
            case "true", "1", "yes", "on", "enabled" -> true;
            case "false", "0", "no", "off", "disabled" -> false;
            default -> defaultValue;
        };
    }

    private static List<ConfigFlag> flags(ConfigFlag... flags) {
        return List.of(flags);
    }

    private static ConfigFlag flag(String key, boolean defaultEnabled) {
        return new ConfigFlag(key, defaultEnabled);
    }

    public record ConfigFlag(String key, boolean defaultEnabled) {
    }
}
