package com.prodigalgal.ircs.ops.audit.notification;

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

class NotificationMailSendHistoryControllerTest {

    private final NotificationMailSendHistoryQueryService service =
            org.mockito.Mockito.mock(NotificationMailSendHistoryQueryService.class);
    private final NotificationMailSendHistoryController controller =
            new NotificationMailSendHistoryController(service);

    @Test
    void returnsFilteredSendHistoryPage() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
        Instant from = Instant.parse("2026-06-08T00:00:00Z");
        Page<NotificationMailSendHistoryResponse> page = new PageImpl<>(List.of(), pageable, 0);
        when(service.findAll(
                        pageable,
                        "SENT",
                        "SINK",
                        null,
                        "mail/activation",
                        "mail-1",
                        "codex",
                        from,
                        null))
                .thenReturn(page);

        var response = controller.getAll(
                pageable,
                "SENT",
                "SINK",
                null,
                "mail/activation",
                "mail-1",
                "codex",
                from,
                null).getBody();
        assertEquals(page.getContent(), response.content());
        assertEquals(page.getTotalElements(), response.page().totalElements());
        assertEquals(page.getSize(), response.page().size());
        assertEquals(page.getNumber(), response.page().number());

        verify(service).findAll(
                pageable,
                "SENT",
                "SINK",
                null,
                "mail/activation",
                "mail-1",
                "codex",
                from,
                null);
    }

    @Test
    void returnsSummary() {
        NotificationMailSendHistorySummaryResponse summary = new NotificationMailSendHistorySummaryResponse(
                3,
                1,
                1,
                1,
                "skipped",
                "sent",
                "failed");
        when(service.summarize()).thenReturn(summary);

        assertEquals(summary, controller.summarize().getBody());
        verify(service).summarize();
    }
}
