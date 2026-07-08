package com.prodigalgal.ircs.ops.queue.dlq.persistence;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class DlqQueryServiceTest {

    private final DlqRepository repository = org.mockito.Mockito.mock(DlqRepository.class);
    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final DlqQueryService service = new DlqQueryService(repository, rabbitTemplate);

    @Test
    void retriesPendingMessageAndMarksRetried() {
        UUID id = UUID.randomUUID();
        FailedMessageResponse message = message(id, "PENDING");
        when(repository.findById(id)).thenReturn(Optional.of(message));

        service.retry(id);

        verify(rabbitTemplate).convertAndSend("x.test", "rk.test", "{\"ok\":true}");
        verify(repository).markRetried(id);
    }

    @Test
    void skipsNonPendingRetry() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(message(id, "DISCARDED")));

        service.retry(id);

        verify(rabbitTemplate, never()).convertAndSend(org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString());
        verify(repository, never()).markRetried(id);
    }

    @Test
    void delegatesBatchDiscard() {
        UUID id = UUID.randomUUID();

        service.batchDiscard(List.of(id));

        verify(repository).markDiscarded(id);
    }

    private FailedMessageResponse message(UUID id, String status) {
        return new FailedMessageResponse(
                id,
                Instant.now(),
                Instant.now(),
                0L,
                "q.test",
                "rk.test",
                "x.test",
                "{\"ok\":true}",
                "stack",
                0,
                status);
    }
}
