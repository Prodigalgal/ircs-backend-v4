package com.prodigalgal.ircs.identity.infrastructure;

public interface AvatarStorageClient {

    StoredAvatar store(AvatarFile avatar);

    record AvatarFile(String filename, String contentType, byte[] data) {
    }

    record StoredAvatar(
            String url,
            String storageKey,
            String mimeType,
            long fileSize,
            String fileHash) {
    }
}
