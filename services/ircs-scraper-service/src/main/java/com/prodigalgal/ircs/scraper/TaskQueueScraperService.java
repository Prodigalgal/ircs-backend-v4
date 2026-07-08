package com.prodigalgal.ircs.scraper;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import com.prodigalgal.ircs.contracts.ingestion.IngestionItem;
import com.prodigalgal.ircs.contracts.task.TaskDetailDoneMessage;
import com.prodigalgal.ircs.contracts.task.TaskDetailMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageDiscoveredMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageMessage;
import com.prodigalgal.ircs.contracts.task.TaskScrapeOptions;
import com.prodigalgal.ircs.scraper.ScraperDtos.DataSourceRecord;
import com.prodigalgal.ircs.scraper.ScraperDtos.ListItem;
import com.prodigalgal.ircs.scraper.ScraperDtos.ListPage;
import com.prodigalgal.ircs.scraper.ScraperDtos.ManualScrapeConfigRequest;
import com.prodigalgal.ircs.scraper.ScraperDtos.ScrapedVideoDraft;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
class TaskQueueScraperService {

    private static final String JOB_TYPE_QUEUE_CONSUMER = "queue-consumer";
    private static final String JOB_NAME_PAGE = "scraper.task-page";
    private static final String JOB_NAME_DETAIL = "scraper.task-detail";

    private final DataSourceRepository dataSourceRepository;
    private final ListScraperClient listScraperClient;
    private final ScraperMappingService mappingService;
    private final IngestionPublisher ingestionPublisher;
    private final ScraperTaskQueuePublisher queuePublisher;
    private final TaskRuntimeControlService runtimeControlService;
    private final WorkerJobAuditWriter auditWriter;
    private final ScrapeUpdateWindowFilter updateWindowFilter;

    void processPage(TaskPageMessage message) {
        Instant startedAt = Instant.now();
        if (!runtimeControlService.canRun(message.masterTaskId())) {
            recordSkipped(JOB_NAME_PAGE, message.correlationId(), startedAt);
            return;
        }
        try {
            DataSourceRecord source = source(message.dataSourceId());
            ManualScrapeConfigRequest config = config(message.options(), message.pageNumber(), false);
            ScrapeUpdateWindowFilter.FilteredListPage filteredPage = updateWindowFilter.filter(
                    listScraperClient.fetchListPage(source, config, message.pageNumber()),
                    message.pageNumber(),
                    filterHours(message.options()));
            ListPage page = filteredPage.page();
            for (ListItem item : page.items()) {
                queuePublisher.publishDetail(new TaskDetailMessage(
                        message.masterTaskId(),
                        message.pageTaskId(),
                        IrcsUuidGenerators.nextId(),
                        message.dataSourceId(),
                        item.id(),
                        null,
                        0,
                        idempotencyKey(message.masterTaskId(), message.pageTaskId(), item.id()),
                        message.options(),
                        message.correlationId(),
                        Instant.now()));
            }
            queuePublisher.publishPageDiscovered(new TaskPageDiscoveredMessage(
                    message.masterTaskId(),
                    message.pageTaskId(),
                    message.pageNumber(),
                    page.items().size(),
                    page.totalPages(),
                    page.totalItems(),
                    message.correlationId(),
                    Instant.now()));
            recordSucceeded(JOB_NAME_PAGE, message.correlationId(), startedAt);
        } catch (RuntimeException ex) {
            recordFailed(JOB_NAME_PAGE, message == null ? null : message.correlationId(), startedAt,
                    new TaskQueueScraperException("page task failed", ex));
            throw ex;
        }
    }

