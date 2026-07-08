package com.prodigalgal.ircs.search.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.retention.LogRetentionResult;
import com.prodigalgal.ircs.search.index.SearchIndexService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuditEsLogRetentionTargetTest {

    private final SearchIndexService searchIndexService = org.mockito.Mockito.mock(SearchIndexService.class);

    @Test
    void deletesAuditEsDocumentsOlderThanCutoff() {
        Instant cutoff = Instant.parse("2026-05-21T00:00:00Z");
        Duration retention = Duration.ofDays(30);
        when(searchIndexService.deleteAuditOlderThan(cutoff)).thenReturn(5L);
        AuditEsLogRetentionTarget target = new AuditEsLogRetentionTarget(searchIndexService);

        LogRetentionResult result = target.deleteOlderThan(cutoff, retention);

        assertThat(result.targetId()).isEqualTo("audit-es");
        assertThat(result.deletedCount()).isEqualTo(5L);
        assertThat(result.cutoff()).isEqualTo(cutoff);
        verify(searchIndexService).deleteAuditOlderThan(cutoff);
    }
}
