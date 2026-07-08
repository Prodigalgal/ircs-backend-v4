package com.prodigalgal.ircs.content.video.controller;



import com.prodigalgal.ircs.content.video.application.RawVideoAdminService;
import com.prodigalgal.ircs.content.video.api.ContentApiException;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.BatchRequest;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.IdResponse;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.RawVideoCardResponse;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.RawVideoCreateRequest;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.RawVideoDetailResponse;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.RawVideoUpdateRequest;
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
@RequestMapping("/api/v1/raw-videos")
@RequiredArgsConstructor
public class RawVideoController {

    private final RawVideoAdminService service;

    @GetMapping
    public ResponseEntity<PageEnvelope<RawVideoCardResponse>> getAllRawVideos(
            @PageableDefault(size = 20) Pageable pageable,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String enrichmentStatus,
            @RequestParam(required = false) String normalizationStatus,
            @RequestParam(required = false) String aggregationStatus,
            @RequestParam(required = false) String year,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) BigDecimal minScore,
            @RequestParam(required = false) Boolean isMissingSlug,
            @RequestParam(required = false) UUID dataSourceId,
            @RequestParam(required = false) String sourceCategoryName,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String language) {
        return ResponseEntity.ok(PageEnvelope.from(service.findAll(
                pageable,
                title,
                categoryId,
                enrichmentStatus,
                normalizationStatus,
                aggregationStatus,
                year,
                area,
                minScore,
                isMissingSlug,
                dataSourceId,
                sourceCategoryName,
                genre,
                language)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RawVideoDetailResponse> getRawVideo(@PathVariable UUID id) {
        return service.findOne(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<IdResponse> createRawVideo(@Valid @RequestBody RawVideoCreateRequest request) {
        UUID id = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/raw-videos/" + id)).body(new IdResponse(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> updateRawVideo(
            @PathVariable UUID id,
            @Valid @RequestBody RawVideoUpdateRequest request) {
        if (!Objects.equals(id, request.id())) {
            throw new ContentApiException(HttpStatus.BAD_REQUEST, "Invalid ID");
        }
        service.update(id, request);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVideo(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch/delete")
    public ResponseEntity<Void> batchDeleteVideos(@RequestBody BatchRequest request) {
        service.batchDelete(request.ids());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch/sync-search")
    public ResponseEntity<Void> batchSyncSearch(@RequestBody BatchRequest request) {
        service.batchSyncSearch(request.ids());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/renormalize")
    public ResponseEntity<Void> reNormalize(@PathVariable UUID id) {
        service.reNormalize(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/reenrich")
    public ResponseEntity<Void> reEnrich(@PathVariable UUID id) {
        service.reEnrich(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/refetch")
    public ResponseEntity<Void> reFetchFromSource(@PathVariable UUID id) {
        service.reFetchFromSource(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/batch/renormalize")
    public ResponseEntity<Void> batchReNormalize(@RequestBody BatchRequest request) {
        service.batchReNormalize(request.ids());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/batch/reenrich")
    public ResponseEntity<Void> batchReEnrich(@RequestBody BatchRequest request) {
        service.batchReEnrich(request.ids());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/batch/refetch")
    public ResponseEntity<Void> batchReFetch(@RequestBody BatchRequest request) {
        service.batchReFetch(request.ids());
        return ResponseEntity.accepted().build();
    }
}
