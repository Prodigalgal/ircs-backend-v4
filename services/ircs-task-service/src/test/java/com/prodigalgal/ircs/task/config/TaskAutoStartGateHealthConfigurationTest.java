package com.prodigalgal.ircs.task.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.prodigalgal.ircs.common.readiness.AutoStartGateState;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.mock.env.MockEnvironment;

class TaskAutoStartGateHealthConfigurationTest {

    @Test
    void exposesDefaultSeedSchedulerWatchdogAndValkeyRuntimeGates() {
        HealthIndicator indicator = new TaskAutoStartGateHealthConfiguration()
                .autoStartGateHealthIndicator(new MockEnvironment());

        List<AutoStartGateState> gates = gates(indicator.health());

        assertThat(gates).extracting(AutoStartGateState::name)
                .containsExactly(
                        "task-default-seed",
                        "task-scheduler",
                        "task-watchdog",
                        "task-queue-listener",
                        "task-snapshot-flush");
        assertThat(gates).filteredOn(gate -> gate.name().equals("task-default-seed"))
                .singleElement()
                .satisfies(gate -> assertThat(gate.enabled()).isTrue());
        assertThat(gates).filteredOn(gate -> gate.name().equals("task-scheduler"))
                .singleElement()
                .satisfies(gate -> assertThat(gate.enabled()).isTrue());
        assertThat(gates).filteredOn(gate -> gate.name().equals("task-watchdog"))
                .singleElement()
                .satisfies(gate -> assertThat(gate.blocked()).isTrue());
        assertThat(gates).filteredOn(gate -> gate.name().equals("task-queue-listener"))
                .singleElement()
                .satisfies(gate -> assertThat(gate.blocked()).isTrue());
        assertThat(gates).filteredOn(gate -> gate.name().equals("task-snapshot-flush"))
                .singleElement()
                .satisfies(gate -> assertThat(gate.blocked()).isTrue());
    }

    @SuppressWarnings("unchecked")
    private List<AutoStartGateState> gates(Health health) {
        return (List<AutoStartGateState>) health.getDetails().get("gates");
    }
}
