package com.prodigalgal.ircs.scraper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.contracts.ingestion.IngestionItem;
import com.prodigalgal.ircs.scraper.ScraperDtos.DataSourceRecord;
import com.prodigalgal.ircs.scraper.ScraperDtos.DirectScrapeItem;
import com.prodigalgal.ircs.scraper.ScraperDtos.ListItem;
import com.prodigalgal.ircs.scraper.ScraperDtos.ListPage;
import com.prodigalgal.ircs.scraper.ScraperDtos.TaskExecutionRequest;
import java.util.Map;

class ManualScraperServiceTaskExecutionTest {

    private final DataSourceRepository dataSourceRepository = org.mockito.Mockito.mock(DataSourceRepository.class);
    private final ListScraperClient listScraperClient = org.mockito.Mockito.mock(ListScraperClient.class);
    private final ScraperMappingService mappingService = new ScraperMappingService(new ObjectMapper());
    private final IngestionPublisher ingestionPublisher = org.mockito.Mockito.mock(IngestionPublisher.class);
    private final RawVideoRefetchRepository rawVideoRefetchRepository = org.mockito.Mockito.mock(RawVideoRefetchRepository.class);
    private final WorkerJobAuditWriter auditWriter = org.mockito.Mockito.mock(WorkerJobAuditWriter.class);
    private final ManualScraperService service = new ManualScraperService(
            dataSourceRepository,
            listScraperClient,
            mappingService,
            ingestionPublisher,
            rawVideoRefetchRepository,
            Runnable::run,
            auditWriter,
            new ScrapeUpdateWindowFilter(null));

    @Test
    void scraperExecutorRunsTasksOnVirtualThreads() throws Exception {
        Executor executor = ManualScraperService.scraperExecutor();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean virtualThread = new AtomicBoolean(false);

        executor.execute(() -> {
            virtualThread.set(Thread.currentThread().isVirtual());
            completed.countDown();
        });

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertTrue(virtualThread.get());
    }

    @Test
    void executesDirectItemsForTaskRunnerContract() {
        UUID taskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        when(dataSourceRepository.findById(dataSourceId)).thenReturn(Optional.of(new DataSourceRecord(
                dataSourceId,
                "Codex source",
                "https://example.invalid",
                "/list",
                "{}",
                "/detail/{id}",
                "{}",
                "{}")));
        DirectScrapeItem item = new DirectScrapeItem(
                null,
                "codex-source-vid",
                "Codex video",
                null,
                null,
                null,
                "2026",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "{}",
                List.of());

        var result = service.executeCollectionTask(new TaskExecutionRequest(
                taskId,
                dataSourceId,
                "codex",
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
                "{}",
                0,
                true,
                List.of(item)));

        assertEquals("COMPLETED", result.status());
        assertEquals(1, result.publishedCount());
        assertEquals(0, result.failedCount());
        verify(ingestionPublisher).publish(org.mockito.ArgumentMatchers.any(IngestionItem.class));
        WorkerJobAuditEvent event = captureAuditEvent();
        org.assertj.core.api.Assertions.assertThat(event.jobType()).isEqualTo("runner");
        org.assertj.core.api.Assertions.assertThat(event.jobName()).isEqualTo("scraper.collection-task");
        org.assertj.core.api.Assertions.assertThat(event.correlationId()).isEqualTo(taskId.toString());
        org.assertj.core.api.Assertions.assertThat(event.status()).isEqualTo("succeeded");
        org.assertj.core.api.Assertions.assertThat(event.error()).isNull();
    }

    @Test
    void manualScraperCardShowsMappedCategoryInsteadOfRemarks() {
        UUID dataSourceId = UUID.randomUUID();
        DataSourceRecord source = new DataSourceRecord(
                dataSourceId,
                "Codex source",
                "https://example.invalid",
                "/list",
                "{}",
                "/detail/{id}",
                "{}",
                "{}");
        DirectScrapeItem item = new DirectScrapeItem(
                null,
                "codex-card-vid",
                "Codex card",
                null,
                null,
                null,
                "2026",
                null,
                null,
                "更新至 8 集",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                """
                        {
                          "categoryName": "电视剧"
                        }
                        """,
                List.of());

        IngestionItem ingestionItem = mappingService.directItem(item, source, false);
        ScraperDtos.ScrapedVideoCard card = service.card(source, ingestionItem, "PUBLISHED", null, null);

        assertEquals("电视剧", card.category());
    }

