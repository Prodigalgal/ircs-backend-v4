package com.prodigalgal.ircs.ops.audit.worker;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkerJobAuditQueryService {

    private final WorkerJobAuditRepository repository;

    public Page<WorkerJobAuditEventResponse> findAll(
            Pageable pageable,
            String jobSource,
            String jobType,
            String jobName,
            String correlationId,
            String status,
            String errorClass,
            Instant from,
            Instant to) {
        return repository.findAll(pageable, jobSource, jobType, jobName, correlationId, status, errorClass, from, to);
    }

    public WorkerJobAuditSummaryResponse summarize() {
        return repository.summarize(Instant.now().minus(24, ChronoUnit.HOURS));
    }
}
