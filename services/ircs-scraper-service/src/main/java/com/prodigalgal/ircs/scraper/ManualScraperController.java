package com.prodigalgal.ircs.scraper;

import com.prodigalgal.ircs.scraper.ScraperDtos.InitSessionResponse;
import com.prodigalgal.ircs.scraper.ScraperDtos.ManualScrapeConfigRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/scraper/manual")
@RequiredArgsConstructor
class ManualScraperController {

    private final ManualScraperService manualScraperService;

    @PostMapping("/init")
    ResponseEntity<InitSessionResponse> initSession(@Valid @RequestBody ManualScrapeConfigRequest request) {
        return ResponseEntity.ok(new InitSessionResponse(manualScraperService.initSession(request).toString()));
    }

    @GetMapping("/stream/{sessionId}")
    SseEmitter stream(@PathVariable UUID sessionId) {
        return manualScraperService.stream(sessionId);
    }
}
