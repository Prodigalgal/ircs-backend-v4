package com.prodigalgal.ircs.task.domain;

import java.util.UUID;

public record TaskRuntimeState(
        UUID id,
        UUID dataSourceId,
        String status,
        Boolean enabled,
        Integer startPage,
        Integer currentPage
) {
}
