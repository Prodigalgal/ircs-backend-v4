package com.prodigalgal.ircs.common.storage;

import java.util.UUID;

public record StorageWorkPayload(
        UUID entityId,
        String sourceService,
        String reason) {
}
