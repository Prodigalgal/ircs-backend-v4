package com.prodigalgal.ircs.common.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EgressIdentitiesTest {

    @Test
    void resolvesUnknownPlaceholderToFallback() {
        assertThat(EgressIdentities.resolve("unknown", "pod-1")).isEqualTo("pod-1");
        assertThat(EgressIdentities.resolve(" UNKNOWN ", "10.0.0.1")).isEqualTo("10.0.0.1");
    }

    @Test
    void stillSanitizesExplicitIdentity() {
        assertThat(EgressIdentities.resolve("2001:db8::10", "pod-1")).isEqualTo("2001_db8__10");
    }
}
