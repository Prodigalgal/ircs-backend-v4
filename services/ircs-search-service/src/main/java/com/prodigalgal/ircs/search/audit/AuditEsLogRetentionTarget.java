package com.prodigalgal.ircs.search.audit;

import com.prodigalgal.ircs.common.retention.LogRetentionResult;
import com.prodigalgal.ircs.common.retention.LogRetentionTarget;
import com.prodigalgal.ircs.search.index.SearchIndexService;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AuditEsLogRetentionTarget implements LogRetentionTarget {

    static final String TARGET_ID = "audit-es";

    private final SearchIndexService searchIndexService;

    @Override
    public String id() {
        return TARGET_ID;
    }

    @Override
    public LogRetentionResult deleteOlderThan(Instant cutoff, Duration retention) {
        long deleted = searchIndexService.deleteAuditOlderThan(cutoff);
        return new LogRetentionResult(TARGET_ID, cutoff, retention, deleted);
    }
}
