package com.prodigalgal.ircs.interaction;

import com.prodigalgal.ircs.common.web.PageEnvelope;
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
@RequestMapping("/api/portal/feedback")
public class FeedbackController {

    private final InteractionQueryService queryService;
    private final FeedbackCommandService commandService;
    private final MemberTokenService memberTokenService;

    @PostMapping
    public UserMessageResponse submit(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody FeedbackSubmitRequest request) {
        UUID memberId = memberTokenService.requireMemberId(authorization);
        return commandService.submit(memberId, request);
    }

    @GetMapping
    public PageEnvelope<UserMessageResponse> myFeedback(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        UUID memberId = memberTokenService.requireMemberId(authorization);
        return pageEnvelope(commandService.myFeedback(memberId, PageBounds.of(page, size, 10, 70)));
    }

    @GetMapping("/wall")
    public PageEnvelope<UserMessageResponse> publicWall(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return pageEnvelope(queryService.publicWall(PageBounds.of(page, size, 20, 70)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID id) {
        UUID memberId = memberTokenService.requireMemberId(authorization);
        commandService.delete(memberId, id);
        return ResponseEntity.noContent().build();
    }

    private <T> PageEnvelope<T> pageEnvelope(PageResponse<T> page) {
        return PageEnvelope.of(page.content(), page.number(), page.size(), page.totalElements());
    }
}
