package com.prodigalgal.ircs.ops.audit.request;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RequestAuditQueryService {

    private final RequestAuditRepository repository;
    private final RequestAuditSummarySnapshotRepository summarySnapshotRepository;

    public Page<RequestAuditLogResponse> findAll(
            Pageable pageable,
            String username,
            String requestSource,
            String method,
            String path,
            Integer statusCode,
            String statusClass,
            String clientIp,
            Instant from,
            Instant to) {
        return repository.findAll(pageable, username, requestSource, method, path, statusCode, statusClass, clientIp, from, to);
    }

    public RequestAuditSummaryResponse summarize() {
        return usableSummarySnapshot()
                .map(RequestAuditSummarySnapshot::summary)
                .orElseGet(this::refreshSummarySnapshot);
    }

    RequestAuditSummaryResponse refreshSummarySnapshot() {
        RequestAuditSummaryResponse summary = repository.summarize(Instant.now().minus(24, ChronoUnit.HOURS));
        summarySnapshotRepository.save(summary, Instant.now());
        return summary;
    }

    private java.util.Optional<RequestAuditSummarySnapshot> usableSummarySnapshot() {
        java.util.Optional<RequestAuditSummarySnapshot> snapshot = summarySnapshotRepository.findUsable();
        return snapshot == null ? java.util.Optional.empty() : snapshot;
    }
}
