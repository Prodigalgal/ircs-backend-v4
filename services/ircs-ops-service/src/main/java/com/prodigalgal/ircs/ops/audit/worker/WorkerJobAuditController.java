package com.prodigalgal.ircs.ops.audit.worker;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ops/worker-job-audit")
public class WorkerJobAuditController {

    private final WorkerJobAuditQueryService workerJobAuditQueryService;

    @GetMapping
    public ResponseEntity<PageEnvelope<WorkerJobAuditEventResponse>> getAll(
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(name = "jobSource", required = false) String jobSource,
            @RequestParam(name = "jobType", required = false) String jobType,
            @RequestParam(name = "jobName", required = false) String jobName,
            @RequestParam(name = "correlationId", required = false) String correlationId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "errorClass", required = false) String errorClass,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(PageEnvelope.from(workerJobAuditQueryService.findAll(
                pageable,
                jobSource,
                jobType,
                jobName,
                correlationId,
                status,
                errorClass,
                from,
                to)));
    }

    @GetMapping("/summary")
    public ResponseEntity<WorkerJobAuditSummaryResponse> summarize() {
        return ResponseEntity.ok(workerJobAuditQueryService.summarize());
    }
}
