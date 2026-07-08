package com.prodigalgal.ircs.ops.audit.request;

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

class RequestAuditControllerTest {

    private final RequestAuditQueryService service = org.mockito.Mockito.mock(RequestAuditQueryService.class);
    private final RequestAuditController controller = new RequestAuditController(service);

    @Test
    void returnsFilteredAuditPage() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
        Instant from = Instant.parse("2026-06-06T00:00:00Z");
        Page<RequestAuditLogResponse> page = new PageImpl<>(List.of(), pageable, 0);
        when(service.findAll(pageable, "admin", "ircs-config-service", "GET", "/api", null, "2xx", "127.0.0.1", from, null))
                .thenReturn(page);

        var response = controller.getAll(
                pageable,
                "admin",
                "ircs-config-service",
                "GET",
                "/api",
                null,
                "2xx",
                "127.0.0.1",
                from,
                null).getBody();
        assertEquals(page.getContent(), response.content());
        assertEquals(page.getTotalElements(), response.page().totalElements());
        assertEquals(page.getSize(), response.page().size());
        assertEquals(page.getNumber(), response.page().number());

        verify(service).findAll(pageable, "admin", "ircs-config-service", "GET", "/api", null, "2xx", "127.0.0.1", from, null);
    }

    @Test
    void returnsSummary() {
        RequestAuditSummaryResponse summary = new RequestAuditSummaryResponse(10, 2, 1, 4000L);
        when(service.summarize()).thenReturn(summary);

        assertEquals(summary, controller.summarize().getBody());
        verify(service).summarize();
    }
}
