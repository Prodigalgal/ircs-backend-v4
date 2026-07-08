package com.prodigalgal.ircs.storage.image;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.CoverImageResponse;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.UrlRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/cover-images")
@RequiredArgsConstructor
public class CoverImageController {

    private final CoverImageAdminService service;

    @GetMapping
    public ResponseEntity<PageEnvelope<CoverImageResponse>> getAll(
            @PageableDefault(size = 20) Pageable pageable,
            @RequestParam(name = "status", required = false) CoverImageStatus status,
            @RequestParam(name = "storageType", required = false) CoverImageStorageType storageType,
            @RequestParam(name = "url", required = false) String url,
            @RequestParam(name = "sourceDomain", required = false) String sourceDomain,
            @RequestParam(name = "minFileSize", required = false) Long minFileSize,
            @RequestParam(name = "maxFileSize", required = false) Long maxFileSize) {
        return ResponseEntity.ok(PageEnvelope.from(service.findAll(
                pageable,
                status,
                storageType,
                url,
                sourceDomain,
                minFileSize,
                maxFileSize)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CoverImageResponse> getOne(@PathVariable(name = "id") UUID id) {
        return service.findOne(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable(name = "id") UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CoverImageResponse> uploadFile(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(service.manualUpload(file));
    }

    @PostMapping("/fetch")
    public ResponseEntity<CoverImageResponse> fetchFromUrl(@Valid @RequestBody UrlRequest request) {
        return ResponseEntity.ok(service.createFromUrl(request.url()));
    }

    @PostMapping("/{id}/sync-r2")
    public ResponseEntity<Void> syncR2(@PathVariable(name = "id") UUID id) {
        service.triggerR2Sync(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retryDownload(@PathVariable(name = "id") UUID id) {
        service.retryDownload(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/download-backfill")
    public ResponseEntity<Integer> enqueueDownloadBackfill(
            @RequestParam(name = "limit", defaultValue = "25") int limit) {
        return ResponseEntity.accepted().body(service.enqueueDownloadBackfill(limit));
    }
}
