package com.prodigalgal.ircs.common.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkerInstanceIdsTest {

    @Test
    void explicitWorkerIdWins() {
        assertThat(WorkerInstanceIds.resolve("ircs-search-service", "search-pod-1"))
                .isEqualTo("search-pod-1");
    }

    @Test
    void fallbackCombinesApplicationHostAndProcess() {
        String workerId = WorkerInstanceIds.resolve("ircs-search-service", "");

        assertThat(workerId)
                .startsWith("ircs-search-service@")
                .contains("#")
                .hasSizeLessThanOrEqualTo(WorkerInstanceIds.MAX_LENGTH);
    }
}
