package com.prodigalgal.ircs.storage.image;

public final class AvatarStorageDtos {

    private AvatarStorageDtos() {
    }

    public record AvatarUploadResponse(
            String url,
            String storageKey,
            String mimeType,
            long fileSize,
            String fileHash) {
    }
}
