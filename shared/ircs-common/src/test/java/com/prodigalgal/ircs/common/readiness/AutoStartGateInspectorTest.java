package com.prodigalgal.ircs.common.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AutoStartGateInspectorTest {

    @Test
    void missingGateUsesDefaultSourceAndBlocksWhenDefaultDisabled() {
        AutoStartGateState state = new AutoStartGateInspector(new MockEnvironment())
                .inspect(new AutoStartGate(
                        "normalization-llm-cleaning-work-queue-worker",
                        "app.normalization.llm-cleaning.work-queue.worker.enabled",
                        "APP_NORMALIZATION_LLM_CLEANING_WORK_QUEUE_WORKER_ENABLED",
                        false));

        assertThat(state.enabled()).isFalse();
        assertThat(state.blocked()).isTrue();
        assertThat(state.source()).isEqualTo("DEFAULT");
    }

    @Test
    void runtimeInjectionWinsAndReportsInjectedSourceKey() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_STORAGE_R2_WORK_QUEUE_WORKER_ENABLED", "true");

        AutoStartGateState state = new AutoStartGateInspector(environment)
                .inspect(new AutoStartGate(
                        "storage-r2-work-queue-worker",
                        "app.storage.r2.work-queue.worker.enabled",
                        "APP_STORAGE_R2_WORK_QUEUE_WORKER_ENABLED",
                        false));

        assertThat(state.enabled()).isTrue();
        assertThat(state.blocked()).isFalse();
        assertThat(state.source()).isEqualTo("INJECTED");
        assertThat(state.sourceKey()).isEqualTo("APP_STORAGE_R2_WORK_QUEUE_WORKER_ENABLED");
    }
}
