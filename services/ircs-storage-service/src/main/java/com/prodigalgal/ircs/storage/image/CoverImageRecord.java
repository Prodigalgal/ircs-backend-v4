package com.prodigalgal.ircs.storage.image;

import java.util.UUID;

public record CoverImageRecord(UUID id, CoverImageStorageType storageType, String storagePath) {
}
