package com.prodigalgal.ircs.scraper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ScraperTrafficConfigValuesTest {

    private final SystemConfigRepository repository = org.mockito.Mockito.mock(SystemConfigRepository.class);

    @Test
    void readsSourceIpTrafficDefaultsFromSystemConfigRepository() {
        when(repository.findValue("global.traffic.safety-floor-ms")).thenReturn(Optional.of("3500"));
        when(repository.findValue("global.traffic.max-wait-ms")).thenReturn(Optional.of("180000"));
        when(repository.findValue("app.scraper.traffic.source-enabled")).thenReturn(Optional.of("false"));

        ScraperTrafficConfigValues values = new ScraperTrafficConfigValues(new MockEnvironment(), repository);

        assertThat(values.safetyFloor()).isEqualTo(Duration.ofMillis(3500));
        assertThat(values.maxWait(Duration.ofSeconds(1))).isEqualTo(Duration.ofMillis(180000));
        assertThat(values.sourceEnabled(true)).isFalse();
    }

    @Test
    void runtimeEnvironmentOverridesSystemConfigRepository() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("GLOBAL_TRAFFIC_SAFETY_FLOOR_MS", "1200")
                .withProperty("GLOBAL_TRAFFIC_MAX_WAIT_MS", "90000")
                .withProperty("APP_SCRAPER_TRAFFIC_SOURCE_ENABLED", "false");
        when(repository.findValue("global.traffic.safety-floor-ms")).thenReturn(Optional.of("3500"));
        when(repository.findValue("global.traffic.max-wait-ms")).thenReturn(Optional.of("180000"));
        when(repository.findValue("app.scraper.traffic.source-enabled")).thenReturn(Optional.of("true"));

        ScraperTrafficConfigValues values = new ScraperTrafficConfigValues(environment, repository);

        assertThat(values.safetyFloor()).isEqualTo(Duration.ofMillis(1200));
        assertThat(values.maxWait(Duration.ofSeconds(1))).isEqualTo(Duration.ofMillis(90000));
        assertThat(values.sourceEnabled(true)).isFalse();
    }
}
