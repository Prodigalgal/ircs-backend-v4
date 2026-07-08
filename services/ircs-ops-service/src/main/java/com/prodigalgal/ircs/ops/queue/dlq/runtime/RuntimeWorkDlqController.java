package com.prodigalgal.ircs.ops.queue.dlq.runtime;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ops/runtime-dlq")
class RuntimeWorkDlqController {

    private final RuntimeWorkDlqService dlqService;

    @GetMapping
    ResponseEntity<List<RuntimeWorkDlqQueueResponse>> listQueues(
            @RequestParam(name = "sampleLimit", defaultValue = "5") int sampleLimit) {
        return ResponseEntity.ok(dlqService.listQueues(sampleLimit));
    }

    @PostMapping("/requeue")
    ResponseEntity<RuntimeWorkDlqActionResponse> requeue(
            @RequestParam(name = "taskType") String taskType,
            @RequestParam(name = "limit", defaultValue = "1") int limit,
            @RequestParam(name = "maxReplayAttempts", defaultValue = "3") int maxReplayAttempts) {
        return ResponseEntity.ok(dlqService.requeue(taskType, limit, maxReplayAttempts));
    }
}
