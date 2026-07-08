package com.prodigalgal.ircs.interaction;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/messages")
public class AdminMessageController {

    private final AdminMessageService service;

    @GetMapping
    public PageEnvelope<UserMessageResponse> getAll(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "public") Boolean publicMessage,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return pageEnvelope(service.findAll(keyword, status, publicMessage, page, size));
    }

    @PostMapping("/{id}/reply")
    public UserMessageResponse reply(
            @PathVariable UUID id,
            @RequestBody AdminReplyRequest request) {
        return service.reply(id, request);
    }

    @PutMapping("/{id}/visibility")
    public UserMessageResponse toggleVisibility(
            @PathVariable UUID id,
            @RequestBody MessageVisibilityRequest request) {
        return service.toggleVisibility(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    private <T> PageEnvelope<T> pageEnvelope(PageResponse<T> page) {
        return PageEnvelope.of(page.content(), page.number(), page.size(), page.totalElements());
    }
}
