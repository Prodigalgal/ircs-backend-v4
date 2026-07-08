package com.prodigalgal.ircs.interaction;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/portal/interaction")
public class PortalInteractionController {

    private final InteractionQueryService queryService;
    private final InteractionCommandService commandService;
    private final MemberTokenService memberTokenService;

    @PostMapping("/favorites/{unifiedVideoId}")
    public Map<String, Boolean> toggleFavorite(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID unifiedVideoId) {
        UUID memberId = memberTokenService.requireMemberId(authorization);
        return Map.of("favorited", commandService.toggleFavorite(memberId, unifiedVideoId));
    }

    @GetMapping("/favorites/{unifiedVideoId}/status")
    public Map<String, Boolean> favoriteStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID unifiedVideoId) {
        UUID memberId = memberTokenService.requireMemberId(authorization);
        return Map.of("favorited", commandService.favoriteStatus(memberId, unifiedVideoId));
    }

    @GetMapping("/history")
    public PageEnvelope<InteractionRecordResponse> history(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID memberId = memberTokenService.requireMemberId(authorization);
        return pageEnvelope(queryService.history(memberId, PageBounds.of(page, size, 20, 70)));
    }

    @GetMapping("/favorites")
    public PageEnvelope<InteractionRecordResponse> favorites(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID memberId = memberTokenService.requireMemberId(authorization);
        return pageEnvelope(queryService.favorites(memberId, PageBounds.of(page, size, 20, 70)));
    }

    @PostMapping("/history")
    public ResponseEntity<Void> reportProgress(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody ProgressReportRequest request) {
        UUID memberId = memberTokenService.requireMemberId(authorization);
        commandService.reportProgress(memberId, request);
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/history")
    public ResponseEntity<Void> clearHistory(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UUID memberId = memberTokenService.requireMemberId(authorization);
        commandService.clearHistory(memberId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/history/{id}")
    public ResponseEntity<Void> deleteHistoryRecord(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID id) {
        UUID memberId = memberTokenService.requireMemberId(authorization);
        commandService.deleteHistoryRecord(memberId, id);
        return ResponseEntity.noContent().build();
    }

    private <T> PageEnvelope<T> pageEnvelope(PageResponse<T> page) {
        return PageEnvelope.of(page.content(), page.number(), page.size(), page.totalElements());
    }
}
