package com.prodigalgal.ircs.content.auxiliary.controller;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import com.prodigalgal.ircs.content.auxiliary.application.AuxiliaryAdminService;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.PlaylistCardResponse;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.PlaylistDetailResponse;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.PlaylistRequest;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.PlaylistUpdateRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
@RequiredArgsConstructor
@RequestMapping("/api/v1/playlists")
public class PlaylistAdminController {

    private final AuxiliaryAdminService service;

    @PostMapping
    public ResponseEntity<PlaylistDetailResponse> create(@Valid @RequestBody PlaylistRequest request) {
        PlaylistDetailResponse result = service.createPlaylist(request);
        return ResponseEntity.created(URI.create("/api/v1/playlists/" + result.id())).body(result);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PlaylistDetailResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody PlaylistUpdateRequest request) {
        return ResponseEntity.ok(service.updatePlaylist(id, request));
    }

    @GetMapping
    public ResponseEntity<PageEnvelope<PlaylistCardResponse>> getAll(
            @PageableDefault(size = 20) Pageable pageable,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String videoTitle) {
        return ResponseEntity.ok(PageEnvelope.from(service.findPlaylists(pageable, name, videoTitle)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlaylistDetailResponse> getOne(@PathVariable UUID id) {
        return service.findPlaylist(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.deletePlaylist(id);
        return ResponseEntity.noContent().build();
    }
}
