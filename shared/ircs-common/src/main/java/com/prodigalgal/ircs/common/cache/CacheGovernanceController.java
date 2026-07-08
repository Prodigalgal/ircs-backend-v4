package com.prodigalgal.ircs.common.cache;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/cache-governance")
public class CacheGovernanceController {

    private final CacheRegistry registry;

    @GetMapping
    public List<CacheSummary> summaries() {
        return registry.summaries();
    }

    @GetMapping("/{name}")
    public ResponseEntity<CacheSummary> summary(@PathVariable String name) {
        return registry.summary(name)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{name}")
    public CacheEvictResult evictAll(@PathVariable String name) {
        return registry.evictAll(name);
    }

    @DeleteMapping("/{name}/entries/{key}")
    public CacheEvictResult evictKey(@PathVariable String name, @PathVariable String key) {
        return registry.evictKey(name, key);
    }
}
