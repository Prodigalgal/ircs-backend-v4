package com.prodigalgal.ircs.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.concurrent.VirtualThreadExecutors;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import com.prodigalgal.ircs.contracts.ingestion.IngestionItem;
import com.prodigalgal.ircs.scraper.ScraperDtos.DataSourceRecord;
import com.prodigalgal.ircs.scraper.ScraperDtos.DirectScrapeItem;
import com.prodigalgal.ircs.scraper.ScraperDtos.ListItem;
import com.prodigalgal.ircs.scraper.ScraperDtos.ListPage;
import com.prodigalgal.ircs.scraper.ScraperDtos.ManualScrapeConfigRequest;
import com.prodigalgal.ircs.scraper.ScraperDtos.ScrapeEvent;
import com.prodigalgal.ircs.scraper.ScraperDtos.ScrapedVideoCard;
import com.prodigalgal.ircs.scraper.ScraperDtos.ScrapedVideoDraft;
import com.prodigalgal.ircs.scraper.ScraperDtos.TaskExecutionLog;
import com.prodigalgal.ircs.scraper.ScraperDtos.TaskExecutionRequest;
import com.prodigalgal.ircs.scraper.ScraperDtos.TaskExecutionResult;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@Slf4j
@RequiredArgsConstructor
class ManualScraperService {

    private static final ObjectMapper CARD_OBJECT_MAPPER = new ObjectMapper();

    private final DataSourceRepository dataSourceRepository;
    private final ListScraperClient listScraperClient;
    private final ScraperMappingService mappingService;
    private final IngestionPublisher ingestionPublisher;
    private final RawVideoRefetchRepository rawVideoRefetchRepository;
    private final Executor scraperExecutor;
    private final WorkerJobAuditWriter auditWriter;
    private final ScrapeUpdateWindowFilter updateWindowFilter;
    private final Map<UUID, ManualScrapeConfigRequest> sessions = new ConcurrentHashMap<>();

    @Value("${app.scraper.direct-items-enabled:true}")
    private boolean directItemsEnabled = true;

    UUID initSession(ManualScrapeConfigRequest request) {
        UUID sessionId = IrcsUuidGenerators.nextId();
        sessions.put(sessionId, request);
        return sessionId;
    }

    void refetchRawVideo(UUID rawVideoId) {
        Instant acceptedAt = Instant.now();
        RawVideoRefetchRepository.RawVideoRefetchTarget target = rawVideoRefetchRepository.findTarget(rawVideoId)
                .orElseThrow(() -> {
                    IllegalArgumentException error = new IllegalArgumentException("Raw video not found: " + rawVideoId);
                    recordFailed("scraper.refetch-raw-video", rawVideoId, acceptedAt, error);
                    return error;
                });
        if (target.dataSourceId() == null) {
            IllegalStateException error = new IllegalStateException("Raw video has no linked data source: " + rawVideoId);
            recordFailed("scraper.refetch-raw-video", rawVideoId, acceptedAt, error);
            throw error;
        }
        DataSourceRecord source = dataSourceRepository.findById(target.dataSourceId())
                .orElseThrow(() -> {
                    IllegalStateException error = new IllegalStateException("Data source not found: " + target.dataSourceId());
                    recordFailed("scraper.refetch-raw-video", rawVideoId, acceptedAt, error);
                    return error;
                });
        ManualScrapeConfigRequest request = new ManualScrapeConfigRequest(
                "refetch",
                null,
                null,
                1,
                1,
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                true,
                null);
        CompletableFuture.runAsync(() -> {
            Instant startedAt = Instant.now();
            try {
                String detailJson = listScraperClient.fetchDetail(source, request, target.sourceVid());
                ScrapedVideoDraft draft = mappingService.mapDetail(detailJson, source);
                ingestionPublisher.publish(mappingService.toItem(draft, true));
                recordSucceeded("scraper.refetch-raw-video", rawVideoId, startedAt);
            } catch (RuntimeException ex) {
                recordFailed("scraper.refetch-raw-video", rawVideoId, startedAt,
                        new ManualScraperAuditException("refetch execution failed"));
                throw ex;
            }
        }, scraperExecutor);
    }

