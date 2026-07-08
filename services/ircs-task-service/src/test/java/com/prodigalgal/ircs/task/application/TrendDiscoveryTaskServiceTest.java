package com.prodigalgal.ircs.task.application;





import com.prodigalgal.ircs.task.infrastructure.DataSourceSeedCandidate;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.infrastructure.TaskDistributedLockRunner;
import com.prodigalgal.ircs.task.dto.TaskCreateRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.trend.TrendDiscoveryScheduleRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class TrendDiscoveryTaskServiceTest {

    private final JdbcCollectionTaskRepository taskRepository = org.mockito.Mockito.mock(JdbcCollectionTaskRepository.class);
    private final TaskCommandService taskCommandService = org.mockito.Mockito.mock(TaskCommandService.class);
    private final TrendDiscoveryTaskService service = service();

    @Test
    void createsAndQueuesDiscoveryTaskForEachDatasourceAndKeyword() {
        UUID dataSourceId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findDefaultTaskSeedDataSources())
                .thenReturn(List.of(new DataSourceSeedCandidate(dataSourceId, "Codex Source")));
        when(taskRepository.findTaskIdByName(any())).thenReturn(Optional.empty());
        when(taskCommandService.create(any())).thenReturn(taskId);
        when(taskCommandService.startInternal(taskId, false)).thenReturn(true);

        var response = service.schedule(new TrendDiscoveryScheduleRequest(
                List.of("Codex Trend", "Codex Trend", " "),
                1,
                1,
                0,
                false), "corr-trend");

        assertThat(response.requestedKeywords()).isEqualTo(1);
        assertThat(response.dataSourceCount()).isEqualTo(1);
        assertThat(response.createdTasks()).isEqualTo(1);
        assertThat(response.queuedTasks()).isEqualTo(1);
        assertThat(response.tasks()).hasSize(1);
        assertThat(response.tasks().getFirst().status()).isEqualTo("QUEUED");

        ArgumentCaptor<TaskCreateRequest> captor = ArgumentCaptor.forClass(TaskCreateRequest.class);
        verify(taskCommandService).create(captor.capture());
        TaskCreateRequest request = captor.getValue();
        assertThat(request.dataSourceId()).isEqualTo(dataSourceId);
        assertThat(request.filterKeywords()).isEqualTo("Codex Trend");
        assertThat(request.startPage()).isEqualTo(1);
        assertThat(request.endPage()).isEqualTo(1);
    }

    @Test
    void reusesExistingTaskAndReportsSkippedWhenAlreadyActive() {
        UUID dataSourceId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findDefaultTaskSeedDataSources())
                .thenReturn(List.of(new DataSourceSeedCandidate(dataSourceId, "Codex Source")));
        when(taskRepository.findTaskIdByName(any())).thenReturn(Optional.of(taskId));
        when(taskCommandService.startInternal(taskId, false)).thenReturn(false);

        var response = service.schedule(new TrendDiscoveryScheduleRequest(
                List.of("Codex Trend"),
                null,
                null,
                null,
                false), "corr-trend");

        assertThat(response.createdTasks()).isZero();
        assertThat(response.reusedTasks()).isEqualTo(1);
        assertThat(response.queuedTasks()).isZero();
        assertThat(response.tasks().getFirst().status()).isEqualTo("SKIPPED_ACTIVE_OR_DISABLED");
    }

    @Test
    void limitsDiscoveryDataSourcesByRequestAndConfig() {
        UUID firstDataSourceId = UUID.randomUUID();
        UUID secondDataSourceId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        ReflectionTestUtils.setField(service, "maxDataSources", 1);
        when(taskRepository.findDefaultTaskSeedDataSources())
                .thenReturn(List.of(
                        new DataSourceSeedCandidate(firstDataSourceId, "First Source"),
                        new DataSourceSeedCandidate(secondDataSourceId, "Second Source")));
        when(taskRepository.findTaskIdByName(any())).thenReturn(Optional.empty());
        when(taskCommandService.create(any())).thenReturn(taskId);
        when(taskCommandService.startInternal(taskId, false)).thenReturn(true);

        var response = service.schedule(new TrendDiscoveryScheduleRequest(
                List.of("Codex Trend"),
                1,
                1,
                0,
                false,
                2), "corr-trend");

        assertThat(response.requestedKeywords()).isEqualTo(1);
        assertThat(response.dataSourceCount()).isEqualTo(1);
        assertThat(response.createdTasks()).isEqualTo(1);
        assertThat(response.tasks()).hasSize(1);
        assertThat(response.tasks().getFirst().dataSourceId()).isEqualTo(firstDataSourceId);

        ArgumentCaptor<TaskCreateRequest> captor = ArgumentCaptor.forClass(TaskCreateRequest.class);
        verify(taskCommandService).create(captor.capture());
        assertThat(captor.getValue().dataSourceId()).isEqualTo(firstDataSourceId);
    }

    private TrendDiscoveryTaskService service() {
        TrendDiscoveryTaskService result = new TrendDiscoveryTaskService(
                taskRepository,
                taskCommandService,
                TaskDistributedLockRunner.local(),
                new com.fasterxml.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(result, "enabled", true);
        ReflectionTestUtils.setField(result, "maxKeywords", 50);
        ReflectionTestUtils.setField(result, "maxDataSources", 0);
        return result;
    }
}
