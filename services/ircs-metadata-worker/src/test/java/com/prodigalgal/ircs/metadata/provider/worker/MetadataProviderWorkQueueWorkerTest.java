package com.prodigalgal.ircs.metadata.provider.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.work.RuntimeWorkItem;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.contracts.metadata.MetadataSearchContext;
import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import com.prodigalgal.ircs.metadata.dispatch.messaging.MetadataProviderTaskPublisher.MetadataProviderTaskPayload;
import com.prodigalgal.ircs.metadata.provider.application.MetadataProviderWorker;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderRetryableException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class MetadataProviderWorkQueueWorkerTest {

    private final RuntimeWorkQueue workQueue = org.mockito.Mockito.mock(RuntimeWorkQueue.class);
    private final MetadataProviderWorker providerWorker = org.mockito.Mockito.mock(MetadataProviderWorker.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void continuesBatchAfterOneTaskFails() throws Exception {
        RuntimeWorkItem first = task("task-1");
        RuntimeWorkItem second = task("task-2");
        MetadataProviderWorkQueueWorker worker = worker(1);

        when(workQueue.claim(
                eq(PipelineRuntimeWorkTypes.METADATA_PROVIDER),
                any(),
                eq(2),
                eq(Duration.ofMinutes(10))))
                .thenReturn(List.of(first, second));
        doThrow(new MetadataProviderRetryableException("RATE_LIMITED", "limited"))
                .doNothing()
                .when(providerWorker)
                .execute(any(MetadataSearchContext.class), eq(ProviderType.TMDB));

        org.assertj.core.api.Assertions.assertThat(worker.runOnce()).isEqualTo(2);

        verify(workQueue).fail(
                eq(first),
                eq(true),
                eq(Duration.ofMinutes(2)),
                org.mockito.ArgumentMatchers.contains("limited"));
        verify(workQueue).complete(second);
        worker.shutdown();
    }

    @Test
    void claimsConfiguredBatchWhenParallelismIsEnabled() throws Exception {
        RuntimeWorkItem first = task("task-1");
        RuntimeWorkItem second = task("task-2");
        MetadataProviderWorkQueueWorker worker = worker(4);
        AtomicBoolean virtualThread = new AtomicBoolean(false);

        when(workQueue.claim(
                eq(PipelineRuntimeWorkTypes.METADATA_PROVIDER),
                any(),
                eq(2),
                eq(Duration.ofMinutes(10))))
                .thenReturn(List.of(first, second));
        org.mockito.Mockito.doAnswer(invocation -> {
                    virtualThread.set(Thread.currentThread().isVirtual());
                    return null;
                })
                .when(providerWorker)
                .execute(any(MetadataSearchContext.class), eq(ProviderType.TMDB));

        org.assertj.core.api.Assertions.assertThat(worker.runOnce()).isEqualTo(2);

        verify(workQueue).complete(first);
        verify(workQueue).complete(second);
        org.assertj.core.api.Assertions.assertThat(virtualThread.get()).isTrue();
        worker.shutdown();
    }

    private MetadataProviderWorkQueueWorker worker(int parallelism) {
        return new MetadataProviderWorkQueueWorker(
                workQueue,
                providerWorker,
                objectMapper,
                null,
                "ircs-metadata-worker",
                "worker-test",
                2,
                parallelism,
                Duration.ofMinutes(10),
                Duration.ofMinutes(2));
    }

    private RuntimeWorkItem task(String taskId) throws Exception {
        UUID videoId = UUID.randomUUID();
        MetadataSearchContext context = MetadataSearchContext.builder()
                .videoId(videoId)
                .title("The Matrix")
                .pipelineVersion("v1")
                .build();
        String payload = objectMapper.writeValueAsString(new MetadataProviderTaskPayload(ProviderType.TMDB, context));
        Instant now = Instant.now();
        return new RuntimeWorkItem(
                PipelineRuntimeWorkTypes.METADATA_PROVIDER,
                taskId,
                taskId,
                videoId.toString(),
                "v1",
                payload,
                "INFLIGHT",
                0,
                now,
                now,
                now,
                now.plus(Duration.ofMinutes(10)),
                "worker-test",
                null);
    }
}
