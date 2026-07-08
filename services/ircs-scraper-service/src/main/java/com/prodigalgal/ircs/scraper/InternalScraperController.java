package com.prodigalgal.ircs.scraper;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.prodigalgal.ircs.scraper.ScraperDtos.TaskExecutionRequest;
import com.prodigalgal.ircs.scraper.ScraperDtos.TaskExecutionResult;

@RestController
@RequestMapping("/internal/v1/scraper")
@RequiredArgsConstructor
class InternalScraperController {

    private final ManualScraperService manualScraperService;

    @PostMapping("/raw-videos/{id}/refetch")
    ResponseEntity<Void> refetchRawVideo(@PathVariable(name = "id") UUID id) {
        manualScraperService.refetchRawVideo(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/task-executions")
    @ResponseStatus(HttpStatus.OK)
    TaskExecutionResult executeCollectionTask(@RequestBody TaskExecutionRequest request) {
        return manualScraperService.executeCollectionTask(request);
    }
}
