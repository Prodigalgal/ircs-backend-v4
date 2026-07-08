package com.prodigalgal.ircs.content.video.controller;



import com.prodigalgal.ircs.content.video.api.ContentApiException;
import com.prodigalgal.ircs.content.video.application.UnifiedVideoAdminService;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.BatchRequest;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.UnifiedVideoCardResponse;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.UnifiedVideoCreateRequest;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.UnifiedVideoDetailResponse;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.UnifiedVideoUpdateRequest;
import com.prodigalgal.ircs.common.web.PageEnvelope;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/unified-videos")
@RequiredArgsConstructor
public class UnifiedVideoController {

    private final UnifiedVideoAdminService service;

    @GetMapping
    public ResponseEntity<PageEnvelope<UnifiedVideoCardResponse>> getAll(
            @PageableDefault(size = 20) Pageable pageable,
            @RequestParam(name = "title", required = false) String title,
            @RequestParam(name = "categoryId", required = false) UUID categoryId,
            @RequestParam(name = "year", required = false) String year,
            @RequestParam(name = "area", required = false) String area,
            @RequestParam(name = "minScore", required = false) BigDecimal minScore,
            @RequestParam(name = "hasDoubanId", required = false) Boolean hasDoubanId,
            @RequestParam(name = "hasTmdbId", required = false) Boolean hasTmdbId,
            @RequestParam(name = "contentVisibility", required = false) String contentVisibility,
            @RequestParam(name = "metadataStatus", required = false) String metadataStatus,
            @RequestParam(name = "genre", required = false) String genre,
            @RequestParam(name = "language", required = false) String language,
            @RequestParam(name = "actor", required = false) String actor,
            @RequestParam(name = "director", required = false) String director) {
        return ResponseEntity.ok(PageEnvelope.from(service.findAll(
                pageable,
                title,
                categoryId,
                year,
                area,
                minScore,
                hasDoubanId,
                hasTmdbId,
                contentVisibility,
                metadataStatus,
                genre,
                language,
                actor,
                director)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UnifiedVideoDetailResponse> getOne(@PathVariable("id") UUID id) {
        return service.findOne(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<UnifiedVideoDetailResponse> create(@Valid @RequestBody UnifiedVideoCreateRequest request) {
        UnifiedVideoDetailResponse result = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/unified-videos/" + result.id())).body(result);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UnifiedVideoDetailResponse> update(
            @PathVariable("id") UUID id,
            @Valid @RequestBody UnifiedVideoUpdateRequest request) {
        if (!Objects.equals(id, request.id())) {
            throw new ContentApiException(HttpStatus.BAD_REQUEST, "Invalid ID");
        }
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch/delete")
    public ResponseEntity<Void> batchDelete(@RequestBody BatchRequest request) {
        service.batchDelete(request.ids());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch/sync-search")
    public ResponseEntity<Void> batchSyncSearch(@RequestBody BatchRequest request) {
        service.batchSyncSearch(request.ids());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/recalculate")
    public ResponseEntity<UnifiedVideoDetailResponse> recalculateMetadata(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(service.recalculateMetadata(id));
    }
}
