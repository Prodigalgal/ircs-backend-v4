package com.prodigalgal.ircs.common.web;

import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
public class ServiceInfoController {

    private final String applicationName;

    public ServiceInfoController(@Value("${spring.application.name:unknown}") String applicationName) {
        this.applicationName = applicationName;
    }

    @GetMapping("/service-info")
    public Map<String, Object> serviceInfo() {
        return Map.of(
                "service", applicationName,
                "status", "UP",
                "timestamp", Instant.now().toString()
        );
    }
}

