package com.prodigalgal.ircs.ops.queue.dlq.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class DlqControllerTest {

    private final DlqQueryService service = org.mockito.Mockito.mock(DlqQueryService.class);
    private final DlqController controller = new DlqController(service);

    @Test
    void returnsFilteredDlqPage() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<FailedMessageResponse> page = new PageImpl<>(List.of(), pageable, 0);
        when(service.findAll(pageable, "PENDING", "q.storage", "timeout")).thenReturn(page);

        var response = controller.getAll(pageable, "PENDING", "q.storage", "timeout").getBody();
        assertEquals(page.getContent(), response.content());
        assertEquals(page.getTotalElements(), response.page().totalElements());
        assertEquals(page.getSize(), response.page().size());
        assertEquals(page.getNumber(), response.page().number());
        verify(service).findAll(pageable, "PENDING", "q.storage", "timeout");
    }

    @Test
    void delegatesRetryAndDiscardCommands() {
        UUID id = UUID.randomUUID();

        assertEquals(200, controller.retry(id).getStatusCode().value());
        assertEquals(200, controller.discard(id).getStatusCode().value());

        verify(service).retry(id);
        verify(service).discard(id);
    }

    @Test
    void delegatesBatchCommands() {
        UUID id = UUID.randomUUID();
        DlqController.BatchRequest request = new DlqController.BatchRequest(List.of(id));

        assertEquals(200, controller.batchRetry(request).getStatusCode().value());
        assertEquals(200, controller.batchDiscard(request).getStatusCode().value());

        verify(service).batchRetry(List.of(id));
        verify(service).batchDiscard(List.of(id));
    }
}
