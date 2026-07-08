package com.prodigalgal.ircs.ops.traffic.controller;

import com.prodigalgal.ircs.ops.traffic.application.TrafficMonitorService;
import com.prodigalgal.ircs.ops.traffic.dto.TrafficStatusResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ops/traffic")
public class TrafficMonitorController {

    private final TrafficMonitorService trafficMonitorService;

    @GetMapping("/status")
    public ResponseEntity<TrafficStatusResponse> getStatus() {
        return ResponseEntity.ok(trafficMonitorService.getTrafficStatus());
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> resetKey(@RequestBody(required = false) Map<String, String> payload) {
        if (payload != null) {
            trafficMonitorService.resetKey(payload.get("key"));
        }
        return ResponseEntity.ok().build();
    }
}