    TaskExecutionResult executeCollectionTask(TaskExecutionRequest request) {
        Instant startedAt = Instant.now();
        if (request == null || request.taskId() == null || request.dataSourceId() == null) {
            recordFailed("scraper.collection-task", request == null ? null : request.taskId(), startedAt,
                    new ManualScraperAuditException("taskId and dataSourceId are required"));
            return new TaskExecutionResult("FAILED", 0, 1, List.of(log("ERROR", "SYSTEM", "taskId and dataSourceId are required")));
        }
        ManualScrapeConfigRequest config = request.toManualRequest();
        List<TaskExecutionLog> logs = new ArrayList<>();
        int published = 0;
        int failed = 0;
        try {
            DataSourceRecord source = dataSourceRepository.findById(request.dataSourceId())
                    .orElseThrow(() -> new IllegalArgumentException("Data source not found: " + request.dataSourceId()));
            logs.add(log("INFO", "SYSTEM", "Task execution accepted by scraper-service"));
            if (!CollectionUtils.isEmpty(config.directItems())) {
                if (!directItemsEnabled) {
                    throw new IllegalStateException("directItems are disabled");
                }
                for (DirectScrapeItem item : config.directItems()) {
                    try {
                        IngestionItem ingestionItem = mappingService.directItem(item, source, config.forceIngest());
                        ingestionPublisher.publish(ingestionItem);
                        published++;
                        logs.add(log("INFO", ingestionItem.video().sourceVid(), "Published direct task item"));
                    } catch (Exception ex) {
                        failed++;
                        logs.add(log("ERROR", item.sourceVid(), "Direct item failed: " + ex.getMessage()));
                    }
                }
            } else {
                TaskExecutionCounters counters = executeDataSource(source, config, logs);
                published += counters.published();
                failed += counters.failed();
            }
            String status = failed == 0 ? "COMPLETED" : "FAILED";
            logs.add(log(failed == 0 ? "INFO" : "WARN", "SYSTEM",
                    "Task execution finished: published=" + published + ", failed=" + failed));
            TaskExecutionResult result = new TaskExecutionResult(status, published, failed, logs);
            recordTaskExecutionAudit(request.taskId(), startedAt, result);
            return result;
        } catch (Exception ex) {
            logs.add(log("ERROR", "SYSTEM", "Task execution failed: " + ex.getMessage()));
            TaskExecutionResult result = new TaskExecutionResult("FAILED", published, failed + 1, logs);
            recordFailed("scraper.collection-task", request.taskId(), startedAt,
                    new ManualScraperAuditException("task execution failed"));
            return result;
        }
    }

    SseEmitter stream(UUID sessionId) {
        ManualScrapeConfigRequest request = sessions.remove(sessionId);
        if (request == null) {
            throw new IllegalArgumentException("Session expired or invalid");
        }
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        CompletableFuture.runAsync(() -> run(request, emitter), scraperExecutor);
        return emitter;
    }

    private void run(ManualScrapeConfigRequest request, SseEmitter emitter) {
        try {
            List<DataSourceRecord> dataSources = dataSourceRepository.findAll();
            send(emitter, ScrapeEvent.log("任务初始化... (Mode: V3 MVP queue producer)"));
            send(emitter, ScrapeEvent.log("搜索关键词: [" + request.keyword() + "]"));
            send(emitter, ScrapeEvent.log("范围: 第 " + request.effectiveStartPage() + " - " + request.effectiveEndPage() + " 页"));
            send(emitter, ScrapeEvent.log("共加载 " + dataSources.size() + " 个数据源。"));

            if (!CollectionUtils.isEmpty(request.directItems())) {
                publishDirectItems(request, dataSources, emitter);
            } else {
                for (DataSourceRecord source : dataSources) {
                    processDataSource(source, request, emitter);
                }
            }

            send(emitter, ScrapeEvent.log("=== 所有任务执行完毕 ==="));
            send(emitter, ScrapeEvent.done());
            emitter.complete();
        } catch (Exception e) {
            try {
                send(emitter, ScrapeEvent.error("系统内部错误: " + e.getMessage()));
            } catch (IOException ignored) {
            }
            emitter.completeWithError(e);
        }
    }

    private void publishDirectItems(ManualScrapeConfigRequest request, List<DataSourceRecord> dataSources,
            SseEmitter emitter) throws IOException {
        if (!directItemsEnabled) {
            throw new IllegalStateException("directItems are disabled");
        }
        DataSourceRecord fallback = dataSources.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No data source available for direct smoke item"));
        for (DirectScrapeItem item : request.directItems()) {
            DataSourceRecord source = item.dataSourceId() == null
                    ? fallback
                    : dataSourceRepository.findById(item.dataSourceId()).orElse(fallback);
            IngestionItem ingestionItem = mappingService.directItem(item, source, request.forceIngest());
            ingestionPublisher.publish(ingestionItem);
            send(emitter, ScrapeEvent.card(card(source, ingestionItem, "PUBLISHED", null, null)));
        }
    }

