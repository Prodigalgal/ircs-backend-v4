package com.prodigalgal.ircs.scraper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.contracts.ingestion.IngestionItem;
import com.prodigalgal.ircs.contracts.ingestion.IngestionVideoDTO;
import com.prodigalgal.ircs.contracts.task.TaskDetailDoneMessage;
import com.prodigalgal.ircs.contracts.task.TaskDetailMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageDiscoveredMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageMessage;
import com.prodigalgal.ircs.contracts.task.TaskScrapeOptions;
import com.prodigalgal.ircs.scraper.ScraperDtos.DataSourceRecord;
import com.prodigalgal.ircs.scraper.ScraperDtos.ListItem;
import com.prodigalgal.ircs.scraper.ScraperDtos.ListPage;
import com.prodigalgal.ircs.scraper.ScraperDtos.ScrapedVideoDraft;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class TaskQueueScraperServiceTest {

    private final DataSourceRepository dataSourceRepository = org.mockito.Mockito.mock(DataSourceRepository.class);
    private final ListScraperClient listScraperClient = org.mockito.Mockito.mock(ListScraperClient.class);
    private final ScraperMappingService mappingService = org.mockito.Mockito.mock(ScraperMappingService.class);
    private final IngestionPublisher ingestionPublisher = org.mockito.Mockito.mock(IngestionPublisher.class);
    private final ScraperTaskQueuePublisher queuePublisher = org.mockito.Mockito.mock(ScraperTaskQueuePublisher.class);
    private final TaskRuntimeControlService runtimeControlService = org.mockito.Mockito.mock(
            TaskRuntimeControlService.class,
            invocation -> invocation.getMethod().getReturnType().equals(boolean.class)
                    ? true
                    : org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation));
    private final WorkerJobAuditWriter auditWriter = org.mockito.Mockito.mock(WorkerJobAuditWriter.class);
    private final ScrapeUpdateWindowFilter updateWindowFilter = new ScrapeUpdateWindowFilter(
            clockProvider(Clock.fixed(Instant.parse("2026-06-13T12:00:00Z"), ZoneOffset.UTC)));
    private final TaskQueueScraperService service = new TaskQueueScraperService(
            dataSourceRepository,
            listScraperClient,
            mappingService,
            ingestionPublisher,
            queuePublisher,
            runtimeControlService,
            auditWriter,
            updateWindowFilter);

    @Test
    void pageTaskSplitsDetailTasksWithoutFetchingDetailsInline() {
        UUID masterTaskId = UUID.randomUUID();
        UUID pageTaskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        DataSourceRecord source = source(dataSourceId);
        when(dataSourceRepository.findById(dataSourceId)).thenReturn(Optional.of(source));
        when(listScraperClient.fetchListPage(eq(source), any(), eq(2))).thenReturn(new ListPage(
                List.of(
                        new ListItem("v-1", "2026-06-13", Map.of()),
                        new ListItem("v-2", "2026-06-13", Map.of())),
                9,
                88));

        service.processPage(new TaskPageMessage(
                masterTaskId,
                pageTaskId,
                dataSourceId,
                2,
                false,
                0,
                options(),
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z")));

        ArgumentCaptor<TaskDetailMessage> detailCaptor = ArgumentCaptor.forClass(TaskDetailMessage.class);
        verify(queuePublisher, org.mockito.Mockito.times(2)).publishDetail(detailCaptor.capture());
        assertThat(detailCaptor.getAllValues())
                .extracting(TaskDetailMessage::sourceVid)
                .containsExactly("v-1", "v-2");
        assertThat(detailCaptor.getAllValues())
                .allSatisfy(message -> {
                    assertThat(message.masterTaskId()).isEqualTo(masterTaskId);
                    assertThat(message.pageTaskId()).isEqualTo(pageTaskId);
                    assertThat(message.idempotencyKey()).contains(message.sourceVid());
                    assertThat(message.options().keyword()).isEqualTo("codex");
                });
        ArgumentCaptor<TaskPageDiscoveredMessage> discoveredCaptor =
                ArgumentCaptor.forClass(TaskPageDiscoveredMessage.class);
        verify(queuePublisher).publishPageDiscovered(discoveredCaptor.capture());
        assertThat(discoveredCaptor.getValue().detailScheduled()).isEqualTo(2);
        assertThat(discoveredCaptor.getValue().totalPages()).isEqualTo(9);
        assertThat(discoveredCaptor.getValue().totalItems()).isEqualTo(88);
        verify(listScraperClient, never()).fetchDetail(any(), any(), any());
        assertAudit("scraper.task-page", "succeeded");
    }

    @Test
    void pageTaskDoesNotFetchListWhenMasterIsPaused() {
        UUID masterTaskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        when(runtimeControlService.canRun(masterTaskId)).thenReturn(false);

        service.processPage(new TaskPageMessage(
                masterTaskId,
                UUID.randomUUID(),
                dataSourceId,
                2,
                false,
                0,
                options(),
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z")));

        verify(listScraperClient, never()).fetchListPage(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(queuePublisher, never()).publishDetail(any());
        verify(queuePublisher, never()).publishPageDiscovered(any());
        assertAudit("scraper.task-page", "skipped");
    }

    @Test
    void pageTaskStopsExpansionWhenUpdateWindowIsExhausted() {
        UUID masterTaskId = UUID.randomUUID();
        UUID pageTaskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        DataSourceRecord source = source(dataSourceId);
        when(dataSourceRepository.findById(dataSourceId)).thenReturn(Optional.of(source));
        when(listScraperClient.fetchListPage(eq(source), any(), eq(4))).thenReturn(new ListPage(
                List.of(new ListItem("v-old", "2026-06-09", Map.of())),
                9,
                88));

        service.processPage(new TaskPageMessage(
                masterTaskId,
                pageTaskId,
                dataSourceId,
                4,
                false,
                0,
                options(false, 48),
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z")));

        verify(queuePublisher, never()).publishDetail(any());
        ArgumentCaptor<TaskPageDiscoveredMessage> discoveredCaptor =
                ArgumentCaptor.forClass(TaskPageDiscoveredMessage.class);
        verify(queuePublisher).publishPageDiscovered(discoveredCaptor.capture());
        assertThat(discoveredCaptor.getValue().detailScheduled()).isZero();
        assertThat(discoveredCaptor.getValue().totalPages()).isEqualTo(4);
        assertAudit("scraper.task-page", "succeeded");
    }

    @Test
    void detailTaskFetchesDetailPublishesIngestionAndCompletionEvent() {
        UUID masterTaskId = UUID.randomUUID();
        UUID pageTaskId = UUID.randomUUID();
        UUID detailTaskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        DataSourceRecord source = source(dataSourceId);
        ScrapedVideoDraft draft = draft(dataSourceId, "v-1");
        IngestionItem ingestionItem = ingestion(dataSourceId, "v-1");
        when(dataSourceRepository.findById(dataSourceId)).thenReturn(Optional.of(source));
        when(listScraperClient.fetchDetail(eq(source), any(), eq("v-1"))).thenReturn("{\"ok\":true}");
        when(mappingService.mapDetail("{\"ok\":true}", source)).thenReturn(draft);
        when(mappingService.toItem(draft, true)).thenReturn(ingestionItem);

        service.processDetail(new TaskDetailMessage(
                masterTaskId,
                pageTaskId,
                detailTaskId,
                dataSourceId,
                "v-1",
                null,
                0,
                "idempotent",
                options(true),
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z")));

        verify(ingestionPublisher).publish(ingestionItem);
        ArgumentCaptor<TaskDetailDoneMessage> doneCaptor = ArgumentCaptor.forClass(TaskDetailDoneMessage.class);
        verify(queuePublisher).publishDetailDone(doneCaptor.capture());
        assertThat(doneCaptor.getValue().detailTaskId()).isEqualTo(detailTaskId);
        assertThat(doneCaptor.getValue().successful()).isTrue();
        assertThat(doneCaptor.getValue().errorMessage()).isNull();
        assertAudit("scraper.task-detail", "succeeded");
    }

    @Test
    void detailTaskDoesNotFetchDetailWhenMasterIsPaused() {
        UUID masterTaskId = UUID.randomUUID();
        UUID pageTaskId = UUID.randomUUID();
        UUID detailTaskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        when(runtimeControlService.canRun(masterTaskId)).thenReturn(false);

        service.processDetail(new TaskDetailMessage(
                masterTaskId,
                pageTaskId,
                detailTaskId,
                dataSourceId,
                "v-1",
                null,
                0,
                "idempotent",
                options(true),
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z")));

        verify(listScraperClient, never()).fetchDetail(any(), any(), any());
        verify(ingestionPublisher, never()).publish(any());
        verify(queuePublisher, never()).publishDetailDone(any());
        assertAudit("scraper.task-detail", "skipped");
    }

    @Test
    void detailTaskFailureAuditIncludesRootCauseSummary() {
        UUID masterTaskId = UUID.randomUUID();
        UUID pageTaskId = UUID.randomUUID();
        UUID detailTaskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        DataSourceRecord source = source(dataSourceId);
        when(dataSourceRepository.findById(dataSourceId)).thenReturn(Optional.of(source));
        when(listScraperClient.fetchDetail(eq(source), any(), eq("v-429")))
                .thenThrow(new IllegalStateException("HTTP status 429 from data source"));

        assertThatThrownBy(() -> service.processDetail(new TaskDetailMessage(
                masterTaskId,
                pageTaskId,
                detailTaskId,
                dataSourceId,
                "v-429",
                null,
                0,
                "idempotent",
                options(true),
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP status 429");

        ArgumentCaptor<WorkerJobAuditEvent> auditCaptor = ArgumentCaptor.forClass(WorkerJobAuditEvent.class);
        verify(auditWriter).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().jobType()).isEqualTo("queue-consumer");
        assertThat(auditCaptor.getValue().jobName()).isEqualTo("scraper.task-detail");
        assertThat(auditCaptor.getValue().status()).isEqualTo("failed");
        assertThat(auditCaptor.getValue().error().getMessage())
                .contains("detail task failed")
                .contains("HTTP status 429");
    }

    private DataSourceRecord source(UUID dataSourceId) {
        return new DataSourceRecord(
                dataSourceId,
                "Codex source",
                "https://example.invalid",
                "/list",
                "{}",
                "/detail/{id}",
                "{}",
                "{}");
    }

    private TaskScrapeOptions options() {
        return options(false);
    }

    private TaskScrapeOptions options(boolean forceIngest) {
        return options(forceIngest, null);
    }

    private TaskScrapeOptions options(boolean forceIngest, Integer filterHours) {
        return new TaskScrapeOptions(
                "codex",
                null,
                filterHours,
                null,
                true,
                false,
                null,
                null,
                null,
                null,
                null,
                "{}",
                0,
                forceIngest);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Clock> clockProvider(Clock clock) {
        ObjectProvider<Clock> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        when(provider.getIfUnique()).thenReturn(clock);
        return provider;
    }

    private ScrapedVideoDraft draft(UUID dataSourceId, String sourceVid) {
        return new ScrapedVideoDraft(
                dataSourceId,
                sourceVid,
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
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                "{}",
                "{}",
                List.of());
    }

    private IngestionItem ingestion(UUID dataSourceId, String sourceVid) {
        return new IngestionItem(new IngestionVideoDTO(
                sourceVid,
                null,
                null,
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
                "PENDING",
                dataSourceId,
                List.of(),
                0), true);
    }

    private void assertAudit(String jobName, String status) {
        ArgumentCaptor<WorkerJobAuditEvent> auditCaptor = ArgumentCaptor.forClass(WorkerJobAuditEvent.class);
        verify(auditWriter).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().jobType()).isEqualTo("queue-consumer");
        assertThat(auditCaptor.getValue().jobName()).isEqualTo(jobName);
        assertThat(auditCaptor.getValue().status()).isEqualTo(status);
    }
}
