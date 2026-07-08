package com.prodigalgal.ircs.config.controller;

import java.time.ZoneId;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/common")
public class CommonController {

    private static final List<String> TIMEZONES = ZoneId.getAvailableZoneIds().stream()
            .sorted()
            .toList();

    @GetMapping("/timezones")
    public ResponseEntity<List<String>> getTimezones() {
        return ResponseEntity.ok(TIMEZONES);
    }
}