    private void processDataSource(DataSourceRecord source, ManualScrapeConfigRequest request, SseEmitter emitter)
            throws IOException {
        send(emitter, ScrapeEvent.log("[" + source.name() + "] 开始搜索..."));
        for (int page = request.effectiveStartPage(); page <= request.effectiveEndPage(); page++) {
            ScrapeUpdateWindowFilter.FilteredListPage filteredPage = updateWindowFilter.filter(
                    listScraperClient.fetchListPage(source, request, page),
                    page,
                    request.filterHours());
            ListPage pageResult = filteredPage.page();
            List<ListItem> items = pageResult.items();
            if (pageResult.hasPagination()) {
                send(emitter, ScrapeEvent.log("[" + source.name() + "] 第 " + page
                        + " 页 pagination: totalPages=" + valueOrUnknown(pageResult.totalPages())
                        + ", totalItems=" + valueOrUnknown(pageResult.totalItems())));
            }
            if (items.isEmpty()) {
                String message = filteredPage.exhausted()
                        ? "[" + source.name() + "] 最近更新窗口已结束，停止该源。"
                        : "[" + source.name() + "] 第 " + page + " 页未找到数据，停止该源。";
                send(emitter, ScrapeEvent.log(message));
                break;
            }
            for (ListItem item : items) {
                try {
                    String detailJson = listScraperClient.fetchDetail(source, request, item.id());
                    ScrapedVideoDraft draft = mappingService.mapDetail(detailJson, source);
                    IngestionItem ingestionItem = mappingService.toItem(draft, request.forceIngest());
                    ingestionPublisher.publish(ingestionItem);
                    send(emitter, ScrapeEvent.card(card(source, ingestionItem, "PUBLISHED", null, null)));
                } catch (Exception e) {
                    send(emitter, ScrapeEvent.error("[" + source.name() + "] 处理失败 (VID:" + item.id() + "): " + e.getMessage()));
                }
                delay(request.fixedDelayMs());
            }
            if (filteredPage.exhausted()) {
                send(emitter, ScrapeEvent.log("[" + source.name() + "] 最近更新窗口已结束，停止该源。"));
                break;
            }
            if (pageResult.totalPages() != null && page >= pageResult.totalPages()) {
                break;
            }
        }
        send(emitter, ScrapeEvent.log("[" + source.name() + "] 处理完成。"));
    }

    private TaskExecutionCounters executeDataSource(
            DataSourceRecord source,
            ManualScrapeConfigRequest request,
            List<TaskExecutionLog> logs) {
        int published = 0;
        int failed = 0;
        logs.add(log("INFO", "SYSTEM", "[" + source.name() + "] start"));
        for (int page = request.effectiveStartPage(); page <= request.effectiveEndPage(); page++) {
            ScrapeUpdateWindowFilter.FilteredListPage filteredPage = updateWindowFilter.filter(
                    listScraperClient.fetchListPage(source, request, page),
                    page,
                    request.filterHours());
            ListPage pageResult = filteredPage.page();
            List<ListItem> items = pageResult.items();
            if (pageResult.hasPagination()) {
                logs.add(log("INFO", "PAGE-" + page, "[" + source.name() + "] pagination totalPages="
                        + valueOrUnknown(pageResult.totalPages()) + ", totalItems=" + valueOrUnknown(pageResult.totalItems())));
            }
            if (items.isEmpty()) {
                String message = filteredPage.exhausted()
                        ? "[" + source.name() + "] update window exhausted, stop source"
                        : "[" + source.name() + "] empty page, stop source";
                logs.add(log("INFO", "PAGE-" + page, message));
                break;
            }
            for (ListItem item : items) {
                try {
                    String detailJson = listScraperClient.fetchDetail(source, request, item.id());
                    ScrapedVideoDraft draft = mappingService.mapDetail(detailJson, source);
                    IngestionItem ingestionItem = mappingService.toItem(draft, request.forceIngest());
                    ingestionPublisher.publish(ingestionItem);
                    published++;
                    logs.add(log("INFO", item.id(), "Published task item"));
                } catch (Exception e) {
                    failed++;
                    logs.add(log("ERROR", item.id(), "Task item failed: " + e.getMessage()));
                }
                delay(request.fixedDelayMs());
            }
            if (filteredPage.exhausted()) {
                logs.add(log("INFO", "PAGE-" + page, "[" + source.name() + "] update window exhausted, stop source"));
                break;
            }
            if (pageResult.totalPages() != null && page >= pageResult.totalPages()) {
                break;
            }
        }
        logs.add(log("INFO", "SYSTEM", "[" + source.name() + "] done"));
        return new TaskExecutionCounters(published, failed);
    }

