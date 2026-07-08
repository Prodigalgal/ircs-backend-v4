package com.prodigalgal.ircs.ops.audit.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RequestAuditQueryServiceTest {

    private final RequestAuditRepository repository = org.mockito.Mockito.mock(RequestAuditRepository.class);
    private final RequestAuditSummarySnapshotRepository snapshotRepository =
            org.mockito.Mockito.mock(RequestAuditSummarySnapshotRepository.class);
    private final RequestAuditQueryService service = new RequestAuditQueryService(repository, snapshotRepository);

    @Test
    void summarizeUsesSnapshotBeforeLiveQuery() {
        RequestAuditSummaryResponse summary = new RequestAuditSummaryResponse(10, 1, 2, 3000L);
        when(snapshotRepository.findUsable()).thenReturn(Optional.of(new RequestAuditSummarySnapshot(
                summary,
                Instant.now(),
                Instant.now().plusSeconds(30),
                Instant.now().plusSeconds(300),
                "database")));

        RequestAuditSummaryResponse response = service.summarize();

        assertEquals(summary, response);
        verify(repository, times(0)).summarize(any());
    }

    @Test
    void summarizeRefreshesSnapshotWhenMissing() {
        RequestAuditSummaryResponse summary = new RequestAuditSummaryResponse(20, 2, 4, 6000L);
        when(snapshotRepository.findUsable()).thenReturn(Optional.empty());
        when(repository.summarize(any())).thenReturn(summary);

        RequestAuditSummaryResponse response = service.summarize();

        assertEquals(summary, response);
        verify(repository).summarize(any());
        verify(snapshotRepository).save(eq(summary), any());
    }
}
