package com.prodigalgal.ircs.search.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.search.SearchSyncWorkTypes;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueueCounts;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;

class SearchSyncTaskOpsRepositoryTest {

    private final RuntimeWorkQueue workQueue = org.mockito.Mockito.mock(RuntimeWorkQueue.class);
    private final ObjectProvider<SearchSyncWorkQueueWorker> workerProvider = org.mockito.Mockito.mock(ObjectProvider.class);
    private final SearchSyncTaskOpsRepository repository = new SearchSyncTaskOpsRepository(workQueue, workerProvider);

    @Test
    void aggregatesRuntimeWorkQueueStats() {
        when(workQueue.counts(SearchSyncWorkTypes.RAW)).thenReturn(new RuntimeWorkQueueCounts(2, 1, 0));
        when(workQueue.counts(SearchSyncWorkTypes.UNIFIED)).thenReturn(new RuntimeWorkQueueCounts(3, 0, 1));

        SearchSyncTaskStatsResponse stats = repository.stats();

        assertThat(stats.available()).isTrue();
        assertThat(stats.unavailableReason()).isNull();
        assertThat(stats.total()).isEqualTo(7);
        assertThat(stats.pending()).isEqualTo(5);
        assertThat(stats.processing()).isEqualTo(1);
        assertThat(stats.failed()).isEqualTo(1);
        assertThat(stats.duePending()).isEqualTo(5);
        assertThat(stats.rawVideo()).isEqualTo(3);
        assertThat(stats.unifiedVideo()).isEqualTo(4);
        assertThat(stats.rawQueue().pending()).isEqualTo(2);
        assertThat(stats.rawQueue().processing()).isEqualTo(1);
        assertThat(stats.unifiedQueue().failed()).isEqualTo(1);
    }

    @Test
    void returnsUnavailableWhenRuntimeWorkQueueFails() {
        when(workQueue.counts(SearchSyncWorkTypes.RAW)).thenThrow(new IllegalStateException("redis down"));

        SearchSyncTaskStatsResponse stats = repository.stats();

        assertThat(stats.available()).isFalse();
        assertThat(stats.unavailableReason()).isEqualTo("runtime_work_queue_unavailable");
        assertThat(stats.total()).isZero();
    }
}