    private String valueOrUnknown(Integer value) {
        return value == null ? "unknown" : value.toString();
    }

    ScrapedVideoCard card(DataSourceRecord source, IngestionItem item, String status, String error, String detailUrl) {
        return new ScrapedVideoCard(
                source.name(),
                item.video().title(),
                item.video().coverImageUrl(),
                null,
                item.video().sourceVid(),
                status,
                error,
                item.video().year(),
                cardCategory(item),
                detailUrl);
    }

    private String cardCategory(IngestionItem item) {
        if (item == null || item.video() == null || !org.springframework.util.StringUtils.hasText(item.video().rawMetadata())) {
            return null;
        }
        try {
            JsonNode root = CARD_OBJECT_MAPPER.readTree(item.video().rawMetadata());
            return firstText(root,
                    "rawTypeName",
                    "categoryName",
                    "typeName",
                    "raw_type_name",
                    "category_name",
                    "type_name",
                    "rawTypeId",
                    "categoryId",
                    "typeId",
                    "raw_type_id",
                    "category_id",
                    "type_id");
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstText(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode value = root.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.isTextual() ? value.asText() : value.asText(value.toString());
                if (org.springframework.util.StringUtils.hasText(text)) {
                    return text.trim();
                }
            }
        }
        JsonNode sourcePayload = root.path("sourcePayload");
        if (sourcePayload.isObject()) {
            return firstText(sourcePayload, fields);
        }
        return null;
    }

    private void send(SseEmitter emitter, ScrapeEvent event) throws IOException {
        emitter.send(event);
    }

    private TaskExecutionLog log(String level, String sourceVid, String message) {
        return new TaskExecutionLog(Instant.now().toString(), level, sourceVid, message);
    }

    private void delay(Integer fixedDelayMs) {
        int delay = fixedDelayMs == null ? 0 : fixedDelayMs;
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(Math.min(delay, 5000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void recordTaskExecutionAudit(UUID taskId, Instant startedAt, TaskExecutionResult result) {
        if ("COMPLETED".equals(result.status())) {
            recordSucceeded("scraper.collection-task", taskId, startedAt);
            return;
        }
        recordFailed("scraper.collection-task", taskId, startedAt,
                new ManualScraperAuditException("task execution completed with item failures"));
    }

    private void recordSucceeded(String jobName, UUID correlationId, Instant startedAt) {
        recordAudit(WorkerJobAuditEvent.succeeded(
                "runner",
                jobName,
                correlationId(correlationId),
                elapsedSince(startedAt)));
    }

    private void recordFailed(String jobName, UUID correlationId, Instant startedAt, RuntimeException error) {
        recordAudit(WorkerJobAuditEvent.failed(
                "runner",
                jobName,
                correlationId(correlationId),
                elapsedSince(startedAt),
                error));
    }

    private void recordAudit(WorkerJobAuditEvent event) {
        try {
            auditWriter.record(event);
        } catch (RuntimeException ex) {
            log.warn("Manual scraper audit write failed: {}", ex.getMessage());
        }
    }

    private static String correlationId(UUID id) {
        return id == null ? null : id.toString();
    }

    private static Duration elapsedSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }

    @Bean
    static Executor scraperExecutor() {
        return new BoundedVirtualThreadExecutor("scraper-mvp-", 2);
    }

    private static final class BoundedVirtualThreadExecutor implements Executor {
        private final Semaphore permits;
        private final java.util.concurrent.ThreadFactory threadFactory;

        private BoundedVirtualThreadExecutor(String threadNamePrefix, int concurrency) {
            this.permits = new Semaphore(Math.max(1, concurrency));
            this.threadFactory = VirtualThreadExecutors.threadFactory(threadNamePrefix);
        }

        @Override
        public void execute(Runnable command) {
            try {
                permits.acquire();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("interrupted while waiting for scraper executor permit", ex);
            }
            threadFactory.newThread(() -> {
                try {
                    command.run();
                } finally {
                    permits.release();
                }
            }).start();
        }
    }

    private record TaskExecutionCounters(int published, int failed) {
    }

    private static class ManualScraperAuditException extends RuntimeException {
        ManualScraperAuditException(String message) {
            super(message);
        }
    }
}
