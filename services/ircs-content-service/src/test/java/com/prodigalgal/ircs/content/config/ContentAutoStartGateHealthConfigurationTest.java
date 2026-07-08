package com.prodigalgal.ircs.content.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.prodigalgal.ircs.common.readiness.AutoStartGateState;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.mock.env.MockEnvironment;

class ContentAutoStartGateHealthConfigurationTest {

    @Test
    void exposesListenerAndResolverSeedGates() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_CONTENT_LISTENER_ENABLED", "true");
        HealthIndicator indicator = new ContentAutoStartGateHealthConfiguration()
                .autoStartGateHealthIndicator(environment);

        List<AutoStartGateState> gates = gates(indicator.health());

        assertThat(gates).extracting(AutoStartGateState::name)
                .containsExactly(
                        "content-listener",
                        "content-config-listener",
                        "content-maintenance-gate-listener",
                        "content-resolver-preset-seed");
        assertThat(gates).filteredOn(gate -> gate.name().equals("content-listener"))
                .singleElement()
                .satisfies(gate -> {
                    assertThat(gate.enabled()).isTrue();
                    assertThat(gate.source()).isEqualTo("INJECTED");
                    assertThat(gate.sourceKey()).isEqualTo("APP_CONTENT_LISTENER_ENABLED");
                });
        assertThat(gates).filteredOn(gate -> gate.name().equals("content-config-listener"))
                .singleElement()
                .satisfies(gate -> assertThat(gate.blocked()).isTrue());
        assertThat(gates).filteredOn(gate -> gate.name().equals("content-maintenance-gate-listener"))
                .singleElement()
                .satisfies(gate -> assertThat(gate.blocked()).isTrue());
        assertThat(gates).filteredOn(gate -> gate.name().equals("content-resolver-preset-seed"))
                .singleElement()
                .satisfies(gate -> assertThat(gate.enabled()).isTrue());
    }

    @SuppressWarnings("unchecked")
    private List<AutoStartGateState> gates(Health health) {
        return (List<AutoStartGateState>) health.getDetails().get("gates");
    }
}
