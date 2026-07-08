package com.prodigalgal.ircs.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PortalInteractionControllerTest {

    private final InteractionQueryService queryService = org.mockito.Mockito.mock(InteractionQueryService.class);
    private final InteractionCommandService commandService = org.mockito.Mockito.mock(InteractionCommandService.class);
    private final MemberTokenService memberTokenService = org.mockito.Mockito.mock(MemberTokenService.class);
    private final PortalInteractionController controller =
            new PortalInteractionController(queryService, commandService, memberTokenService);

    @Test
    void togglesFavoriteForAuthenticatedMember() {
        UUID memberId = UUID.randomUUID();
        UUID unifiedVideoId = UUID.randomUUID();
        when(memberTokenService.requireMemberId("Bearer token")).thenReturn(memberId);
        when(commandService.toggleFavorite(memberId, unifiedVideoId)).thenReturn(true);

        Map<String, Boolean> response = controller.toggleFavorite("Bearer token", unifiedVideoId);

        assertEquals(Map.of("favorited", true), response);
        verify(commandService).toggleFavorite(memberId, unifiedVideoId);
    }

    @Test
    void returnsFavoriteStatusForAuthenticatedMember() {
        UUID memberId = UUID.randomUUID();
        UUID unifiedVideoId = UUID.randomUUID();
        when(memberTokenService.requireMemberId("Bearer token")).thenReturn(memberId);
        when(commandService.favoriteStatus(memberId, unifiedVideoId)).thenReturn(false);

        Map<String, Boolean> response = controller.favoriteStatus("Bearer token", unifiedVideoId);

        assertEquals(Map.of("favorited", false), response);
        verify(commandService).favoriteStatus(memberId, unifiedVideoId);
    }

    @Test
    void returnsHistoryForAuthenticatedMember() {
        UUID memberId = UUID.randomUUID();
        UUID historyId = UUID.randomUUID();
        InteractionRecordResponse record = new InteractionRecordResponse(
                historyId,
                UUID.randomUUID(),
                "影片",
                "https://example.invalid/poster.jpg",
                BigDecimal.valueOf(8.8),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "第1集",
                120,
                600,
                Instant.parse("2026-06-04T00:00:00Z"),
                true);
        PageBounds bounds = PageBounds.of(0, 20, 20, 70);
        PageResponse<InteractionRecordResponse> page = PageResponse.of(List.of(record), 1, bounds);
        when(memberTokenService.requireMemberId("Bearer token")).thenReturn(memberId);
        when(queryService.history(memberId, bounds)).thenReturn(page);

        PageEnvelope<InteractionRecordResponse> response = controller.history("Bearer token", 0, 20);

        assertPageEquals(page, response);
        verify(memberTokenService).requireMemberId("Bearer token");
        verify(queryService).history(memberId, bounds);
    }

    @Test
    void returnsFavoritesForAuthenticatedMember() {
        UUID memberId = UUID.randomUUID();
        InteractionRecordResponse record = new InteractionRecordResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "影片",
                "https://example.invalid/poster.jpg",
                BigDecimal.valueOf(8.8),
                null,
                null,
                null,
                0,
                0,
                Instant.parse("2026-06-04T00:00:00Z"),
                true);
        PageBounds bounds = PageBounds.of(1, 5, 20, 70);
        PageResponse<InteractionRecordResponse> page = PageResponse.of(List.of(record), 8, bounds);
        when(memberTokenService.requireMemberId("Bearer token")).thenReturn(memberId);
        when(queryService.favorites(memberId, bounds)).thenReturn(page);

        PageEnvelope<InteractionRecordResponse> response = controller.favorites("Bearer token", 1, 5);

        assertPageEquals(page, response);
        verify(memberTokenService).requireMemberId("Bearer token");
        verify(queryService).favorites(memberId, bounds);
    }

    @Test
    void reportsProgressForAuthenticatedMember() {
        UUID memberId = UUID.randomUUID();
        ProgressReportRequest request =
                new ProgressReportRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "第1集", 30, 600);
        when(memberTokenService.requireMemberId("Bearer token")).thenReturn(memberId);

        ResponseEntity<Void> response = controller.reportProgress("Bearer token", request);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(commandService).reportProgress(memberId, request);
    }

    @Test
    void clearsHistoryForAuthenticatedMember() {
        UUID memberId = UUID.randomUUID();
        when(memberTokenService.requireMemberId("Bearer token")).thenReturn(memberId);

        ResponseEntity<Void> response = controller.clearHistory("Bearer token");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(commandService).clearHistory(memberId);
    }

    @Test
    void deletesHistoryRecordForAuthenticatedMember() {
        UUID memberId = UUID.randomUUID();
        UUID historyId = UUID.randomUUID();
        when(memberTokenService.requireMemberId("Bearer token")).thenReturn(memberId);

        ResponseEntity<Void> response = controller.deleteHistoryRecord("Bearer token", historyId);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(commandService).deleteHistoryRecord(memberId, historyId);
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
