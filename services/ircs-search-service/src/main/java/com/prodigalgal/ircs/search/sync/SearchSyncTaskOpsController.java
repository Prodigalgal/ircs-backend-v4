package com.prodigalgal.ircs.search.sync;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/search/ops/search-sync-tasks")
public class SearchSyncTaskOpsController {

    private final SearchSyncTaskOpsRepository repository;

    @GetMapping("/stats")
    public ResponseEntity<SearchSyncTaskStatsResponse> stats() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(repository.stats());
    }
}
