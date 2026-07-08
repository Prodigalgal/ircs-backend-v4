package com.prodigalgal.ircs.storage.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class StorageR2HealthIndicatorTest {

    private final R2ObjectStorage r2ObjectStorage = org.mockito.Mockito.mock(R2ObjectStorage.class);

    @Test
    void reportsUpDisabledWhenR2GateIsOff() {
        StorageR2HealthIndicator indicator = new StorageR2HealthIndicator(
                r2ObjectStorage,
                false,
                "ircs",
                "img.example.test");

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("status", "DISABLED_BY_CONFIG")
                .containsEntry("enabled", false);
        verify(r2ObjectStorage, never()).isActive();
    }

    @Test
    void reportsUpWhenEnabledAndR2StorageIsActive() {
        when(r2ObjectStorage.isActive()).thenReturn(true);
        StorageR2HealthIndicator indicator = new StorageR2HealthIndicator(
                r2ObjectStorage,
                true,
                "ircs",
                "img.example.test");

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("enabled", true)
                .containsEntry("bucket", "ircs")
                .containsEntry("domain", "img.example.test")
                .containsEntry("connectivity", "OK");
    }

    @Test
    void reportsDownWhenEnabledButR2StorageIsInactive() {
        when(r2ObjectStorage.isActive()).thenReturn(false);
        StorageR2HealthIndicator indicator = new StorageR2HealthIndicator(
                r2ObjectStorage,
                true,
                "",
                "");

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("enabled", true)
                .containsEntry("bucket", "not-configured")
                .containsEntry("domain", "not-configured")
                .containsEntry("reason", "R2 storage is enabled but credentials or endpoint are incomplete");
    }
}
