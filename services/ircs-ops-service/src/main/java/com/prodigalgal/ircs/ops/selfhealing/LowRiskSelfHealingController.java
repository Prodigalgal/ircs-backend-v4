package com.prodigalgal.ircs.ops.selfhealing;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ops/self-healing/low-risk")
public class LowRiskSelfHealingController {

    private final LowRiskSelfHealingService selfHealingService;

    @PostMapping("/run")
    public ResponseEntity<LowRiskHealingResponse> run(@RequestBody LowRiskHealingRequest request) {
        return ResponseEntity.ok(selfHealingService.run(request));
    }
}
