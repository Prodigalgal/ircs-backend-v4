package com.prodigalgal.ircs.metadata.dispatch.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.aggregation.AggregationWorkPublisher;
import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import com.prodigalgal.ircs.metadata.config.MetadataConfigValues;
import com.prodigalgal.ircs.metadata.dispatch.domain.RawVideoEnrichmentRecord;
import com.prodigalgal.ircs.metadata.dispatch.dto.MetadataDispatchResult;
import com.prodigalgal.ircs.metadata.dispatch.infrastructure.RawVideoEnrichmentRepository;
import com.prodigalgal.ircs.metadata.dispatch.messaging.MetadataProviderTaskPublisher;
import com.prodigalgal.ircs.metadata.pipeline.MetadataPipelineRunRepository;
import com.prodigalgal.ircs.metadata.result.messaging.RawSearchSyncPublisher;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MetadataDispatchServiceTest {

    @Mock
    private RawVideoEnrichmentRepository repository;

    @Mock
    private MetadataPipelineRunRepository pipelineRunRepository;

    @Mock
    private MetadataProviderTaskPublisher providerTaskPublisher;

    @Mock
    private RawSearchSyncPublisher searchSyncPublisher;

    @Mock
    private AggregationWorkPublisher aggregationWorkPublisher;

    @Mock
    private MetadataConfigValues configValues;

    @AfterEach
    void tearDown() {
        org.mockito.Mockito.validateMockitoUsage();
    }

    @Test
    void finalizesImmediatelyWhenAuthoritativeIdExists() {
        UUID id = UUID.randomUUID();
        RawVideoEnrichmentRecord record = record(id);
        record.setTmdbId("tmdb-1");
        when(repository.findById(id)).thenReturn(Optional.of(record));

        MetadataDispatchResult result = newService(false).dispatch(id, "hash-v1");

        assertEquals("SUCCESS", result.status());
        verify(repository).updateStatus(id, "SUCCESS", 0);
        verify(searchSyncPublisher).publishIndex(id);
        verify(aggregationWorkPublisher).enqueue(id, "hash-v1", "metadata-immediate-finalized");
        verify(providerTaskPublisher, never()).publish(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    void dispatchesEnabledMissingProvidersToValkeyTasks() {
        UUID id = UUID.randomUUID();
        RawVideoEnrichmentRecord record = record(id);
        when(repository.findById(id)).thenReturn(Optional.of(record));
        when(pipelineRunRepository.prepareDispatch(id, "hash-v1", List.of(ProviderType.TMDB)))
                .thenReturn(List.of(ProviderType.TMDB));

        MetadataDispatchResult result = newService(true).dispatch(id, "hash-v1");

        assertEquals("DISPATCHED", result.status());
        assertEquals(List.of(ProviderType.TMDB), result.providers());
        verify(providerTaskPublisher).publish(org.mockito.Mockito.eq(ProviderType.TMDB), org.mockito.Mockito.any());
        verify(searchSyncPublisher, never()).publishIndex(id);
    }

    @Test
    void stalePipelineVersionDoesNotFanOutProviderTasks() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(record(id)));

        MetadataDispatchResult result = newService(false).dispatch(id, "old-hash");

        assertEquals("STALE", result.status());
        verify(providerTaskPublisher, never()).publish(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    void fanOutUsesProviderConfigAndPublishesProviderTasksThroughValkey() {
        UUID id = UUID.randomUUID();
        RawVideoEnrichmentRecord record = record(id);
        when(repository.findById(id)).thenReturn(Optional.of(record));
        RuntimeWorkQueue workQueue = org.mockito.Mockito.mock(RuntimeWorkQueue.class);
        MetadataProviderTaskPublisher publisher = new MetadataProviderTaskPublisher(workQueue, new ObjectMapper());
        when(configValues.doubanEnabled()).thenReturn(true);
        when(configValues.tmdbEnabled()).thenReturn(true);
        when(configValues.rottenTomatoesEnabled()).thenReturn(true);
        when(pipelineRunRepository.prepareDispatch(
                        id,
                        "hash-v1",
                        List.of(ProviderType.DOUBAN, ProviderType.TMDB, ProviderType.ROTTEN_TOMATOES)))
                .thenReturn(List.of(ProviderType.DOUBAN, ProviderType.TMDB, ProviderType.ROTTEN_TOMATOES));
        MetadataDispatchService service = new MetadataDispatchService(
                repository,
                configValues,
                pipelineRunRepository,
                publisher,
                searchSyncPublisher,
                aggregationWorkPublisher);

        MetadataDispatchResult result = service.dispatch(id, "hash-v1");

        assertEquals("DISPATCHED", result.status());
        assertEquals(List.of(ProviderType.DOUBAN, ProviderType.TMDB, ProviderType.ROTTEN_TOMATOES), result.providers());
        ArgumentCaptor<RuntimeWorkItemRequest> requestCaptor = ArgumentCaptor.forClass(RuntimeWorkItemRequest.class);
        verify(workQueue, org.mockito.Mockito.times(3)).submitAfterCommit(requestCaptor.capture());
        assertEquals(List.of(
                PipelineRuntimeWorkTypes.providerTaskId("DOUBAN", id, "hash-v1"),
                PipelineRuntimeWorkTypes.providerTaskId("TMDB", id, "hash-v1"),
                PipelineRuntimeWorkTypes.providerTaskId("ROTTEN_TOMATOES", id, "hash-v1")),
                requestCaptor.getAllValues().stream().map(RuntimeWorkItemRequest::taskId).toList());
        assertEquals(List.of(
                PipelineRuntimeWorkTypes.METADATA_PROVIDER,
                PipelineRuntimeWorkTypes.METADATA_PROVIDER,
                PipelineRuntimeWorkTypes.METADATA_PROVIDER),
                requestCaptor.getAllValues().stream().map(RuntimeWorkItemRequest::taskType).toList());
        verify(searchSyncPublisher, never()).publishIndex(id);
    }

    @Test
    void skipsProviderWhenAuthoritativeProviderIdAlreadyExists() {
        UUID id = UUID.randomUUID();
        RawVideoEnrichmentRecord record = record(id);
        record.setTmdbId("tmdb-1");
        when(repository.findById(id)).thenReturn(Optional.of(record));
        RuntimeWorkQueue workQueue = org.mockito.Mockito.mock(RuntimeWorkQueue.class);
        MetadataProviderTaskPublisher publisher = new MetadataProviderTaskPublisher(workQueue, new ObjectMapper());
        when(configValues.doubanEnabled()).thenReturn(true);
        when(configValues.tmdbEnabled()).thenReturn(true);
        when(configValues.rottenTomatoesEnabled()).thenReturn(true);
        when(pipelineRunRepository.prepareDispatch(
                        id,
                        "hash-v1",
                        List.of(ProviderType.DOUBAN, ProviderType.ROTTEN_TOMATOES)))
                .thenReturn(List.of(ProviderType.DOUBAN, ProviderType.ROTTEN_TOMATOES));
        MetadataDispatchService service = new MetadataDispatchService(
                repository,
                configValues,
                pipelineRunRepository,
                publisher,
                searchSyncPublisher,
                aggregationWorkPublisher);

        MetadataDispatchResult result = service.dispatch(id, "hash-v1");

        assertEquals("DISPATCHED", result.status());
        assertEquals(List.of(ProviderType.DOUBAN, ProviderType.ROTTEN_TOMATOES), result.providers());
        ArgumentCaptor<RuntimeWorkItemRequest> requestCaptor = ArgumentCaptor.forClass(RuntimeWorkItemRequest.class);
        verify(workQueue, org.mockito.Mockito.times(2)).submitAfterCommit(requestCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(requestCaptor.getAllValues())
                .extracting(RuntimeWorkItemRequest::taskId)
                .noneMatch(taskId -> taskId.toString().contains("TMDB"));
    }

    @Test
    void retryableImmediateFailureIncrementsRetryCount() {
        UUID id = UUID.randomUUID();
        RawVideoEnrichmentRecord record = record(id);
        record.setEnrichmentRetryCount(2);
        when(repository.findById(id)).thenReturn(Optional.of(record));

        MetadataDispatchResult result = newService(false).dispatch(id, "hash-v1");

        assertEquals("FAILED", result.status());
        verify(repository).updateStatus(id, "FAILED", 3);
        verify(searchSyncPublisher).publishIndex(id);
    }

    @Test
    void missingVideoReturnsNotFoundWithoutTouchingSearchSync() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        MetadataDispatchResult result = newService(false).dispatch(id, "hash-v1");

        assertEquals(false, result.videoFound());
        verify(searchSyncPublisher, never()).publishIndex(id);
    }

    private MetadataDispatchService newService(boolean tmdbEnabled) {
        org.mockito.Mockito.lenient().when(configValues.tmdbEnabled()).thenReturn(tmdbEnabled);
        return new MetadataDispatchService(
                repository,
                configValues,
                pipelineRunRepository,
                providerTaskPublisher,
                searchSyncPublisher,
                aggregationWorkPublisher);
    }

    private RawVideoEnrichmentRecord record(UUID id) {
        return new RawVideoEnrichmentRecord(
                id,
                "title",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "PENDING",
                0,
                "hash-v1");
    }
}
