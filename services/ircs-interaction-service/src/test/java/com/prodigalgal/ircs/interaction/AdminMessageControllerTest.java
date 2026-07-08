package com.prodigalgal.ircs.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AdminMessageControllerTest {

    private final AdminMessageService service = org.mockito.Mockito.mock(AdminMessageService.class);
    private final AdminMessageController controller = new AdminMessageController(service);

    @Test
    void passesListParametersToService() {
        PageBounds bounds = PageBounds.of(2, 30, 20, 100);
        PageResponse<UserMessageResponse> expected = PageResponse.of(
                List.of(message("PENDING", false)),
                61,
                bounds);
        when(service.findAll("关键字", "PENDING", true, 2, 30)).thenReturn(expected);

        PageEnvelope<UserMessageResponse> response =
                controller.getAll("关键字", "PENDING", true, 2, 30, "createdAt,desc");

        assertPageEquals(expected, response);
        verify(service).findAll("关键字", "PENDING", true, 2, 30);
    }

    @Test
    void repliesToMessage() {
        UUID messageId = UUID.randomUUID();
        AdminReplyRequest request = new AdminReplyRequest("已收到");
        UserMessageResponse expected = message("REPLIED", false);
        when(service.reply(messageId, request)).thenReturn(expected);

        UserMessageResponse response = controller.reply(messageId, request);

        assertEquals(expected, response);
        verify(service).reply(messageId, request);
    }

    @Test
    void togglesVisibility() {
        UUID messageId = UUID.randomUUID();
        MessageVisibilityRequest request = new MessageVisibilityRequest(true);
        UserMessageResponse expected = message("PENDING", true);
        when(service.toggleVisibility(messageId, request)).thenReturn(expected);

        UserMessageResponse response = controller.toggleVisibility(messageId, request);

        assertEquals(expected, response);
        verify(service).toggleVisibility(messageId, request);
    }

    @Test
    void deletesMessageWithNoContentResponse() {
        UUID messageId = UUID.randomUUID();

        ResponseEntity<Void> response = controller.delete(messageId);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).delete(messageId);
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
