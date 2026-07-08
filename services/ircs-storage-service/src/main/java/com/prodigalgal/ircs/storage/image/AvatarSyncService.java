package com.prodigalgal.ircs.storage.image;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
@RequiredArgsConstructor
public class AvatarSyncService {

    private final AvatarSyncMemberRepository memberRepository;
    private final LocalObjectStorage localObjectStorage;
    private final R2ObjectStorage r2ObjectStorage;
    private final StorageConfigValues configValues;

    @Transactional
    public AvatarSyncResult sync(UUID memberId) {
        if (memberId == null) {
            return AvatarSyncResult.skipped("member id is null");
        }
        if (!r2ObjectStorage.isActive()) {
            log.debug("R2 disabled or inactive. Skipping avatar sync for member {}", memberId);
            return AvatarSyncResult.skipped("r2 inactive");
        }
        Optional<String> currentUrl = memberRepository.findAvatarUrl(memberId);
        if (currentUrl.isEmpty()) {
            log.debug("Member {} not found during avatar sync", memberId);
            return AvatarSyncResult.skipped("member missing");
        }
        String avatarUrl = currentUrl.get();
        String localPrefix = localPublicPrefix();
        if (!StringUtils.hasText(avatarUrl) || !avatarUrl.startsWith(localPrefix + "/")) {
            log.debug("Avatar URL is not local. Skip member {} url={}", memberId, avatarUrl);
            return AvatarSyncResult.skipped("avatar not local");
        }

        String relativePath = avatarUrl.substring(localPrefix.length());
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }
        if (!StringUtils.hasText(relativePath)) {
            return AvatarSyncResult.skipped("avatar path empty");
        }

        Optional<byte[]> data = localObjectStorage.retrieve(relativePath);
        if (data.isEmpty()) {
            log.warn("Local avatar file missing for member {}: {}", memberId, relativePath);
            return AvatarSyncResult.skipped("local file missing");
        }

        r2ObjectStorage.store(data.get(), relativePath, mimeType(relativePath));
        String r2Url = joinUrlParts(r2PublicBase(), relativePath);
        int updated = memberRepository.updateAvatarUrl(memberId, avatarUrl, r2Url);
        if (updated > 0) {
            localObjectStorage.deleteIfExists(relativePath);
            log.info("Avatar successfully synced to R2 for member {}", memberId);
            return AvatarSyncResult.synced(relativePath, r2Url);
        }

        log.info("Avatar sync skipped local cleanup because member {} avatar changed during sync", memberId);
        return AvatarSyncResult.skipped("avatar changed during sync");
    }

    private String localPublicPrefix() {
        String base = configValues.storagePublicPath().trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private String r2PublicBase() {
        String base = configValues.r2PublicDomain().trim();
        if (!base.startsWith("http")) {
            base = "https://" + base;
        }
        return base;
    }

    private String joinUrlParts(String base, String path) {
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        return cleanBase + "/" + cleanPath;
    }

    private String mimeType(String relativePath) {
        String path = relativePath.toLowerCase(Locale.ROOT);
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (path.endsWith(".png")) {
            return "image/png";
        }
        if (path.endsWith(".webp")) {
            return "image/webp";
        }
        if (path.endsWith(".gif")) {
            return "image/gif";
        }
        return "application/octet-stream";
    }

    public record AvatarSyncResult(boolean synced, String storagePath, String r2Url, String reason) {

        static AvatarSyncResult synced(String storagePath, String r2Url) {
            return new AvatarSyncResult(true, storagePath, r2Url, null);
        }

        static AvatarSyncResult skipped(String reason) {
            return new AvatarSyncResult(false, null, null, reason);
        }
    }
}
