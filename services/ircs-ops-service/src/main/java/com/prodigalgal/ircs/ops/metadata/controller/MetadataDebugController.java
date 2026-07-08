package com.prodigalgal.ircs.ops.metadata.controller;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/debug/metadata")
public class MetadataDebugController {

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam(name = "title") String title,
            @RequestParam(name = "year", required = false) String year,
            @RequestParam(name = "subtitle", required = false) String subtitle,
            @RequestParam(name = "providerType", defaultValue = "DOUBAN") String providerType) {
        log.info("Metadata debug dry-run: provider={}, title={}, year={}", providerType, title, year);
        return ResponseEntity.ok(Map.of(
                "status", "DRY_RUN",
                "providerType", providerType,
                "duration_ms", 0,
                "message", "dev guarded contract: external metadata providers are not called",
                "context", Map.of(
                        "title", title,
                        "year", year == null ? "" : year,
                        "subtitle", subtitle == null ? "" : subtitle)));
    }
}
