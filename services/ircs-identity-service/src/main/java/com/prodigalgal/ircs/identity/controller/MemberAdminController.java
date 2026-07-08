package com.prodigalgal.ircs.identity.controller;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import com.prodigalgal.ircs.identity.dto.HistoryRecordResponse;
import com.prodigalgal.ircs.identity.application.MemberAdminService;
import com.prodigalgal.ircs.identity.domain.MemberStatus;
import com.prodigalgal.ircs.identity.dto.PageResponse;

import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberAdminResponse;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberAdminUpdateRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberStatusUpdateRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class MemberAdminController {

    private final MemberAdminService memberAdminService;

    @GetMapping
    public ResponseEntity<PageEnvelope<MemberAdminResponse>> getAll(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sort", required = false, defaultValue = "createdAt,desc") String sort,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) MemberStatus status,
            @RequestParam(name = "adultContentAllowed", required = false) Boolean adultContentAllowed,
            @RequestParam(name = "minPoints", required = false) Integer minPoints,
            @RequestParam(name = "maxPoints", required = false) Integer maxPoints) {
        return ResponseEntity.ok(pageEnvelope(memberAdminService.findAll(
                keyword,
                status,
                adultContentAllowed,
                minPoints,
                maxPoints,
                page,
                size,
                sort)));
    }

    @GetMapping("/{id}/favorites")
    public ResponseEntity<PageEnvelope<HistoryRecordResponse>> getFavorites(
            @PathVariable(name = "id") UUID id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(pageEnvelope(memberAdminService.favorites(id, page, size)));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<PageEnvelope<HistoryRecordResponse>> getHistory(
            @PathVariable(name = "id") UUID id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(pageEnvelope(memberAdminService.history(id, page, size)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<MemberAdminResponse> updateMember(
            @PathVariable(name = "id") UUID id,
            @Valid @RequestBody MemberAdminUpdateRequest request) {
        return ResponseEntity.ok(memberAdminService.updateMember(id, request));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<MemberAdminResponse> updateStatus(
            @PathVariable(name = "id") UUID id,
            @Valid @RequestBody MemberStatusUpdateRequest request) {
        return ResponseEntity.ok(memberAdminService.updateStatus(id, request.status()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable(name = "id") UUID id) {
        memberAdminService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private <T> PageEnvelope<T> pageEnvelope(PageResponse<T> page) {
        return PageEnvelope.of(page.content(), page.number(), page.size(), page.totalElements());
    }
}
