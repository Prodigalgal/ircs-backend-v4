package com.prodigalgal.ircs.identity.controller;

import com.prodigalgal.ircs.identity.application.MemberProfileService;

import com.prodigalgal.ircs.identity.dto.IdentityDtos.CheckInResult;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.AvatarUploadResponse;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberProfileResponse;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.PasswordChangeRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.ProfileUpdateRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/portal/profile")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class MemberProfileController {

    private final MemberProfileService profileService;

    @GetMapping
    public ResponseEntity<MemberProfileResponse> getMyProfile(@AuthenticationPrincipal String id) {
        return ResponseEntity.ok(profileService.getProfile(UUID.fromString(id)));
    }

    @PutMapping
    public ResponseEntity<MemberProfileResponse> updateProfile(
            @AuthenticationPrincipal String id,
            @Valid @RequestBody ProfileUpdateRequest request) {
        return ResponseEntity.ok(profileService.updateProfile(UUID.fromString(id), request));
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal String id,
            @Valid @RequestBody PasswordChangeRequest request) {
        profileService.changePassword(UUID.fromString(id), request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AvatarUploadResponse> uploadAvatar(
            @AuthenticationPrincipal String id,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(profileService.uploadAvatar(UUID.fromString(id), file));
    }

    @PostMapping("/check-in")
    public ResponseEntity<CheckInResult> checkIn(@AuthenticationPrincipal String id) {
        return ResponseEntity.ok(profileService.checkIn(UUID.fromString(id)));
    }
}
