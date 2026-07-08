package com.prodigalgal.ircs.ops.audit.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class WorkerJobAuditControllerTest {

    private final WorkerJobAuditQueryService service = org.mockito.Mockito.mock(WorkerJobAuditQueryService.class);
    private final WorkerJobAuditController controller = new WorkerJobAuditController(service);

    @Test
    void returnsFilteredWorkerJobAuditPage() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
        Instant from = Instant.parse("2026-06-08T00:00:00Z");
        Page<WorkerJobAuditEventResponse> page = new PageImpl<>(List.of(), pageable, 0);
        when(service.findAll(
                        pageable,
                        "ircs-notification-worker",
                        "queue-consumer",
                        "notification.mail",
                        "mail-1",
                        "failed",
                        "IllegalStateException",
                        from,
                        null))
                .thenReturn(page);

        var response = controller.getAll(
                pageable,
                "ircs-notification-worker",
                "queue-consumer",
                "notification.mail",
                "mail-1",
                "failed",
                "IllegalStateException",
                from,
                null).getBody();
        assertEquals(page.getContent(), response.content());
        assertEquals(page.getTotalElements(), response.page().totalElements());
        assertEquals(page.getSize(), response.page().size());
        assertEquals(page.getNumber(), response.page().number());

        verify(service).findAll(
                pageable,
                "ircs-notification-worker",
                "queue-consumer",
                "notification.mail",
                "mail-1",
                "failed",
                "IllegalStateException",
                from,
                null);
    }

    @Test
    void returnsSummary() {
        WorkerJobAuditSummaryResponse summary = new WorkerJobAuditSummaryResponse(4, 1, 3, 900L);
        when(service.summarize()).thenReturn(summary);

        assertEquals(summary, controller.summarize().getBody());
        verify(service).summarize();
    }
}
