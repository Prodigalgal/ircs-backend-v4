package com.prodigalgal.ircs.task.controller;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import com.prodigalgal.ircs.task.application.MediaRequestBatchService;
import com.prodigalgal.ircs.task.dto.MediaRequestBatchActionResponse;
import com.prodigalgal.ircs.task.dto.MediaRequestBatchResponse;
import com.prodigalgal.ircs.task.dto.TaskCardSummary;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/media-request-batches")
public class MediaRequestBatchController {

    private final MediaRequestBatchService batchService;

    @GetMapping
    public ResponseEntity<PageEnvelope<MediaRequestBatchResponse>> list(
            @PageableDefault(size = 20) Pageable pageable,
            @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok(PageEnvelope.from(batchService.findBatches(pageable, status)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MediaRequestBatchResponse> get(@PathVariable(name = "id") UUID id) {
        return ResponseEntity.ok(batchService.findBatch(id));
    }

    @GetMapping("/{id}/collection-tasks")
    public ResponseEntity<PageEnvelope<TaskCardSummary>> collectionTasks(
            @PathVariable(name = "id") UUID id,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(PageEnvelope.from(batchService.findCollectionTasks(id, pageable)));
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<MediaRequestBatchActionResponse> start(@PathVariable(name = "id") UUID id) {
        return ResponseEntity.accepted().body(batchService.startBatch(id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<MediaRequestBatchActionResponse> cancel(@PathVariable(name = "id") UUID id) {
        return ResponseEntity.accepted().body(batchService.cancelBatch(id));
    }
}
