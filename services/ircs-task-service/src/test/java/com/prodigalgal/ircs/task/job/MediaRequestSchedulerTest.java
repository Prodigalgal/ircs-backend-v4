package com.prodigalgal.ircs.task.job;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.task.application.MediaRequestBatchService;
import com.prodigalgal.ircs.task.domain.MediaRequestCandidate;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.infrastructure.TaskDistributedLockRunner;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MediaRequestSchedulerTest {

    private final JdbcCollectionTaskRepository taskRepository = org.mockito.Mockito.mock(JdbcCollectionTaskRepository.class);
    private final MediaRequestBatchService batchService = org.mockito.Mockito.mock(MediaRequestBatchService.class);
    private final MediaRequestScheduler scheduler = scheduler();

    @Test
    void createsBatchForClaimedMediaRequests() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(taskRepository.claimPendingMediaRequests(20)).thenReturn(List.of(
                new MediaRequestCandidate(firstId, "黑客帝国", 1999, 4),
                new MediaRequestCandidate(secondId, "异形", null, 2)));
        UUID batchId = UUID.randomUUID();
        when(batchService.createBatchFromPendingRequests(org.mockito.ArgumentMatchers.anyList())).thenReturn(Optional.of(batchId));

        scheduler.schedulePendingMediaRequestsLocked();

        verify(batchService).createBatchFromPendingRequests(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void doesNotReturnRequestsToPendingWhenAllClaimedRequestsAlreadyExist() {
        UUID requestId = UUID.randomUUID();
        when(taskRepository.claimPendingMediaRequests(20))
                .thenReturn(List.of(new MediaRequestCandidate(requestId, "黑客帝国", 1999, 1)));
        when(batchService.createBatchFromPendingRequests(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(Optional.empty());

        scheduler.schedulePendingMediaRequestsLocked();

        verify(batchService).createBatchFromPendingRequests(org.mockito.ArgumentMatchers.anyList());
        verify(taskRepository, never()).markMediaRequestsPending(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void returnsClaimedRequestsToPendingWhenBatchCreationThrows() {
        UUID requestId = UUID.randomUUID();
        when(taskRepository.claimPendingMediaRequests(20))
                .thenReturn(List.of(new MediaRequestCandidate(requestId, "失败片", null, 1)));
        when(batchService.createBatchFromPendingRequests(org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new IllegalStateException("source unavailable"));

        assertThatThrownBy(scheduler::schedulePendingMediaRequestsLocked)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("source unavailable");

        verify(taskRepository).markMediaRequestsPending(List.of(requestId), "source unavailable");
        verify(taskRepository, never()).markMediaRequestsScheduled(org.mockito.ArgumentMatchers.any());
    }

    private MediaRequestScheduler scheduler() {
        MediaRequestScheduler result = new MediaRequestScheduler(
                taskRepository,
                batchService,
                TaskDistributedLockRunner.local());
        ReflectionTestUtils.setField(result, "batchSize", 20);
        return result;
    }
}