    @Test
    void taskExecutionLogsPaginationTotalsFromListPage() {
        UUID taskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        DataSourceRecord source = new DataSourceRecord(
                dataSourceId,
                "Codex source",
                "https://example.invalid",
                "/list",
                "{}",
                "/detail/{id}",
                "{}",
                """
                        {
                          "detail_mapping": {
                            "source_vid": { "path": "$.list[0].vod_id" },
                            "title": { "path": "$.list[0].vod_name" }
                          }
                        }
                        """);
        when(dataSourceRepository.findById(dataSourceId)).thenReturn(Optional.of(source));
        when(listScraperClient.fetchListPage(eq(source), any(), eq(1))).thenReturn(new ListPage(
                List.of(new ListItem("v-page", "2026-06-10", Map.of())),
                9,
                88));
        when(listScraperClient.fetchDetail(eq(source), any(), eq("v-page"))).thenReturn("""
                {"list":[{"vod_id":"v-page","vod_name":"Codex paged video"}]}
                """);

        var result = service.executeCollectionTask(new TaskExecutionRequest(
                taskId,
                dataSourceId,
                "codex",
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
                "{}",
                0,
                true,
                List.of()));

        assertEquals("COMPLETED", result.status());
        assertEquals(1, result.publishedCount());
        assertTrue(result.logs().stream()
                .anyMatch(log -> log.message().contains("pagination totalPages=9, totalItems=88")));
        verify(ingestionPublisher).publish(org.mockito.ArgumentMatchers.any(IngestionItem.class));
        org.assertj.core.api.Assertions.assertThat(captureAuditEvent().status()).isEqualTo("succeeded");
    }

    @Test
    void writesSucceededAuditForRawVideoRefetchRunner() {
        UUID rawVideoId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        DataSourceRecord source = new DataSourceRecord(
                dataSourceId,
                "Codex source",
                "https://example.invalid",
                "/list",
                "{}",
                "/detail/{id}",
                "{}",
                """
                        {
                          "detail_mapping": {
                            "source_vid": { "path": "$.list[0].vod_id" },
                            "title": { "path": "$.list[0].vod_name" }
                          }
                        }
                        """);
        when(rawVideoRefetchRepository.findTarget(rawVideoId))
                .thenReturn(Optional.of(new RawVideoRefetchRepository.RawVideoRefetchTarget(rawVideoId, "v-refetch", dataSourceId)));
        when(dataSourceRepository.findById(dataSourceId)).thenReturn(Optional.of(source));
        when(listScraperClient.fetchDetail(eq(source), any(), eq("v-refetch"))).thenReturn("""
                {"list":[{"vod_id":"v-refetch","vod_name":"Codex refetched video"}]}
                """);

        service.refetchRawVideo(rawVideoId);

        verify(ingestionPublisher).publish(org.mockito.ArgumentMatchers.any(IngestionItem.class));
        WorkerJobAuditEvent event = captureAuditEvent();
        org.assertj.core.api.Assertions.assertThat(event.jobType()).isEqualTo("runner");
        org.assertj.core.api.Assertions.assertThat(event.jobName()).isEqualTo("scraper.refetch-raw-video");
        org.assertj.core.api.Assertions.assertThat(event.correlationId()).isEqualTo(rawVideoId.toString());
        org.assertj.core.api.Assertions.assertThat(event.status()).isEqualTo("succeeded");
    }

    @Test
    void writesFailedAuditWithStableErrorForTaskRunnerFailure() {
        UUID taskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        when(dataSourceRepository.findById(dataSourceId)).thenReturn(Optional.empty());

        var result = service.executeCollectionTask(new TaskExecutionRequest(
                taskId,
                dataSourceId,
                "codex",
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
                "{}",
                0,
                true,
                List.of()));

        assertEquals("FAILED", result.status());
        WorkerJobAuditEvent event = captureAuditEvent();
        org.assertj.core.api.Assertions.assertThat(event.jobName()).isEqualTo("scraper.collection-task");
        org.assertj.core.api.Assertions.assertThat(event.correlationId()).isEqualTo(taskId.toString());
        org.assertj.core.api.Assertions.assertThat(event.status()).isEqualTo("failed");
        org.assertj.core.api.Assertions.assertThat(event.error()).hasMessage("task execution failed");
        org.assertj.core.api.Assertions.assertThat(event.error().getMessage()).doesNotContain(dataSourceId.toString());
    }

    private WorkerJobAuditEvent captureAuditEvent() {
        ArgumentCaptor<WorkerJobAuditEvent> captor = ArgumentCaptor.forClass(WorkerJobAuditEvent.class);
        verify(auditWriter).record(captor.capture());
        return captor.getValue();
    }
}
