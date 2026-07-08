package com.prodigalgal.ircs.common.work;

import static org.assertj.core.api.Assertions.assertThat;

import com.prodigalgal.ircs.common.aggregation.AggregationWorkTypes;
import com.prodigalgal.ircs.common.audit.AuditReplicationWorkTypes;
import com.prodigalgal.ircs.common.magnet.MagnetWorkTypes;
import com.prodigalgal.ircs.common.normalization.LlmCleaningWorkTypes;
import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.search.SearchSyncWorkTypes;
import com.prodigalgal.ircs.common.storage.StorageWorkTypes;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkSubmissionTopologyTest {

    @Test
    void onlyEnhancementRuntimeWorkTypesHaveSubmissionGates() {
        assertThat(runtimeSubmissionKeys(SearchSyncWorkTypes.RAW))
                .containsExactly("app.search.sync.enabled", "app.search.work-queue.worker.enabled");
        assertThat(runtimeSubmissionKeys(SearchSyncWorkTypes.UNIFIED))
                .containsExactly("app.search.sync.enabled", "app.search.work-queue.worker.enabled");
        assertThat(runtimeSubmissionKeys(AggregationWorkTypes.RAW_VIDEO)).isEmpty();
        assertThat(runtimeSubmissionKeys(LlmCleaningWorkTypes.RAW_TERM))
                .containsExactly(
                        "app.ai.llm.enabled",
                        "app.normalization.llm-cleaning.work-queue.worker.enabled");
        assertThat(runtimeSubmissionKeys(StorageWorkTypes.AVATAR_SYNC))
                .containsExactly("app.storage.r2.enabled", "app.storage.r2.work-queue.worker.enabled");
        assertThat(runtimeSubmissionKeys(StorageWorkTypes.COVER_R2_SYNC))
                .containsExactly("app.storage.r2.enabled", "app.storage.r2.work-queue.worker.enabled");
        assertThat(runtimeSubmissionKeys(AuditReplicationWorkTypes.ES_REPLICATION))
                .containsExactly(
                        "app.audit.es-replication.enabled",
                        "app.search.audit-es-replication.worker.enabled");
        assertThat(runtimeSubmissionKeys(MagnetWorkTypes.SEARCH))
                .containsExactly("app.magnet.work-queue.submission.enabled");
    }

    @Test
    void corePipelineRuntimeWorkTypesAreNotSubmissionGated() {
        assertThat(runtimeSubmissionKeys(PipelineRuntimeWorkTypes.NORMALIZE_VIDEO)).isEmpty();
        assertThat(runtimeSubmissionKeys(PipelineRuntimeWorkTypes.ENRICH_METADATA)).isEmpty();
        assertThat(runtimeSubmissionKeys(PipelineRuntimeWorkTypes.METADATA_PROVIDER)).isEmpty();
    }

    @Test
    void consumerTopologyDistinguishesCoreAndEnhancementQueues() {
        assertThat(runtimeConsumerKeys(AggregationWorkTypes.RAW_VIDEO)).isEmpty();
        assertThat(runtimeConsumerKeys(LlmCleaningWorkTypes.RAW_TERM))
                .containsExactly(
                        "app.ai.llm.enabled",
                        "app.normalization.llm-cleaning.work-queue.worker.enabled");
        assertThat(runtimeConsumerKeys(MagnetWorkTypes.SEARCH))
                .containsExactly("app.magnet.work-queue.worker.enabled");
        assertThat(runtimeConsumerKeys(PipelineRuntimeWorkTypes.NORMALIZE_VIDEO)).isEmpty();
        assertThat(runtimeConsumerKeys(PipelineRuntimeWorkTypes.ENRICH_METADATA)).isEmpty();
        assertThat(runtimeConsumerKeys(PipelineRuntimeWorkTypes.METADATA_PROVIDER)).isEmpty();
    }

    private static List<String> runtimeSubmissionKeys(String taskType) {
        return SystemConfigWorkSubmissionGate.runtimeSubmissionFlags(taskType).stream()
                .map(SystemConfigWorkSubmissionGate.ConfigFlag::key)
                .toList();
    }

    private static List<String> runtimeConsumerKeys(String taskType) {
        return SystemConfigWorkSubmissionGate.runtimeConsumerFlags(taskType).orElseThrow().stream()
                .map(SystemConfigWorkSubmissionGate.ConfigFlag::key)
                .toList();
    }
}
