package com.prodigalgal.ircs.common.magnet;

import java.util.UUID;

public final class MagnetWorkTypes {

    public static final String SEARCH = "magnet.search";

    private MagnetWorkTypes() {
    }

    public static String searchTaskId(UUID jobId) {
        if (jobId == null) {
            throw new IllegalArgumentException("jobId is required");
        }
        return SEARCH + ":" + jobId;
    }
}
