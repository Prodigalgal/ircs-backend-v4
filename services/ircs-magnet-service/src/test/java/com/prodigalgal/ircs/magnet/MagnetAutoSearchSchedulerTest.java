package com.prodigalgal.ircs.magnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class MagnetAutoSearchSchedulerTest {

    private final JdbcMagnetRepository repository = org.mockito.Mockito.mock(JdbcMagnetRepository.class);
    private final MagnetQueryService queryService = org.mockito.Mockito.mock(MagnetQueryService.class);
    private final RuntimeConfigService runtimeConfig = org.mockito.Mockito.mock(RuntimeConfigService.class);
    private final MagnetAutoSearchScheduler scheduler = new MagnetAutoSearchScheduler(
            repository,
            queryService,
            runtimeConfigProvider(runtimeConfig));

    @Test
    void scheduledRunSkipsWhenDisabled() {
        when(runtimeConfig.booleanValue("app.magnet.auto-search.enabled", false)).thenReturn(false);

        scheduler.runScheduled();

        verify(repository, never()).findAutoSearchCandidates(anyInt(), any(Instant.class));
        verify(queryService, never()).enqueueAutomaticSearch(any());
    }

    @Test
    void runBatchTriggersEachCandidateWithConfiguredBatchSize() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ReflectionTestUtils.setField(scheduler, "batchSizeByDeployment", 10);
        when(runtimeConfig.boundedIntValue("app.magnet.auto-search.batch-size", 10, 1, 100)).thenReturn(2);
        when(runtimeConfig.positiveDurationValue(eq("app.magnet.auto-search.cooldown"), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(repository.findAutoSearchCandidates(eq(2), any(Instant.class))).thenReturn(List.of(first, second));

        int triggered = scheduler.runBatch();

        assertEquals(2, triggered);
        verify(queryService).enqueueAutomaticSearch(first);
        verify(queryService).enqueueAutomaticSearch(second);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RuntimeConfigService> runtimeConfigProvider(RuntimeConfigService runtimeConfig) {
        ObjectProvider<RuntimeConfigService> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(runtimeConfig);
        return provider;
    }
}
