package com.prodigalgal.ircs.metadata.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class MetadataConfigValuesTest {

    private final SystemConfigRepository repository = org.mockito.Mockito.mock(SystemConfigRepository.class);

    @Test
    void usesCurrentDefaultsWhenNoRuntimeOrDbConfigExists() {
        MetadataConfigValues values = values(new MockEnvironment());

        assertTrue(values.tmdbEnabled());
        assertTrue(values.doubanEnabled());
        assertTrue(values.rottenTomatoesEnabled());
        assertEquals(Duration.ofMinutes(5), values.retryDelay());
    }

    @Test
    void dbCanonicalKeysOverrideDefaults() {
        when(repository.findValue(MetadataConfigValues.TMDB_ENABLED_KEY)).thenReturn(Optional.of("false"));
        when(repository.findValue(MetadataConfigValues.DOUBAN_ENABLED_KEY)).thenReturn(Optional.of("false"));
        when(repository.findValue(MetadataConfigValues.RT_ENABLED_KEY)).thenReturn(Optional.of("false"));

        MetadataConfigValues values = values(new MockEnvironment());

        assertFalse(values.tmdbEnabled());
        assertFalse(values.doubanEnabled());
        assertFalse(values.rottenTomatoesEnabled());
    }

    @Test
    void runtimeAliasesOverrideDbCanonicalKeysForProviderFlagsAndRetryDelay() {
        when(repository.findValue(MetadataConfigValues.TMDB_ENABLED_KEY)).thenReturn(Optional.of("true"));
        when(repository.findValue(MetadataConfigValues.DOUBAN_ENABLED_KEY)).thenReturn(Optional.of("true"));
        when(repository.findValue(MetadataConfigValues.RT_ENABLED_KEY)).thenReturn(Optional.of("true"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_METADATA_TMDB_ENABLED", "false")
                .withProperty("APP_METADATA_DOUBAN_ENABLED", "false")
                .withProperty("APP_METADATA_RT_ENABLED", "false")
                .withProperty("APP_METADATA_RETRY_DELAY", "15m");

        MetadataConfigValues values = values(environment);

        assertFalse(values.tmdbEnabled());
        assertFalse(values.doubanEnabled());
        assertFalse(values.rottenTomatoesEnabled());
        assertEquals(Duration.ofMinutes(15), values.retryDelay());
    }

    @Test
    void providerTypeSwitchUsesSameDynamicValues() {
        when(repository.findValue(MetadataConfigValues.TMDB_ENABLED_KEY)).thenReturn(Optional.of("false"));
        MetadataConfigValues values = values(new MockEnvironment());

        assertFalse(values.isProviderEnabled(ProviderType.TMDB));
        assertTrue(values.isProviderEnabled(ProviderType.DOUBAN));
    }

    @Test
    void configListenerStartupFlagDoesNotOverrideProviderSwitches() {
        when(repository.findValue(MetadataConfigValues.TMDB_ENABLED_KEY)).thenReturn(Optional.of("false"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_METADATA_CONFIG_LISTENER_ENABLED", "true");

        MetadataConfigValues values = values(environment);

        assertFalse(values.tmdbEnabled());
    }

    @Test
    void invalidValuesFallBackToCurrentDefaults() {
        when(repository.findValue(MetadataConfigValues.TMDB_ENABLED_KEY)).thenReturn(Optional.of("bad"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_METADATA_RETRY_DELAY", "bad");

        MetadataConfigValues values = values(environment);

        assertTrue(values.tmdbEnabled());
        assertEquals(Duration.ofMinutes(5), values.retryDelay());
    }

    private MetadataConfigValues values(MockEnvironment environment) {
        return new MetadataConfigValues(environment, repository);
    }
}
