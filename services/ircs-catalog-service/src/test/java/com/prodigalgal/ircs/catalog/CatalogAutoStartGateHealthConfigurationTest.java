package com.prodigalgal.ircs.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.prodigalgal.ircs.common.readiness.AutoStartGateState;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.mock.env.MockEnvironment;

class CatalogAutoStartGateHealthConfigurationTest {

    @Test
    void exposesDefaultSeedGateAsEnabledByDefault() {
        HealthIndicator indicator = new CatalogAutoStartGateHealthConfiguration()
                .autoStartGateHealthIndicator(new MockEnvironment());

        List<AutoStartGateState> gates = gates(indicator.health());

        assertThat(gates).singleElement().satisfies(gate -> {
            assertThat(gate.name()).isEqualTo("catalog-default-seed");
            assertThat(gate.enabled()).isTrue();
            assertThat(gate.blocked()).isFalse();
            assertThat(gate.source()).isEqualTo("DEFAULT");
        });
    }

    @SuppressWarnings("unchecked")
    private List<AutoStartGateState> gates(Health health) {
        return (List<AutoStartGateState>) health.getDetails().get("gates");
    }
}