    void processDetail(TaskDetailMessage message) {
        Instant startedAt = Instant.now();
        if (!runtimeControlService.canRun(message.masterTaskId())) {
            recordSkipped(JOB_NAME_DETAIL, message.correlationId(), startedAt);
            return;
        }
        try {
            DataSourceRecord source = source(message.dataSourceId());
            ManualScrapeConfigRequest config = config(message.options(), 1, forceIngest(message.options()));
            String detailJson = listScraperClient.fetchDetail(source, config, message.sourceVid());
            ScrapedVideoDraft draft = mappingService.mapDetail(detailJson, source);
            IngestionItem item = mappingService.toItem(draft, forceIngest(message.options()));
            ingestionPublisher.publish(item);
            queuePublisher.publishDetailDone(new TaskDetailDoneMessage(
                    message.masterTaskId(),
                    message.pageTaskId(),
                    message.detailTaskId(),
                    message.sourceVid(),
                    true,
                    null,
                    message.correlationId(),
                    Instant.now()));
            recordSucceeded(JOB_NAME_DETAIL, message.correlationId(), startedAt);
        } catch (RuntimeException ex) {
            recordFailed(JOB_NAME_DETAIL, message == null ? null : message.correlationId(), startedAt,
                    new TaskQueueScraperException("detail task failed", ex));
            throw ex;
        }
    }

    private DataSourceRecord source(UUID dataSourceId) {
        return dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new IllegalArgumentException("Data source not found: " + dataSourceId));
    }

    private ManualScrapeConfigRequest config(TaskScrapeOptions options, int page, boolean forceIngest) {
        TaskScrapeOptions safe = options == null ? defaults() : options;
        return new ManualScrapeConfigRequest(
                blankToNull(safe.keyword()),
                blankToNull(safe.filterType()),
                safe.filterHours(),
                page,
                page,
                safe.userAgent(),
                safe.enableRandomUa(),
                safe.useCustomProxy(),
                safe.proxyType(),
                safe.proxyHost(),
                safe.proxyPort(),
                safe.proxyUsername(),
                safe.proxyPassword(),
                safe.headers(),
                safe.fixedDelayMs(),
                forceIngest,
                null);
    }

    private TaskScrapeOptions defaults() {
        return new TaskScrapeOptions(
                null,
                null,
                null,
                null,
                true,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                false);
    }

    private boolean forceIngest(TaskScrapeOptions options) {
        return options != null && options.forceIngest();
    }

    private Integer filterHours(TaskScrapeOptions options) {
        return options == null ? null : options.filterHours();
    }

    private String blankToNull(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword;
    }

    private String idempotencyKey(UUID masterTaskId, UUID pageTaskId, String sourceVid) {
        return masterTaskId + ":" + pageTaskId + ":" + sourceVid;
    }

    private void recordSucceeded(String jobName, String correlationId, Instant startedAt) {
        recordAudit(WorkerJobAuditEvent.succeeded(
                JOB_TYPE_QUEUE_CONSUMER,
                jobName,
                correlationId,
                elapsedSince(startedAt)));
    }

    private void recordFailed(String jobName, String correlationId, Instant startedAt, RuntimeException error) {
        recordAudit(WorkerJobAuditEvent.failed(
                JOB_TYPE_QUEUE_CONSUMER,
                jobName,
                correlationId,
                elapsedSince(startedAt),
                error));
    }

    private void recordSkipped(String jobName, String correlationId, Instant startedAt) {
        recordAudit(new WorkerJobAuditEvent(
                JOB_TYPE_QUEUE_CONSUMER,
                jobName,
                correlationId,
                "skipped",
                elapsedSince(startedAt),
                null));
    }

    private void recordAudit(WorkerJobAuditEvent event) {
        try {
            auditWriter.record(event);
        } catch (RuntimeException ex) {
            log.warn("Task queue scraper audit write failed: {}", ex.getMessage());
        }
    }

    private Duration elapsedSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }

    private static class TaskQueueScraperException extends RuntimeException {
        TaskQueueScraperException(String message, RuntimeException cause) {
            super(message + rootCauseSummary(cause), cause);
        }

        private static String rootCauseSummary(Throwable cause) {
            Throwable current = cause;
            Throwable root = cause;
            while (current != null) {
                root = current;
                current = current.getCause();
            }
            if (root == null || root.getMessage() == null || root.getMessage().isBlank()) {
                return "";
            }
            return ": " + root.getMessage();
        }
    }
}
