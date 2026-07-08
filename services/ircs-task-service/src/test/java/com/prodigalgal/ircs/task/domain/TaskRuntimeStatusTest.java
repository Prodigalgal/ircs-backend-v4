package com.prodigalgal.ircs.task.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaskRuntimeStatusTest {

    @Test
    void blocksOnlyExplicitTerminalOrHoldStatusesForDispatch() {
        assertThat(TaskRuntimeStatus.isBlockedForDispatch("PAUSED")).isTrue();
        assertThat(TaskRuntimeStatus.isBlockedForDispatch("STOPPING")).isTrue();
        assertThat(TaskRuntimeStatus.isBlockedForDispatch("FAILED")).isTrue();
        assertThat(TaskRuntimeStatus.isBlockedForDispatch("COMPLETED_WITH_ERRORS")).isTrue();

        assertThat(TaskRuntimeStatus.isBlockedForDispatch(null)).isFalse();
        assertThat(TaskRuntimeStatus.isBlockedForDispatch("")).isFalse();
        assertThat(TaskRuntimeStatus.isBlockedForDispatch("QUEUED")).isFalse();
        assertThat(TaskRuntimeStatus.isBlockedForDispatch("RUNNING")).isFalse();
        assertThat(TaskRuntimeStatus.isBlockedForDispatch("UNKNOWN")).isFalse();
    }

    @Test
    void allowsPageExpansionOnlyBeforeTerminalState() {
        assertThat(TaskRuntimeStatus.allowsPageExpansion("QUEUED")).isTrue();
        assertThat(TaskRuntimeStatus.allowsPageExpansion("running")).isTrue();

        assertThat(TaskRuntimeStatus.allowsPageExpansion(null)).isFalse();
        assertThat(TaskRuntimeStatus.allowsPageExpansion("DISCOVERED")).isFalse();
        assertThat(TaskRuntimeStatus.allowsPageExpansion("FAILED")).isFalse();
        assertThat(TaskRuntimeStatus.allowsPageExpansion("COMPLETED")).isFalse();
    }

    @Test
    void normalizesHoldStatusWithoutLosingStoppingIntent() {
        assertThat(TaskRuntimeStatus.normalizeHoldStatus("STOPPING")).isEqualTo("STOPPING");
        assertThat(TaskRuntimeStatus.normalizeHoldStatus("stopping")).isEqualTo("STOPPING");

        assertThat(TaskRuntimeStatus.normalizeHoldStatus(null)).isEqualTo("PAUSED");
        assertThat(TaskRuntimeStatus.normalizeHoldStatus("RUNNING")).isEqualTo("PAUSED");
    }

    @Test
    void keepsExistingFallbackStatusNormalization() {
        assertThat(TaskRuntimeStatus.normalizeValue(" completed_with_errors ", TaskRuntimeStatus.COMPLETED))
                .isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(TaskRuntimeStatus.normalizeValue(null, TaskRuntimeStatus.COMPLETED))
                .isEqualTo("COMPLETED");
    }
}
