package com.prodigalgal.ircs.common.storage;

import java.util.Locale;
import java.util.UUID;

public final class StorageWorkTypes {

    public static final String AVATAR_SYNC = "storage.avatar-sync";
    public static final String COVER_R2_SYNC = "storage.cover-r2-sync";

    private StorageWorkTypes() {
    }

    public static String avatarTaskId(UUID memberId) {
        return taskId("avatar", memberId);
    }

    public static String coverR2TaskId(UUID coverImageId) {
        return taskId("cover-r2", coverImageId);
    }

    private static String taskId(String prefix, UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        return prefix.toLowerCase(Locale.ROOT) + ":" + id;
    }
}
