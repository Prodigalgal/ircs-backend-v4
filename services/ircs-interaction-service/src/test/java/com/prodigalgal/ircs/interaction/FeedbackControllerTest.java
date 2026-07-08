package com.prodigalgal.ircs.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class FeedbackControllerTest {

    private final InteractionQueryService queryService = org.mockito.Mockito.mock(InteractionQueryService.class);
    private final FeedbackCommandService commandService = org.mockito.Mockito.mock(FeedbackCommandService.class);
    private final MemberTokenService memberTokenService = org.mockito.Mockito.mock(MemberTokenService.class);
    private final FeedbackController controller =
            new FeedbackController(queryService, commandService, memberTokenService);

    @Test
    void submitsFeedbackForAuthenticatedMember() {
        UUID memberId = UUID.randomUUID();
        FeedbackSubmitRequest request = new FeedbackSubmitRequest("门户反馈");
        UserMessageResponse message = message(memberId, "门户反馈");
        when(memberTokenService.requireMemberId("Bearer token")).thenReturn(memberId);
        when(commandService.submit(memberId, request)).thenReturn(message);

        UserMessageResponse response = controller.submit("Bearer token", request);

        assertEquals(message, response);
        verify(memberTokenService).requireMemberId("Bearer token");
        verify(commandService).submit(memberId, request);
    }

    @Test
    void returnsMyFeedbackWithSafePageBounds() {
        UUID memberId = UUID.randomUUID();
        PageBounds bounds = PageBounds.of(2, 999, 10, 70);
        PageResponse<UserMessageResponse> page = PageResponse.of(List.of(message(memberId, "我的留言")), 71, bounds);
        when(memberTokenService.requireMemberId("Bearer token")).thenReturn(memberId);
        when(commandService.myFeedback(memberId, bounds)).thenReturn(page);

        PageEnvelope<UserMessageResponse> response = controller.myFeedback("Bearer token", 2, 999);

        assertPageEquals(page, response);
        verify(memberTokenService).requireMemberId("Bearer token");
        verify(commandService).myFeedback(memberId, bounds);
    }

    @Test
    void returnsPublicWallWithoutMemberEmail() {
        UUID messageId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UserMessageResponse message = new UserMessageResponse(
                messageId,
                memberId,
                "画外用户",
                null,
                "https://example.invalid/avatar.png",
                "公开留言",
                "回复内容",
                "REPLIED",
                true,
                Instant.parse("2026-06-04T00:00:00Z"),
                Instant.parse("2026-06-04T00:00:00Z"));
        PageBounds bounds = PageBounds.of(0, 20, 20, 70);
        PageResponse<UserMessageResponse> page = PageResponse.of(List.of(message), 1, bounds);
        when(queryService.publicWall(bounds)).thenReturn(page);

        PageEnvelope<UserMessageResponse> response = controller.publicWall(0, 20);

        assertPageEquals(page, response);
        assertNull(response.content().getFirst().memberEmail());
        verify(queryService).publicWall(bounds);
    }

    @Test
    void deletesOwnFeedbackWithNoContentResponse() {
        UUID memberId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(memberTokenService.requireMemberId("Bearer token")).thenReturn(memberId);

        ResponseEntity<Void> response = controller.delete("Bearer token", messageId);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(memberTokenService).requireMemberId("Bearer token");
        verify(commandService).delete(memberId, messageId);
    }

    private UserMessageResponse message(UUID memberId, String content) {
        return new UserMessageResponse(
                UUID.randomUUID(),
                memberId,
                "画外用户",
                "member@example.invalid",
                "https://example.invalid/avatar.png",
                content,
                null,
                "PENDING",
                false,
                Instant.parse("2026-06-04T00:00:00Z"),
                Instant.parse("2026-06-04T00:00:00Z"));
    }

    private static <T> void assertPageEquals(PageResponse<T> expected, PageEnvelope<T> actual) {
        assertEquals(expected.content(), actual.content());
        assertEquals(expected.number(), actual.page().number());
        assertEquals(expected.size(), actual.page().size());
        assertEquals(expected.totalElements(), actual.page().totalElements());
        assertEquals(expected.totalPages(), actual.page().totalPages());
        assertEquals(expected.first(), actual.page().first());
        assertEquals(expected.last(), actual.page().last());
        assertEquals(expected.content().size(), actual.page().numberOfElements());
        assertEquals(expected.empty(), actual.page().empty());
    }
}
