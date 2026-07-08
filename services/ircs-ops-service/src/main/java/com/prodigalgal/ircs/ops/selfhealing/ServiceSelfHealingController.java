package com.prodigalgal.ircs.ops.selfhealing;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ops/self-healing/service-restart")
public class ServiceSelfHealingController {

    private final ServiceSelfHealingService selfHealingService;

    @PostMapping("/run")
    public ResponseEntity<ServiceRestartHealingResponse> run(@RequestBody ServiceRestartHealingRequest request) {
        return ResponseEntity.ok(selfHealingService.restart(request));
    }
}
