package com.prodigalgal.ircs.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class AdminMessageServiceTest {

    private final JdbcAdminMessageRepository repository = org.mockito.Mockito.mock(JdbcAdminMessageRepository.class);
    private final InteractionReadModelCache readModelCache = org.mockito.Mockito.mock(InteractionReadModelCache.class);
    private final AdminMessageService service = new AdminMessageService(repository, readModelCache);

    @Test
    void findsMessagesWithNormalizedBoundsKeywordAndStatus() {
        PageBounds bounds = PageBounds.of(1, 999, 20, 100);
        PageResponse<UserMessageResponse> expected = PageResponse.of(List.of(message("PENDING", false)), 101, bounds);
        when(repository.findMessages(eq(bounds), eq(Optional.of("用户")), eq(Optional.of("REPLIED")), eq(Optional.of(true))))
                .thenReturn(expected);

        PageResponse<UserMessageResponse> response = service.findAll(" 用户 ", "replied", true, 1, 999);

        assertSame(expected, response);
        verify(repository).findMessages(bounds, Optional.of("用户"), Optional.of("REPLIED"), Optional.of(true));
    }

    @Test
    void rejectsInvalidStatusBeforeRepositoryQuery() {
        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.findAll(null, "UNKNOWN", null, 0, 20));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertEquals("留言状态无效", exception.getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    void repliesWithTrimmedContent() {
        UUID messageId = UUID.randomUUID();
        UserMessageResponse expected = message("REPLIED", false);
        when(repository.reply(eq(messageId), eq("已收到"), any(Instant.class))).thenReturn(Optional.of(expected));

        UserMessageResponse response = service.reply(messageId, new AdminReplyRequest(" 已收到 "));

        assertSame(expected, response);
        ArgumentCaptor<String> replyCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).reply(eq(messageId), replyCaptor.capture(), any(Instant.class));
        verify(readModelCache).evictPublicFeedbackWall();
        assertEquals("已收到", replyCaptor.getValue());
    }

    @Test
    void rejectsBlankReply() {
        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.reply(UUID.randomUUID(), new AdminReplyRequest(" ")));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertEquals("回复内容不能为空", exception.getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsMissingVisibilityField() {
        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.toggleVisibility(UUID.randomUUID(), new MessageVisibilityRequest(null)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertEquals("public field missing", exception.getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    void togglesVisibilityThroughRepository() {
        UUID messageId = UUID.randomUUID();
        UserMessageResponse expected = message("PENDING", true);
        when(repository.toggleVisibility(eq(messageId), eq(true), any(Instant.class))).thenReturn(Optional.of(expected));

        UserMessageResponse response = service.toggleVisibility(messageId, new MessageVisibilityRequest(true));

        assertSame(expected, response);
        verify(repository).toggleVisibility(eq(messageId), eq(true), any(Instant.class));
        verify(readModelCache).evictPublicFeedbackWall();
    }

    @Test
    void deletesExistingMessage() {
        UUID messageId = UUID.randomUUID();
        when(repository.delete(messageId)).thenReturn(1);

        service.delete(messageId);

        verify(repository).delete(messageId);
        verify(readModelCache).evictPublicFeedbackWall();
    }

    @Test
    void deleteReportsMissingMessage() {
        UUID messageId = UUID.randomUUID();
        when(repository.delete(messageId)).thenReturn(0);

        ApiException exception = assertThrows(ApiException.class, () -> service.delete(messageId));

        assertEquals(HttpStatus.NOT_FOUND, exception.status());
        assertEquals("留言不存在", exception.getMessage());
    }

    private UserMessageResponse message(String status, boolean isPublic) {
        return new UserMessageResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "画外用户",
                "member@example.invalid",
                "https://example.invalid/avatar.png",
                "留言内容",
                null,
                status,
                isPublic,
                Instant.parse("2026-06-04T00:00:00Z"),
                Instant.parse("2026-06-04T00:00:00Z"));
    }
}
