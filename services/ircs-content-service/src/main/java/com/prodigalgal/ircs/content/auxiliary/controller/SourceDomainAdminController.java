package com.prodigalgal.ircs.content.auxiliary.controller;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import com.prodigalgal.ircs.content.auxiliary.application.AuxiliaryAdminService;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.SourceDomainRequest;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.SourceDomainResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/source-domains")
public class SourceDomainAdminController {

    private final AuxiliaryAdminService service;

    @GetMapping
    public ResponseEntity<PageEnvelope<SourceDomainResponse>> getAll(
            @PageableDefault(size = 20) Pageable pageable,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "dataSourceId", required = false) UUID dataSourceId) {
        return ResponseEntity.ok(PageEnvelope.from(service.findSourceDomains(pageable, keyword, dataSourceId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SourceDomainResponse> getOne(@PathVariable("id") UUID id) {
        return service.findSourceDomain(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<SourceDomainResponse> update(
            @PathVariable("id") UUID id,
            @Valid @RequestBody SourceDomainRequest request) {
        return ResponseEntity.ok(service.updateSourceDomain(id, request));
    }
}
