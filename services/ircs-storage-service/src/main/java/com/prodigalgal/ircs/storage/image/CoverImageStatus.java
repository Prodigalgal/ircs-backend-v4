package com.prodigalgal.ircs.storage.image;

public enum CoverImageStatus {
    UNPROCESSED,
    FETCHING,
    LOCAL_STORED,
    UPLOADING,
    REMOTE_STORED,
    FAILED,
    DEAD,
    PENDING_DELETE
}

