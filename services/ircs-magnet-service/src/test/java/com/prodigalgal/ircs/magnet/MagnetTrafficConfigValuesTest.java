package com.prodigalgal.ircs.magnet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class MagnetTrafficConfigValuesTest {

    private static final UUID PROVIDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void readsProviderGapFromRuntimeConfigWithCodePrecedence() {
        RuntimeConfigService runtimeConfig = org.mockito.Mockito.mock(RuntimeConfigService.class);
        when(runtimeConfig.stringValue(eq(MagnetTrafficConfigValues.PROVIDER_GAP_KEY), eq("")))
                .thenReturn("THE_PIRATE_BAY_FRONTEND=15000,thepiratebay-org=20000,bad=abc");

        MagnetTrafficConfigValues values = new MagnetTrafficConfigValues(runtimeConfigProvider(runtimeConfig));

        assertThat(values.providerGap(provider("thepiratebay_org", "THE_PIRATE_BAY_FRONTEND"), ""))
                .isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void parsesDeploymentFallbackWhenRuntimeConfigIsUnavailable() {
        MagnetTrafficConfigValues values = new MagnetTrafficConfigValues(null);

        assertThat(values.providerGap(provider("ext_to", "EXT_TO"), "EXT-TO=15000"))
                .isEqualTo(Duration.ofSeconds(15));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RuntimeConfigService> runtimeConfigProvider(RuntimeConfigService runtimeConfig) {
        ObjectProvider<RuntimeConfigService> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(runtimeConfig);
        return provider;
    }

    private MagnetProviderSummary provider(String code, String providerType) {
        return new MagnetProviderSummary(
                PROVIDER_ID,
                code,
                "Provider",
                providerType,
                "https://example.invalid",
                true,
                10,
                "HIGH",
                List.of("TITLE"),
                1000,
                3000,
                10000,
                20,
                true,
                "test",
                null,
                null,
                null,
                Instant.parse("2026-06-08T00:00:00Z"),
                Instant.parse("2026-06-08T00:00:00Z"));
    }
}
