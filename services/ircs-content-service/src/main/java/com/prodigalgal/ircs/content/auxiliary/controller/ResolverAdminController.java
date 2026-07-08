package com.prodigalgal.ircs.content.auxiliary.controller;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import com.prodigalgal.ircs.content.auxiliary.application.AuxiliaryAdminService;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.ResolverRequest;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.ResolverResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/resolvers")
public class ResolverAdminController {

    private final AuxiliaryAdminService service;

    @PostMapping
    public ResponseEntity<ResolverResponse> create(@Valid @RequestBody ResolverRequest request) {
        ResolverResponse result = service.createResolver(request);
        return ResponseEntity.created(URI.create("/api/v1/resolvers/" + result.id())).body(result);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ResolverResponse> update(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ResolverRequest request) {
        return ResponseEntity.ok(service.updateResolver(id, request));
    }

    @GetMapping
    public ResponseEntity<PageEnvelope<ResolverResponse>> getAll(@PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(PageEnvelope.from(service.findResolvers(pageable)));
    }

    @GetMapping("/active")
    public ResponseEntity<List<ResolverResponse>> getAllActive() {
        return ResponseEntity.ok(service.findActiveResolvers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResolverResponse> getOne(@PathVariable("id") UUID id) {
        return service.findResolver(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        service.deleteResolver(id);
        return ResponseEntity.noContent().build();
    }
}
