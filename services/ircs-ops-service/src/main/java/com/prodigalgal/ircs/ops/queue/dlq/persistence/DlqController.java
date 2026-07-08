package com.prodigalgal.ircs.ops.queue.dlq.persistence;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ops/dlq")
public class DlqController {

    private final DlqQueryService dlqQueryService;

    @GetMapping
    public ResponseEntity<PageEnvelope<FailedMessageResponse>> getAll(
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "queueName", required = false) String queueName,
            @RequestParam(name = "keyword", required = false) String keyword) {
        return ResponseEntity.ok(PageEnvelope.from(dlqQueryService.findAll(pageable, status, queueName, keyword)));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable(name = "id") UUID id) {
        dlqQueryService.retry(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/batch-retry")
    public ResponseEntity<Void> batchRetry(@RequestBody BatchRequest request) {
        dlqQueryService.batchRetry(request.ids());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> discard(@PathVariable(name = "id") UUID id) {
        dlqQueryService.discard(id);
        return ResponseEntity.status(HttpStatus.OK).build();
    }

    @PostMapping("/batch-discard")
    public ResponseEntity<Void> batchDiscard(@RequestBody BatchRequest request) {
        dlqQueryService.batchDiscard(request.ids());
        return ResponseEntity.ok().build();
    }

    public record BatchRequest(List<UUID> ids) {
    }
}
