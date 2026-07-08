package com.prodigalgal.ircs.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

class RuntimeConfigServiceTest {

    private final RuntimeConfigValueSource valueSource = mock(RuntimeConfigValueSource.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<RuntimeConfigValueSource> valueSourceProvider = mock(ObjectProvider.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final RuntimeConfigService service = new RuntimeConfigService(valueSourceProvider, environment);

    @Test
    void databaseValueWinsOverInjectedValue() {
        sources(valueSource);
        environment.setProperty("app.demo.enabled", "true");
        when(valueSource.findValue("app.demo.enabled")).thenReturn(Optional.of("false"));

        assertThat(service.booleanValue("app.demo.enabled", true)).isFalse();
    }

    @Test
    void injectedValueFallsBackWhenDatabaseValueIsBlank() {
        sources(valueSource);
        environment.setProperty("APP_DEMO_INTERVAL", "PT9S");
        when(valueSource.findValue("app.demo.interval")).thenReturn(Optional.of(""));

        assertThat(service.durationValue("app.demo.interval", Duration.ofSeconds(1)))
                .isEqualTo(Duration.ofSeconds(9));
    }

    @Test
    void invalidTypedValueFallsBack() {
        sources(valueSource);
        when(valueSource.findValue("app.demo.limit")).thenReturn(Optional.of("not-a-number"));

        assertThat(service.intValue("app.demo.limit", 12)).isEqualTo(12);
    }

    @Test
    void injectedValueStillWorksWhenDatabaseSourceIsUnavailable() {
        sources();
        environment.setProperty("APP_DEMO_LIMIT", "25");

        assertThat(service.intValue("app.demo.limit", 12)).isEqualTo(25);
    }

    @Test
    void multipleDatabaseSourcesAreReadInOrder() {
        RuntimeConfigValueSource first = mock(RuntimeConfigValueSource.class);
        RuntimeConfigValueSource second = mock(RuntimeConfigValueSource.class);
        sources(first, second);
        when(first.findValue("app.demo.enabled")).thenReturn(Optional.empty());
        when(second.findValue("app.demo.enabled")).thenReturn(Optional.of("false"));

        assertThat(service.booleanValue("app.demo.enabled", true)).isFalse();
    }

    @Test
    void blankDatabaseValueFallsThroughToNextSource() {
        RuntimeConfigValueSource first = mock(RuntimeConfigValueSource.class);
        RuntimeConfigValueSource second = mock(RuntimeConfigValueSource.class);
        sources(first, second);
        when(first.findValue("app.demo.enabled")).thenReturn(Optional.of(""));
        when(second.findValue("app.demo.enabled")).thenReturn(Optional.of("false"));

        assertThat(service.booleanValue("app.demo.enabled", true)).isFalse();
    }

    @Test
    void evictNotifiesAllDatabaseSources() {
        RuntimeConfigValueSource first = mock(RuntimeConfigValueSource.class);
        RuntimeConfigValueSource second = mock(RuntimeConfigValueSource.class);
        sources(first, second);

        service.evict("app.demo.enabled");

        verify(first).evict("app.demo.enabled");
        verify(second).evict("app.demo.enabled");
    }

    private void sources(RuntimeConfigValueSource... sources) {
        when(valueSourceProvider.orderedStream()).thenAnswer(invocation -> Arrays.stream(sources));
    }
}
