package com.prodigalgal.ircs.aggregation;

import java.util.UUID;

record UnifiedCoverBackfillResult(UUID unifiedVideoId, UUID coverImageId, String storageType, String status) {

    boolean shouldPromoteToR2() {
        return "LOCAL".equals(storageType) && "LOCAL_STORED".equals(status);
    }
}
