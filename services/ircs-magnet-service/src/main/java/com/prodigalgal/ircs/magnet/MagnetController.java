package com.prodigalgal.ircs.magnet;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class MagnetController {

    private final MagnetQueryService magnetQueryService;

    @GetMapping("/magnet-providers")
    public ResponseEntity<List<MagnetProviderSummary>> listProviders() {
        return ResponseEntity.ok(magnetQueryService.listProviders());
    }

    @GetMapping("/magnet-providers/{id}")
    public ResponseEntity<MagnetProviderSummary> getProvider(@PathVariable(name = "id") UUID id) {
        return magnetQueryService.findProvider(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/magnet-providers")
    public ResponseEntity<MagnetProviderSummary> createProvider(@RequestBody MagnetProviderRequest request) {
        MagnetProviderSummary result = magnetQueryService.createProvider(request);
        return ResponseEntity.created(ServletUriComponentsBuilder
                        .fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(result.id())
                        .toUri())
                .body(result);
    }

    @PutMapping("/magnet-providers/{id}")
    public ResponseEntity<MagnetProviderSummary> updateProvider(
            @PathVariable(name = "id") UUID id,
            @RequestBody MagnetProviderRequest request) {
        return ResponseEntity.ok(magnetQueryService.updateProvider(id, request));
    }

    @GetMapping("/magnets/unified/{id}")
    public ResponseEntity<List<MagnetLinkSummary>> getUnifiedMagnets(@PathVariable(name = "id") UUID id) {
        return ResponseEntity.ok(magnetQueryService.findApprovedLinks(id));
    }

    @GetMapping("/magnets/unified/{id}/links")
    public ResponseEntity<List<MagnetLinkSummary>> getUnifiedMagnetLinks(@PathVariable(name = "id") UUID id) {
        return ResponseEntity.ok(magnetQueryService.findLinks(id));
    }

    @GetMapping("/magnets/unified/{id}/jobs")
    public ResponseEntity<List<MagnetSearchJobSummary>> getUnifiedMagnetJobs(
            @PathVariable(name = "id") UUID id,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ResponseEntity.ok(magnetQueryService.findSearchJobs(id, limit));
    }

    @GetMapping("/magnets/search-jobs/{id}/runs")
    public ResponseEntity<List<MagnetProviderRunSummary>> getMagnetProviderRuns(@PathVariable(name = "id") UUID id) {
        return ResponseEntity.ok(magnetQueryService.findProviderRuns(id));
    }

    @PostMapping("/magnets/search/unified/{id}")
    public ResponseEntity<MagnetSearchJobSummary> searchUnifiedMagnets(@PathVariable(name = "id") UUID id) {
        return ResponseEntity.ok(magnetQueryService.enqueueUnifiedSearch(id));
    }

    @PutMapping("/magnets/unified/{id}/links/{linkId}/status")
    public ResponseEntity<MagnetLinkSummary> updateUnifiedMagnetLinkStatus(
            @PathVariable(name = "id") UUID id,
            @PathVariable(name = "linkId") UUID linkId,
            @RequestBody MagnetLinkStatusRequest request) {
        return ResponseEntity.ok(magnetQueryService.updateLinkStatus(id, linkId, request));
    }

    @DeleteMapping("/magnets/unified/{id}/links/{linkId}")
    public ResponseEntity<Void> deleteUnifiedMagnetLink(
            @PathVariable(name = "id") UUID id,
            @PathVariable(name = "linkId") UUID linkId) {
        magnetQueryService.deleteLink(id, linkId);
        return ResponseEntity.noContent().build();
    }
}
