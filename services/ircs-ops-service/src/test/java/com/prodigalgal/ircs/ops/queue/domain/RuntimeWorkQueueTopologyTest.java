package com.prodigalgal.ircs.ops.queue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.prodigalgal.ircs.common.aggregation.AggregationWorkTypes;
import com.prodigalgal.ircs.common.audit.AuditReplicationWorkTypes;
import com.prodigalgal.ircs.common.normalization.LlmCleaningWorkTypes;
import com.prodigalgal.ircs.common.search.SearchSyncWorkTypes;
import com.prodigalgal.ircs.common.storage.StorageWorkTypes;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuntimeWorkQueueTopologyTest {

    @Test
    void everyRuntimeWorkProducerHasAConsumerOrConfigGate() {
        Set<String> producerTaskTypes = Set.of(
                SearchSyncWorkTypes.RAW,
                SearchSyncWorkTypes.UNIFIED,
                AggregationWorkTypes.RAW_VIDEO,
                LlmCleaningWorkTypes.RAW_TERM,
                StorageWorkTypes.AVATAR_SYNC,
                StorageWorkTypes.COVER_R2_SYNC,
                AuditReplicationWorkTypes.ES_REPLICATION);

        Set<String> guardedConsumerTaskTypes = Set.of(
                SearchSyncWorkTypes.RAW,
                SearchSyncWorkTypes.UNIFIED,
                AggregationWorkTypes.RAW_VIDEO,
                LlmCleaningWorkTypes.RAW_TERM,
                StorageWorkTypes.AVATAR_SYNC,
                StorageWorkTypes.COVER_R2_SYNC,
                AuditReplicationWorkTypes.ES_REPLICATION);

        assertThat(guardedConsumerTaskTypes).containsExactlyInAnyOrderElementsOf(producerTaskTypes);
    }
}
