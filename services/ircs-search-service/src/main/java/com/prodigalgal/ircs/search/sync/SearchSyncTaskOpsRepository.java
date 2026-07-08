package com.prodigalgal.ircs.search.sync;

import com.prodigalgal.ircs.common.search.SearchSyncWorkTypes;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueueCounts;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SearchSyncTaskOpsRepository {

    private final RuntimeWorkQueue workQueue;
    private final ObjectProvider<SearchSyncWorkQueueWorker> workerProvider;

    public SearchSyncTaskStatsResponse stats() {
        try {
            RuntimeWorkQueueCounts raw = workQueue.counts(SearchSyncWorkTypes.RAW);
            RuntimeWorkQueueCounts unified = workQueue.counts(SearchSyncWorkTypes.UNIFIED);
            long pending = raw.pending() + unified.pending();
            long processing = raw.inflight() + unified.inflight();
            long failed = raw.dlq() + unified.dlq();
            return new SearchSyncTaskStatsResponse(
                    true,
                    null,
                    pending + processing + failed,
                    pending,
                    processing,
                    0,
                    failed,
                    pending,
                    raw.pending() + raw.inflight() + raw.dlq(),
                    unified.pending() + unified.inflight() + unified.dlq(),
                    0,
                    0,
                    0,
                    null,
                    null,
                    new SearchSyncQueueStats(
                            raw.pending(),
                            raw.inflight(),
                            raw.dlq(),
                            workQueue.expiredInflightCount(SearchSyncWorkTypes.RAW)),
                    new SearchSyncQueueStats(
                            unified.pending(),
                            unified.inflight(),
                            unified.dlq(),
                            workQueue.expiredInflightCount(SearchSyncWorkTypes.UNIFIED)),
                    SearchSyncWorkerStats.from(workerState()));
        } catch (RuntimeException ex) {
            return SearchSyncTaskStatsResponse.unavailable("runtime_work_queue_unavailable");
        }
    }

    private SearchSyncWorkQueueState workerState() {
        SearchSyncWorkQueueWorker worker = workerProvider == null ? null : workerProvider.getIfAvailable();
        return worker == null ? null : worker.state();
    }
}
