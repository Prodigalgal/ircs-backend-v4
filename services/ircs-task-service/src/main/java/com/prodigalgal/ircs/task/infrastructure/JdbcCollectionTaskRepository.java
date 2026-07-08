package com.prodigalgal.ircs.task.infrastructure;

import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import com.prodigalgal.ircs.task.domain.MediaRequestBatchItemCandidate;
import com.prodigalgal.ircs.task.domain.ScheduledTaskDefinition;
import com.prodigalgal.ircs.task.domain.MediaRequestCandidate;
import com.prodigalgal.ircs.task.domain.TaskExecutionPlan;
import com.prodigalgal.ircs.task.domain.TaskRuntimeState;
import com.prodigalgal.ircs.task.domain.ValidatedCreateTask;
import com.prodigalgal.ircs.task.domain.ValidatedUpdateTask;
import com.prodigalgal.ircs.task.dto.MediaRequestBatchItemResponse;
import com.prodigalgal.ircs.task.dto.MediaRequestBatchResponse;
import com.prodigalgal.ircs.task.dto.TaskCardSummary;
import com.prodigalgal.ircs.task.dto.TaskDetailSummary;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcCollectionTaskRepository {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String MEDIA_REQUEST_BATCH_HEADER_KEY = "ircsMediaRequestBatch";

    private final JdbcTemplate jdbcTemplate;

    public boolean existsDataSource(UUID dataSourceId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "select exists(select 1 from data_sources where id = ?)",
                Boolean.class,
                dataSourceId);
        return Boolean.TRUE.equals(exists);
    }

    public List<DataSourceSeedCandidate> findDefaultTaskSeedDataSources() {
        return jdbcTemplate.query(
                """
                select id, name
                from data_sources
                where name is not null
                  and btrim(name) <> ''
                order by name asc, id asc
                """,
                (rs, rowNum) -> new DataSourceSeedCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("name")));
    }

    public boolean existsTaskByName(String name) {
        Boolean exists = jdbcTemplate.queryForObject(
                "select exists(select 1 from collection_tasks where name = ?)",
                Boolean.class,
                name);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<UUID> findTaskIdByName(String name) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "select id from collection_tasks where name = ?",
                    UUID.class,
                    name));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<MediaRequestCandidate> claimPendingMediaRequests(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        return jdbcTemplate.query(
                """
                with claimed as (
                    select id
                      from portal_media_requests
                     where status = 'PENDING'
                     order by request_count desc, last_requested_at asc, id asc
                     limit ?
                     for update skip locked
                )
                update portal_media_requests request
                   set status = 'SCHEDULING',
                       scheduled_at = now(),
                       updated_at = now(),
                       version = request.version + 1
                  from claimed
                 where request.id = claimed.id
                returning request.id, request.title, request.release_year, request.request_count
                """,
                (rs, rowNum) -> new MediaRequestCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        apiReleaseYear(rs.getInt("release_year")),
                        rs.getInt("request_count")),
                safeLimit);
    }

    public UUID createMediaRequestBatch(List<MediaRequestCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("media request candidates are required");
        }
        UUID batchId = IrcsUuidGenerators.nextId();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                """
                insert into portal_media_request_batches (
                    id, created_at, updated_at, version, status, request_count
                ) values (?, ?, ?, 0, 'READY', ?)
                """,
                batchId,
                now,
                now,
                candidates.size());
        for (MediaRequestCandidate candidate : candidates) {
            jdbcTemplate.update(
                    """
                    insert into portal_media_request_batch_items (
                        id, batch_id, media_request_id, created_at, updated_at, version,
                        title, release_year, request_count, status
                    ) values (?, ?, ?, ?, ?, 0, ?, ?, ?, 'READY')
                    """,
                    IrcsUuidGenerators.nextId(),
                    batchId,
                    candidate.id(),
                    now,
                    now,
                    candidate.title(),
                    dbReleaseYear(candidate.releaseYear()),
                    candidate.requestCount());
        }
        jdbcTemplate.update(
                "update portal_media_requests set status = 'BATCHED', current_batch_id = ?, updated_at = now(), "
                        + "version = version + 1, last_error_message = null where id in ("
                        + placeholders(candidates.size()) + ")",
                prepend(batchId, candidates.stream().map(MediaRequestCandidate::id).toList()).toArray());
        return batchId;
    }

    public void markMediaRequestsScheduled(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<UUID> safeIds = List.copyOf(ids);
        jdbcTemplate.update(
                "update portal_media_requests set status = 'SCHEDULED', updated_at = now(), "
                        + "version = version + 1, last_error_message = null where id in (" + placeholders(safeIds.size()) + ")",
                safeIds.toArray());
    }

    public void markMediaRequestSkippedExisting(UUID mediaRequestId, UUID existingVideoId, String existingVideoSource) {
        jdbcTemplate.update(
                """
                update portal_media_requests
                   set status = 'SKIPPED_EXISTS',
                       existing_video_id = ?,
                       existing_video_source = ?,
                       completed_at = now(),
                       current_batch_id = null,
                       updated_at = now(),
                       version = version + 1,
                       last_error_message = null
                 where id = ?
                """,
                existingVideoId,
                existingVideoSource,
                mediaRequestId);
    }

    public Page<MediaRequestBatchResponse> findMediaRequestBatches(Pageable pageable, String status) {
        Pageable safePageable = sanitize(pageable);
        List<Object> params = new ArrayList<>();
        String where = "";
        if (status != null && !status.isBlank()) {
            where = "where status = ?";
            params.add(status.trim().toUpperCase());
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from portal_media_request_batches " + where,
                Long.class,
                params.toArray());

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(safePageable.getPageSize());
        queryParams.add(safePageable.getOffset());
        List<MediaRequestBatchResponse> content = jdbcTemplate.query(
                """
                select id, status, request_count, scheduled_count, skipped_count, failed_count,
                       created_at, updated_at, started_at, cancelled_at, completed_at, last_error_message
                  from portal_media_request_batches
                %s
                 order by created_at desc, id desc
                 limit ? offset ?
                """.formatted(where),
                mediaRequestBatchMapper(false),
                queryParams.toArray());
        return new PageImpl<>(content, safePageable, total == null ? 0 : total);
    }

    public Optional<MediaRequestBatchResponse> findMediaRequestBatch(UUID batchId) {
        try {
            MediaRequestBatchResponse batch = jdbcTemplate.queryForObject(
                    """
                    select id, status, request_count, scheduled_count, skipped_count, failed_count,
                           created_at, updated_at, started_at, cancelled_at, completed_at, last_error_message
                      from portal_media_request_batches
                     where id = ?
                    """,
                    mediaRequestBatchMapper(true),
                    batchId);
            return Optional.ofNullable(batch);
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public boolean mediaRequestBatchExists(UUID batchId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "select exists(select 1 from portal_media_request_batches where id = ?)",
                Boolean.class,
                batchId);
        return Boolean.TRUE.equals(exists);
    }

    public boolean markMediaRequestBatchRunning(UUID batchId) {
        return jdbcTemplate.update(
                """
                update portal_media_request_batches
                   set status = 'RUNNING',
                       started_at = coalesce(started_at, now()),
                       updated_at = now(),
                       version = version + 1,
                       last_error_message = null
                 where id = ?
                   and status = 'READY'
                """,
                batchId) > 0;
    }

    public List<MediaRequestBatchItemCandidate> findReadyMediaRequestBatchItems(UUID batchId) {
        return jdbcTemplate.query(
                """
                select id, media_request_id, title, release_year, request_count
                  from portal_media_request_batch_items
                 where batch_id = ?
                   and status = 'READY'
                 order by request_count desc, created_at asc, id asc
                """,
                (rs, rowNum) -> new MediaRequestBatchItemCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getObject("media_request_id", UUID.class),
                        rs.getString("title"),
                        apiReleaseYear(rs.getInt("release_year")),
                        rs.getInt("request_count")),
                batchId);
    }

    public void markMediaRequestBatchItemSkipped(
            UUID itemId,
            UUID mediaRequestId,
            UUID existingVideoId,
            String existingVideoSource) {
        jdbcTemplate.update(
                """
                update portal_media_request_batch_items
                   set status = 'SKIPPED_EXISTS',
                       existing_video_id = ?,
                       existing_video_source = ?,
                       updated_at = now(),
                       version = version + 1,
                       last_error_message = null
                 where id = ?
                """,
                existingVideoId,
                existingVideoSource,
                itemId);
        jdbcTemplate.update(
                """
                update portal_media_requests
                   set status = 'SKIPPED_EXISTS',
                       existing_video_id = ?,
                       existing_video_source = ?,
                       completed_at = now(),
                       updated_at = now(),
                       version = version + 1,
                       last_error_message = null
                 where id = ?
                """,
                existingVideoId,
                existingVideoSource,
                mediaRequestId);
    }

    public void markMediaRequestBatchItemScheduled(UUID itemId, UUID mediaRequestId, int scheduledTaskCount) {
        jdbcTemplate.update(
                """
                update portal_media_request_batch_items
                   set status = 'SCHEDULED',
                       scheduled_task_count = ?,
                       updated_at = now(),
                       version = version + 1,
                       last_error_message = null
                 where id = ?
                """,
                Math.max(0, scheduledTaskCount),
                itemId);
        jdbcTemplate.update(
                """
                update portal_media_requests
                   set status = 'SCHEDULED',
                       scheduled_at = now(),
                       updated_at = now(),
                       version = version + 1,
                       last_error_message = null
                 where id = ?
                """,
                mediaRequestId);
    }

    public void markMediaRequestBatchItemFailed(UUID itemId, UUID mediaRequestId, String reason) {
        jdbcTemplate.update(
                """
                update portal_media_request_batch_items
                   set status = 'FAILED',
                       updated_at = now(),
                       version = version + 1,
                       last_error_message = ?
                 where id = ?
                """,
                truncate(reason),
                itemId);
        jdbcTemplate.update(
                """
                update portal_media_requests
                   set status = 'FAILED',
                       updated_at = now(),
                       version = version + 1,
                       last_error_message = ?
                 where id = ?
                """,
                truncate(reason),
                mediaRequestId);
    }

    public void finishMediaRequestBatch(UUID batchId, String status, String reason) {
        MediaRequestBatchCounters counters = mediaRequestBatchCounters(batchId);
        jdbcTemplate.update(
                """
                update portal_media_request_batches
                   set status = ?,
                       scheduled_count = ?,
                       skipped_count = ?,
                       failed_count = ?,
                       completed_at = case when ? in ('COMPLETED', 'FAILED', 'PARTIAL') then now() else completed_at end,
                       updated_at = now(),
                       version = version + 1,
                       last_error_message = ?
                 where id = ?
                """,
                status,
                counters.scheduled(),
                counters.skipped(),
                counters.failed(),
                status,
                truncate(reason),
                batchId);
    }

    public boolean cancelMediaRequestBatch(UUID batchId, String reason) {
        int updated = jdbcTemplate.update(
                """
                update portal_media_request_batches
                   set status = 'CANCELLED',
                       cancelled_at = now(),
                       updated_at = now(),
                       version = version + 1,
                       last_error_message = ?
                 where id = ?
                   and status = 'READY'
                """,
                truncate(reason),
                batchId);
        if (updated <= 0) {
            return false;
        }
        jdbcTemplate.update(
                """
                update portal_media_request_batch_items
                   set status = 'CANCELLED',
                       updated_at = now(),
                       version = version + 1,
                       last_error_message = ?
                 where batch_id = ?
                   and status = 'READY'
                """,
                truncate(reason),
                batchId);
        jdbcTemplate.update(
                """
                update portal_media_requests
                   set status = 'CANCELLED',
                       updated_at = now(),
                       version = version + 1,
                       last_error_message = ?
                 where current_batch_id = ?
                   and status in ('BATCHED', 'SCHEDULING')
                """,
                truncate(reason),
                batchId);
        jdbcTemplate.update(
                """
                update collection_tasks
                   set status = 'PAUSED',
                       enabled = false,
                       updated_at = now(),
                       last_error_message = ?
                 where headers -> ? ->> 'batchId' = ?
                   and status in ('IDLE', 'QUEUED')
                """,
                truncate(reason),
                MEDIA_REQUEST_BATCH_HEADER_KEY,
                batchId.toString());
        return true;
    }

    public List<UUID> findMediaRequestCollectionTaskIds(UUID batchId) {
        return jdbcTemplate.query(
                """
                select id
                  from collection_tasks
                 where headers -> ? ->> 'batchId' = ?
                 order by created_at desc, id desc
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                MEDIA_REQUEST_BATCH_HEADER_KEY,
                batchId.toString());
    }

    public void markMediaRequestsPending(List<UUID> ids, String reason) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Object> args = new ArrayList<>();
        args.add(reason);
        args.addAll(ids);
        jdbcTemplate.update(
                "update portal_media_requests set status = 'PENDING', updated_at = now(), "
                        + "version = version + 1, last_error_message = ? where id in (" + placeholders(ids.size()) + ")",
                args.toArray());
    }

    public UUID create(ValidatedCreateTask task) {
        UUID id = IrcsUuidGenerators.nextId();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                """
                insert into collection_tasks (
                    id, created_at, updated_at, version, name, status, enabled, cron_expression, time_zone,
                    task_type, start_page, end_page, current_page, filter_type, filter_hours, filter_keywords,
                    request_delay_type, fixed_delay_ms, random_delay_min_ms, random_delay_max_ms, timeout_ms,
                    max_retries, user_agent, enable_random_ua, use_custom_proxy, proxy_type, proxy_host,
                    proxy_port, proxy_username, proxy_password, headers, last_execution_time, stat_start_time,
                    stat_end_time, stat_total_found, stat_processed, stat_success, stat_failed,
                    last_error_message, data_source_id, stat_inserted, stat_updated, stat_ignored
                ) values (
                    ?, ?, ?, 0, ?, 'IDLE', ?, ?, ?,
                    ?, ?, ?, null, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, cast(? as jsonb), null, null,
                    null, 0, 0, 0, 0,
                    null, ?, 0, 0, 0
                )
                """,
                id,
                now,
                now,
                task.name(),
                task.enabled(),
                task.cronExpression(),
                task.timeZone(),
                task.taskType(),
                task.startPage(),
                task.endPage(),
                task.filterType(),
                task.filterHours(),
                task.filterKeywords(),
                task.requestDelayType(),
                task.fixedDelayMs(),
                task.randomDelayMinMs(),
                task.randomDelayMaxMs(),
                task.timeoutMs(),
                task.maxRetries(),
                task.userAgent(),
                task.enableRandomUa(),
                task.useCustomProxy(),
                task.proxyType(),
                task.proxyHost(),
                task.proxyPort(),
                task.proxyUsername(),
                task.proxyPassword(),
                task.headers(),
                task.dataSourceId());
        return id;
    }

    public boolean update(UUID id, ValidatedUpdateTask task) {
        Map<String, Object> updates = new LinkedHashMap<>();
        putIfPresent(updates, "name", task.name());
        putIfPresent(updates, "enabled", task.enabled());
        putIfPresent(updates, "cron_expression", task.cronExpression());
        putIfPresent(updates, "time_zone", task.timeZone());
        putIfPresent(updates, "data_source_id", task.dataSourceId());
        putIfPresent(updates, "task_type", task.taskType());
        putIfPresent(updates, "start_page", task.startPage());
        putIfPresent(updates, "end_page", task.endPage());
        putIfPresent(updates, "filter_type", task.filterType());
        putIfPresent(updates, "filter_hours", task.filterHours());
        putIfPresent(updates, "filter_keywords", task.filterKeywords());
        putIfPresent(updates, "request_delay_type", task.requestDelayType());
        putIfPresent(updates, "fixed_delay_ms", task.fixedDelayMs());
        putIfPresent(updates, "random_delay_min_ms", task.randomDelayMinMs());
        putIfPresent(updates, "random_delay_max_ms", task.randomDelayMaxMs());
        putIfPresent(updates, "timeout_ms", task.timeoutMs());
        putIfPresent(updates, "max_retries", task.maxRetries());
        putIfPresent(updates, "user_agent", task.userAgent());
        putIfPresent(updates, "enable_random_ua", task.enableRandomUa());
        putIfPresent(updates, "use_custom_proxy", task.useCustomProxy());
        putIfPresent(updates, "proxy_type", task.proxyType());
        putIfPresent(updates, "proxy_host", task.proxyHost());
        putIfPresent(updates, "proxy_port", task.proxyPort());
        putIfPresent(updates, "proxy_username", task.proxyUsername());
        putIfPresent(updates, "proxy_password", task.proxyPassword());

        List<Object> params = new ArrayList<>();
        List<String> setClauses = new ArrayList<>();
        updates.forEach((column, value) -> {
            setClauses.add(column + " = ?");
            params.add(value);
        });
        if (task.headers() != null) {
            setClauses.add("headers = cast(? as jsonb)");
            params.add(task.headers());
        }
        setClauses.add("updated_at = ?");
        params.add(Timestamp.from(Instant.now()));
        params.add(id);

        int count = jdbcTemplate.update(
                "update collection_tasks set " + String.join(", ", setClauses) + " where id = ?",
                params.toArray());
        return count > 0;
    }

    public void delete(UUID id) {
        jdbcTemplate.update("delete from collection_tasks where id = ?", id);
    }

    public Optional<TaskRuntimeState> findRuntimeState(UUID id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select id, data_source_id, status, enabled, start_page, current_page
                    from collection_tasks
                    where id = ?
                    """,
                    (rs, rowNum) -> new TaskRuntimeState(
                            rs.getObject("id", UUID.class),
                            rs.getObject("data_source_id", UUID.class),
                            rs.getString("status"),
                            rs.getBoolean("enabled"),
                            getInteger(rs, "start_page"),
                            getInteger(rs, "current_page")),
                    id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<TaskExecutionPlan> findExecutionPlan(UUID id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select id,
                           name,
                           data_source_id,
                           status,
                           enabled,
                           start_page,
                           end_page,
                           current_page,
                           filter_type,
                           filter_hours,
                           filter_keywords,
                           fixed_delay_ms,
                           user_agent,
                           enable_random_ua,
                           use_custom_proxy,
                           proxy_type,
                           proxy_host,
                           proxy_port,
                           proxy_username,
                           proxy_password,
                           headers::text
                    from collection_tasks
                    where id = ?
                    """,
                    (rs, rowNum) -> new TaskExecutionPlan(
                            rs.getObject("id", UUID.class),
                            rs.getString("name"),
                            rs.getObject("data_source_id", UUID.class),
                            rs.getString("status"),
                            rs.getBoolean("enabled"),
                            getInteger(rs, "start_page"),
                            getInteger(rs, "end_page"),
                            getInteger(rs, "current_page"),
                            rs.getString("filter_type"),
                            getInteger(rs, "filter_hours"),
                            rs.getString("filter_keywords"),
                            getInteger(rs, "fixed_delay_ms"),
                            rs.getString("user_agent"),
                            rs.getBoolean("enable_random_ua"),
                            rs.getBoolean("use_custom_proxy"),
                            rs.getString("proxy_type"),
                            rs.getString("proxy_host"),
                            getInteger(rs, "proxy_port"),
                            rs.getString("proxy_username"),
                            rs.getString("proxy_password"),
                            rs.getString("headers")),
                    id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<UUID> findActiveTaskIds(int limit) {
        int safeLimit = Math.max(1, Math.min(MAX_PAGE_SIZE, limit));
        return jdbcTemplate.query(
                """
                select id
                from collection_tasks
                where status in ('QUEUED', 'RUNNING')
                order by updated_at asc, id asc
                limit ?
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                safeLimit);
    }

    public Optional<ScheduledTaskDefinition> findScheduledTask(UUID id) {
        return findScheduledTasks().stream()
                .filter(task -> task.id().equals(id))
                .findFirst();
    }

    public List<ScheduledTaskDefinition> findScheduledTasks() {
        return jdbcTemplate.query(
                """
                select id, name, enabled, cron_expression, time_zone
                from collection_tasks
                where enabled = true
                  and cron_expression is not null
                  and btrim(cron_expression) <> ''
                order by name asc, id asc
                """,
                (rs, rowNum) -> new ScheduledTaskDefinition(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getBoolean("enabled"),
                        rs.getString("cron_expression"),
                        rs.getString("time_zone")));
    }

    public String statusOf(UUID id) {
        try {
            return jdbcTemplate.queryForObject(
                    "select status from collection_tasks where id = ?",
                    String.class,
                    id);
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }

    public boolean existsActiveTaskForDataSource(UUID dataSourceId, UUID excludedTaskId) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                select exists(
                    select 1
                    from collection_tasks
                    where data_source_id = ?
                      and id <> ?
                      and status in ('RUNNING', 'QUEUED')
                )
                """,
                Boolean.class,
                dataSourceId,
                excludedTaskId);
        return Boolean.TRUE.equals(exists);
    }

    public void markQueued(TaskRuntimeState task, boolean resume) {
        Timestamp now = Timestamp.from(Instant.now());
        if (resume) {
            int currentPage = effectiveCurrentPage(task.currentPage(), task.startPage());
            jdbcTemplate.update(
                    """
                    update collection_tasks
                    set status = 'QUEUED',
                        last_execution_time = ?,
                        last_error_message = null,
                        current_page = ?,
                        stat_end_time = null,
                        updated_at = ?
                    where id = ?
                    """,
                    now,
                    currentPage,
                    now,
                    task.id());
        } else {
            int currentPage = effectiveStartPage(task.startPage());
            jdbcTemplate.update(
                    """
                    update collection_tasks
                    set status = 'QUEUED',
                        last_execution_time = ?,
                        last_error_message = null,
                        stat_start_time = ?,
                        stat_end_time = null,
                        stat_total_found = 0,
                        stat_processed = 0,
                        stat_success = 0,
                        stat_failed = 0,
                        stat_inserted = 0,
                        stat_updated = 0,
                        stat_ignored = 0,
                        current_page = ?,
                        updated_at = ?
                    where id = ?
                    """,
                    now,
                    now,
                    currentPage,
                    now,
                    task.id());
        }
    }

    public boolean markRunning(UUID id) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbcTemplate.update(
                """
                update collection_tasks
                set status = 'RUNNING',
                    last_execution_time = ?,
                    updated_at = ?
                where id = ?
                  and status = 'QUEUED'
                """,
                now,
                now,
                id) > 0;
    }

    public void complete(UUID id, long processed, long success, long failed) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                """
                update collection_tasks
                set status = 'COMPLETED',
                    last_execution_time = ?,
                    stat_end_time = ?,
                    stat_processed = ?,
                    stat_success = ?,
                    stat_failed = ?,
                    updated_at = ?
                where id = ?
                """,
                now,
                now,
                processed,
                success,
                failed,
                now,
                id);
    }

    public void flushRuntimeLedger(TaskDbRuntimeSnapshot snapshot) {
        Timestamp now = Timestamp.from(snapshot.flushedAt());
        jdbcTemplate.update(
                """
                update collection_tasks
                set status = ?,
                    current_page = ?,
                    stat_total_found = ?,
                    stat_processed = ?,
                    stat_success = ?,
                    stat_failed = ?,
                    stat_start_time = coalesce(stat_start_time, ?),
                    stat_end_time = ?,
                    last_execution_time = ?,
                    last_error_message = ?,
                    updated_at = ?
                where id = ?
                """,
                snapshot.status(),
                snapshot.currentPage(),
                snapshot.totalFound(),
                snapshot.processed(),
                snapshot.success(),
                snapshot.failed(),
                toTimestamp(snapshot.startedAt()),
                toTimestamp(snapshot.endedAt()),
                now,
                truncate(snapshot.lastError()),
                now,
                snapshot.masterTaskId());
    }

    public void fail(UUID id, String errorMessage) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                """
                update collection_tasks
                set status = 'FAILED',
                    last_execution_time = ?,
                    stat_end_time = ?,
                    last_error_message = ?,
                    updated_at = ?
                where id = ?
                """,
                now,
                now,
                truncate(errorMessage),
                now,
                id);
    }

    public void markPaused(UUID id, String message) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                """
                update collection_tasks
                set status = 'PAUSED',
                    last_execution_time = ?,
                    stat_end_time = ?,
                    last_error_message = ?,
                    updated_at = ?
                where id = ?
                """,
                now,
                now,
                truncate(message),
                now,
                id);
    }

    public int recoverInterruptedTasks(String message) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbcTemplate.update(
                """
                update collection_tasks
                set status = 'PAUSED',
                    last_execution_time = ?,
                    stat_end_time = ?,
                    last_error_message = ?,
                    updated_at = ?
                where status in ('QUEUED', 'RUNNING', 'STOPPING')
                """,
                now,
                now,
                message,
                now);
    }

    public int failStaleActiveTasks(Instant threshold, String message) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbcTemplate.update(
                """
                update collection_tasks
                set status = 'FAILED',
                    stat_end_time = ?,
                    last_error_message = ?,
                    updated_at = ?
                where status in ('QUEUED', 'RUNNING')
                  and last_execution_time < ?
                """,
                now,
                message,
                now,
                Timestamp.from(threshold));
    }

    public void pause(UUID id) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                """
                update collection_tasks
                set status = 'PAUSED',
                    stat_end_time = ?,
                    updated_at = ?
                where id = ?
                  and status in ('RUNNING', 'QUEUED')
                """,
                now,
                now,
                id);
    }

    public void stop(UUID id) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                """
                update collection_tasks
                set status = 'STOPPING',
                    updated_at = ?
                where id = ?
                  and status in ('RUNNING', 'QUEUED', 'PAUSED')
                """,
                now,
                id);
    }

    public Page<TaskCardSummary> findAll(
            Pageable pageable,
            String name,
            String status,
            UUID dataSourceId,
            Boolean enabled
    ) {
        return findAll(pageable, name, status, dataSourceId, enabled, null);
    }

    public Page<TaskCardSummary> findAll(
            Pageable pageable,
            String name,
            String status,
            UUID dataSourceId,
            Boolean enabled,
            UUID mediaRequestBatchId
    ) {
        Pageable safePageable = sanitize(pageable);
        List<Object> whereParams = new ArrayList<>();
        String where = buildWhere(name, status, dataSourceId, enabled, mediaRequestBatchId, whereParams);

        Long total = jdbcTemplate.queryForObject(
                "select count(*) from collection_tasks t " + where,
                Long.class,
                whereParams.toArray());

        List<Object> queryParams = new ArrayList<>(whereParams);
        queryParams.add(safePageable.getPageSize());
        queryParams.add(safePageable.getOffset());

        List<TaskCardSummary> content = jdbcTemplate.query(
                """
                select t.id,
                       t.name,
                       t.status,
                       t.enabled,
                       ds.name as data_source_name,
                       t.cron_expression,
                       t.time_zone,
                       t.filter_type,
                       t.filter_hours,
                       t.filter_keywords,
                       t.start_page,
                       t.end_page,
                       t.current_page,
                       t.last_execution_time,
                       t.stat_processed,
                       t.stat_total_found,
                       t.stat_success,
                       t.stat_failed,
                       t.stat_inserted,
                       t.stat_updated,
                       t.stat_ignored,
                       t.stat_start_time,
                       t.stat_end_time
                from collection_tasks t
                left join data_sources ds on t.data_source_id = ds.id
                %s
                order by t.created_at desc nulls last, t.name asc, t.id asc
                limit ? offset ?
                """.formatted(where),
                cardMapper(),
                queryParams.toArray());

        return new PageImpl<>(content, safePageable, total == null ? 0 : total);
    }

    public Optional<TaskDetailSummary> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select t.id,
                           t.name,
                           t.status,
                           t.enabled,
                           t.cron_expression,
                           t.time_zone,
                           t.data_source_id,
                           ds.name as data_source_name,
                           t.task_type,
                           t.start_page,
                           t.end_page,
                           t.current_page,
                           t.filter_type,
                           t.filter_hours,
                           t.filter_keywords,
                           t.request_delay_type,
                           t.fixed_delay_ms,
                           t.random_delay_min_ms,
                           t.random_delay_max_ms,
                           t.timeout_ms,
                           t.max_retries,
                           t.user_agent,
                           t.enable_random_ua,
                           t.use_custom_proxy,
                           t.proxy_type,
                           t.proxy_host,
                           t.proxy_port,
                           t.proxy_username,
                           t.headers,
                           t.last_execution_time,
                           t.created_at,
                           t.updated_at,
                           t.last_error_message,
                           t.stat_total_found,
                           t.stat_processed,
                           t.stat_success,
                           t.stat_failed,
                           t.stat_inserted,
                           t.stat_updated,
                           t.stat_ignored,
                           t.stat_start_time,
                           t.stat_end_time
                    from collection_tasks t
                    left join data_sources ds on t.data_source_id = ds.id
                    where t.id = ?
                    """,
                    detailMapper(),
                    id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private String buildWhere(
            String name,
            String status,
            UUID dataSourceId,
            Boolean enabled,
            UUID mediaRequestBatchId,
            List<Object> params
    ) {
        List<String> predicates = new ArrayList<>();
        if (name != null && !name.isBlank()) {
            predicates.add("lower(t.name) like ?");
            params.add("%" + name.trim().toLowerCase() + "%");
        }
        if (status != null && !status.isBlank()) {
            predicates.add("t.status = ?");
            params.add(status.trim().toUpperCase());
        }
        if (dataSourceId != null) {
            predicates.add("t.data_source_id = ?");
            params.add(dataSourceId);
        }
        if (enabled != null) {
            predicates.add("t.enabled = ?");
            params.add(enabled);
        }
        if (mediaRequestBatchId != null) {
            predicates.add("t.headers -> ? ->> 'batchId' = ?");
            params.add(MEDIA_REQUEST_BATCH_HEADER_KEY);
            params.add(mediaRequestBatchId.toString());
        }
        if (predicates.isEmpty()) {
            return "";
        }
        return "where " + String.join(" and ", predicates);
    }

    private Pageable sanitize(Pageable pageable) {
        int page = Math.max(0, pageable.getPageNumber());
        int requestedSize = pageable.isPaged() ? pageable.getPageSize() : 20;
        int size = Math.min(Math.max(1, requestedSize), MAX_PAGE_SIZE);
        return PageRequest.of(page, size);
    }

    private RowMapper<TaskCardSummary> cardMapper() {
        return (rs, rowNum) -> new TaskCardSummary(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("status"),
                rs.getBoolean("enabled"),
                rs.getString("data_source_name"),
                rs.getString("cron_expression"),
                rs.getString("time_zone"),
                rs.getString("filter_type"),
                getInteger(rs, "filter_hours"),
                rs.getString("filter_keywords"),
                getInteger(rs, "start_page"),
                getInteger(rs, "end_page"),
                getInteger(rs, "current_page"),
                toInstant(rs.getTimestamp("last_execution_time")),
                getLong(rs, "stat_processed"),
                getLong(rs, "stat_total_found"),
                getLong(rs, "stat_success"),
                getLong(rs, "stat_failed"),
                getLong(rs, "stat_inserted"),
                getLong(rs, "stat_updated"),
                getLong(rs, "stat_ignored"),
                toInstant(rs.getTimestamp("stat_start_time")),
                toInstant(rs.getTimestamp("stat_end_time")));
    }

    private RowMapper<TaskDetailSummary> detailMapper() {
        return (rs, rowNum) -> new TaskDetailSummary(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("status"),
                rs.getBoolean("enabled"),
                rs.getString("cron_expression"),
                rs.getString("time_zone"),
                rs.getObject("data_source_id", UUID.class),
                rs.getString("data_source_name"),
                rs.getString("task_type"),
                getInteger(rs, "start_page"),
                getInteger(rs, "end_page"),
                getInteger(rs, "current_page"),
                rs.getString("filter_type"),
                getInteger(rs, "filter_hours"),
                rs.getString("filter_keywords"),
                rs.getString("request_delay_type"),
                getInteger(rs, "fixed_delay_ms"),
                getInteger(rs, "random_delay_min_ms"),
                getInteger(rs, "random_delay_max_ms"),
                getInteger(rs, "timeout_ms"),
                getInteger(rs, "max_retries"),
                rs.getString("user_agent"),
                rs.getBoolean("enable_random_ua"),
                rs.getBoolean("use_custom_proxy"),
                rs.getString("proxy_type"),
                rs.getString("proxy_host"),
                getInteger(rs, "proxy_port"),
                rs.getString("proxy_username"),
                null,
                rs.getString("headers"),
                toInstant(rs.getTimestamp("last_execution_time")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getString("last_error_message"),
                getLong(rs, "stat_total_found"),
                getLong(rs, "stat_processed"),
                getLong(rs, "stat_success"),
                getLong(rs, "stat_failed"),
                getLong(rs, "stat_inserted"),
                getLong(rs, "stat_updated"),
                getLong(rs, "stat_ignored"),
                toInstant(rs.getTimestamp("stat_start_time")),
                toInstant(rs.getTimestamp("stat_end_time")));
    }

    private RowMapper<MediaRequestBatchResponse> mediaRequestBatchMapper(boolean includeItems) {
        return (rs, rowNum) -> {
            UUID batchId = rs.getObject("id", UUID.class);
            return new MediaRequestBatchResponse(
                    batchId,
                    rs.getString("status"),
                    rs.getInt("request_count"),
                    rs.getInt("scheduled_count"),
                    rs.getInt("skipped_count"),
                    rs.getInt("failed_count"),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("updated_at")),
                    toInstant(rs.getTimestamp("started_at")),
                    toInstant(rs.getTimestamp("cancelled_at")),
                    toInstant(rs.getTimestamp("completed_at")),
                    rs.getString("last_error_message"),
                    includeItems ? findMediaRequestBatchItems(batchId) : List.of());
        };
    }

    private List<MediaRequestBatchItemResponse> findMediaRequestBatchItems(UUID batchId) {
        return jdbcTemplate.query(
                """
                select id, media_request_id, title, release_year, request_count, status,
                       existing_video_id, existing_video_source, scheduled_task_count, last_error_message
                  from portal_media_request_batch_items
                 where batch_id = ?
                 order by request_count desc, created_at asc, id asc
                """,
                (rs, rowNum) -> new MediaRequestBatchItemResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("media_request_id", UUID.class),
                        rs.getString("title"),
                        apiReleaseYear(rs.getInt("release_year")),
                        rs.getInt("request_count"),
                        rs.getString("status"),
                        rs.getObject("existing_video_id", UUID.class),
                        rs.getString("existing_video_source"),
                        rs.getInt("scheduled_task_count"),
                        rs.getString("last_error_message")),
                batchId);
    }

    private MediaRequestBatchCounters mediaRequestBatchCounters(UUID batchId) {
        return jdbcTemplate.queryForObject(
                """
                select count(*) filter (where status = 'SCHEDULED') as scheduled,
                       count(*) filter (where status = 'SKIPPED_EXISTS') as skipped,
                       count(*) filter (where status = 'FAILED') as failed
                  from portal_media_request_batch_items
                 where batch_id = ?
                """,
                (rs, rowNum) -> new MediaRequestBatchCounters(
                        rs.getInt("scheduled"),
                        rs.getInt("skipped"),
                        rs.getInt("failed")),
                batchId);
    }

    private Integer getInteger(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Long getLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private void putIfPresent(Map<String, Object> updates, String column, Object value) {
        if (value != null) {
            updates.put(column, value);
        }
    }

    private int effectiveStartPage(Integer startPage) {
        return startPage != null && startPage > 0 ? startPage : 1;
    }

    private int effectiveCurrentPage(Integer currentPage, Integer startPage) {
        return currentPage != null && currentPage > 0 ? currentPage : effectiveStartPage(startPage);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 2000) {
            return value;
        }
        return value.substring(0, 2000);
    }

    private String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(Math.max(1, size), "?"));
    }

    private Integer apiReleaseYear(int releaseYear) {
        return releaseYear <= 0 ? null : releaseYear;
    }

    private Integer dbReleaseYear(Integer releaseYear) {
        return releaseYear == null || releaseYear <= 0 ? 0 : releaseYear;
    }

    private List<Object> prepend(Object first, List<?> rest) {
        List<Object> result = new ArrayList<>();
        result.add(first);
        if (rest != null) {
            result.addAll(rest);
        }
        return result;
    }

    private record MediaRequestBatchCounters(int scheduled, int skipped, int failed) {
    }
}
