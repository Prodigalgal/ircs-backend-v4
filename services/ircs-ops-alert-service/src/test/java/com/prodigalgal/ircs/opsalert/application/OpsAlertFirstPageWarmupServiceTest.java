package com.prodigalgal.ircs.opsalert.application;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OpsAlertFirstPageWarmupServiceTest {

    private final OpsAlertQueryService queryService = org.mockito.Mockito.mock(OpsAlertQueryService.class);
    private final RuntimeConfigService runtimeConfig = org.mockito.Mockito.mock(RuntimeConfigService.class);
    private final OpsAlertFirstPageWarmupService service =
            new OpsAlertFirstPageWarmupService(queryService, runtimeConfig);

    @Test
    void warmupUsesConfiguredPageSize() {
        when(runtimeConfig.booleanValue("app.ops-alert.first-page-cache.warmup.enabled", true)).thenReturn(true);
        when(runtimeConfig.positiveDurationValue(
                "app.ops-alert.first-page-cache.warmup.refresh-interval",
                Duration.ofSeconds(15))).thenReturn(Duration.ofSeconds(15));
        when(runtimeConfig.boundedIntValue("app.ops-alert.first-page-cache.warmup.page-size", 20, 1, 100))
                .thenReturn(30);

        service.warmupNow();

        verify(queryService).warmFirstPages(eq(30));
    }

    @Test
    void scheduleSkipsWhenDisabled() {
        when(runtimeConfig.booleanValue("app.ops-alert.first-page-cache.warmup.enabled", true)).thenReturn(false);

        service.warmupOnSchedule();

        verify(queryService, times(0)).warmFirstPages(org.mockito.ArgumentMatchers.anyInt());
    }
}
