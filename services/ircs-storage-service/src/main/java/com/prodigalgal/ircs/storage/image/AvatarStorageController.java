package com.prodigalgal.ircs.storage.image;

import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.storage.image.AvatarStorageDtos.AvatarUploadResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/internal/storage/avatars")
@RequiredArgsConstructor
public class AvatarStorageController {

    private final AvatarStorageService service;
    private final StorageInternalAccessPolicy internalAccessPolicy;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AvatarUploadResponse> upload(
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes,
            @RequestParam("file") MultipartFile file) {
        internalAccessPolicy.assertAvatarAccess(serviceId, serviceToken, serviceScopes);
        return ResponseEntity.ok(service.store(file));
    }
}
