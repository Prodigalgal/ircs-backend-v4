package com.prodigalgal.ircs.interaction;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class InteractionQueryServiceTest {

    private final JdbcInteractionRepository repository = org.mockito.Mockito.mock(JdbcInteractionRepository.class);
    private final InteractionReadModelCache readModelCache = org.mockito.Mockito.mock(InteractionReadModelCache.class);
    private final InteractionQueryService service = new InteractionQueryService(repository, readModelCache);

    @Test
    void publicWallUsesReadModelCache() {
        PageBounds bounds = PageBounds.of(0, 20, 20, 70);
        PageResponse<UserMessageResponse> expected = PageResponse.of(List.of(message()), 1, bounds);
        when(repository.findPublicMessages(bounds)).thenReturn(expected);
        when(readModelCache.publicFeedbackWall(eq(bounds), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<PageResponse<UserMessageResponse>> loader = invocation.getArgument(1, Supplier.class);
            return loader.get();
        });

        PageResponse<UserMessageResponse> response = service.publicWall(bounds);

        assertSame(expected, response);
        verify(readModelCache).publicFeedbackWall(eq(bounds), any());
        verify(repository).findPublicMessages(bounds);
    }

    private UserMessageResponse message() {
        return new UserMessageResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "画外用户",
                "member@example.invalid",
                "https://example.invalid/avatar.png",
                "留言内容",
                null,
                "PENDING",
                true,
                Instant.parse("2026-06-04T00:00:00Z"),
                Instant.parse("2026-06-04T00:00:00Z"));
    }
}
