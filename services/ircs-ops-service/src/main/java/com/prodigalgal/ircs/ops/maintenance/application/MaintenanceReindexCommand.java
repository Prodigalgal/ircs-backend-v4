package com.prodigalgal.ircs.ops.maintenance.application;

import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import com.prodigalgal.ircs.ops.config.OpsConfigValues;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class MaintenanceReindexCommand {

    public static final String TASK_UNIFIED_REINDEX = "search-reindex-unified";
    public static final String TASK_RAW_REINDEX = "search-reindex-raw";
    public static final String TASK_UNIFIED_REINDEX_ALL = "search-reindex-unified-all";
    public static final String TASK_RAW_REINDEX_ALL = "search-reindex-raw-all";

    static final int DEFAULT_DEV_LIMIT = OpsConfigValues.DEFAULT_REINDEX_DEV_LIMIT;
    static final int MAX_DEV_LIMIT = OpsConfigValues.MAX_REINDEX_DEV_LIMIT;
    static final int DEFAULT_BATCH_SIZE = OpsConfigValues.DEFAULT_REINDEX_BATCH_SIZE;
    static final int MAX_BATCH_SIZE = OpsConfigValues.MAX_REINDEX_BATCH_SIZE;
    private static final String SELECT_RAW_IDS = """
            select id
              from raw_videos
             order by updated_at desc nulls last, id
             limit :limit
            """;
    private static final String SELECT_RAW_IDS_FIRST_BATCH = """
            select id
              from raw_videos
             order by id asc
             limit :limit
            """;
    private static final String SELECT_RAW_IDS_NEXT_BATCH = """
            select id
              from raw_videos
             where id > :afterId
             order by id asc
             limit :limit
            """;
    private static final String SELECT_UNIFIED_IDS_FIRST_BATCH = """
            select id
              from unified_videos
             order by id asc
             limit :limit
            """;
    private static final String SELECT_UNIFIED_IDS_NEXT_BATCH = """
            select id
              from unified_videos
             where id > :afterId
             order by id asc
             limit :limit
            """;
    private static final String SELECT_UNIFIED_IDS = """
            select id
              from unified_videos
             order by updated_at desc nulls last, id
             limit :limit
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final MaintenanceSearchSyncClient searchSyncClient;
    private final OpsConfigValues configValues;

    public MaintenanceReindexCommand(
            NamedParameterJdbcTemplate jdbcTemplate,
            MaintenanceSearchSyncClient searchSyncClient,
            OpsConfigValues configValues) {
        this.jdbcTemplate = jdbcTemplate;
        this.searchSyncClient = searchSyncClient;
        this.configValues = configValues;
    }

    public MaintenanceRunResult enqueueUnifiedIndex() {
        return enqueueUnifiedIndex(null);
    }

    public MaintenanceRunResult enqueueUnifiedIndex(String correlationId) {
        return enqueueIndex(TASK_UNIFIED_REINDEX, SELECT_UNIFIED_IDS, SearchEntityType.UNIFIED_VIDEO, correlationId);
    }

    public MaintenanceRunResult enqueueRawIndex() {
        return enqueueRawIndex(null);
    }

    public MaintenanceRunResult enqueueRawIndex(String correlationId) {
        return enqueueIndex(TASK_RAW_REINDEX, SELECT_RAW_IDS, SearchEntityType.RAW_VIDEO, correlationId);
    }

    public MaintenanceRunResult enqueueAllUnifiedIndex(String correlationId) {
        return enqueueAllIndex(
                TASK_UNIFIED_REINDEX_ALL,
                SELECT_UNIFIED_IDS_FIRST_BATCH,
                SELECT_UNIFIED_IDS_NEXT_BATCH,
                SearchEntityType.UNIFIED_VIDEO,
                correlationId);
    }

    public MaintenanceRunResult enqueueAllRawIndex(String correlationId) {
        return enqueueAllIndex(
                TASK_RAW_REINDEX_ALL,
                SELECT_RAW_IDS_FIRST_BATCH,
                SELECT_RAW_IDS_NEXT_BATCH,
                SearchEntityType.RAW_VIDEO,
                correlationId);
    }

    public MaintenanceRunResult hardResetUnifiedIndex(String correlationId) {
        int changed = searchSyncClient.hardResetIndex(SearchEntityType.UNIFIED_VIDEO, correlationId);
        return new MaintenanceRunResult("aggregation-reset.search-hard-reset", 1, changed, List.of());
    }

    private MaintenanceRunResult enqueueIndex(
            String taskName,
            String selectSql,
            SearchEntityType entityType,
            String correlationId) {
        int devLimit = configValues.reindexDevLimit();
        if (devLimit == 0) {
            return new MaintenanceRunResult(taskName, 0, 0, List.of());
        }

        List<UUID> ids = jdbcTemplate.query(
                selectSql,
                new MapSqlParameterSource("limit", devLimit),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (ids.isEmpty()) {
            return new MaintenanceRunResult(taskName, 0, 0, List.of());
        }

        int accepted = searchSyncClient.enqueueIndex(ids, entityType, correlationId);

        return new MaintenanceRunResult(taskName, ids.size(), accepted, ids);
    }

    private MaintenanceRunResult enqueueAllIndex(
            String taskName,
            String firstBatchSql,
            String nextBatchSql,
            SearchEntityType entityType,
            String correlationId) {
        int batchSize = configValues.reindexBatchSize();
        UUID cursor = null;
        int selected = 0;
        int accepted = 0;
        List<UUID> sample = new java.util.ArrayList<>();

        while (true) {
            List<UUID> ids = jdbcTemplate.query(
                    cursor == null ? firstBatchSql : nextBatchSql,
                    new MapSqlParameterSource()
                            .addValue("afterId", cursor)
                            .addValue("limit", batchSize),
                    (rs, rowNum) -> rs.getObject("id", UUID.class));
            if (ids.isEmpty()) {
                break;
            }
            selected = safeSum(selected, ids.size());
            accepted = safeSum(accepted, searchSyncClient.enqueueIndex(ids, entityType, correlationId));
            collectSample(sample, ids);
            cursor = ids.getLast();
            if (ids.size() < batchSize) {
                break;
            }
        }

        return new MaintenanceRunResult(taskName, selected, accepted, sample);
    }

    private void collectSample(List<UUID> sample, List<UUID> ids) {
        int remaining = Math.max(0, 20 - sample.size());
        if (remaining == 0) {
            return;
        }
        sample.addAll(ids.stream().limit(remaining).toList());
    }

    private int safeSum(int left, int right) {
        long total = Math.max(0, left) + Math.max(0, right);
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }
}
