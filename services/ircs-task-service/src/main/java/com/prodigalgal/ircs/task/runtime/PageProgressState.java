package com.prodigalgal.ircs.task.runtime;

public record PageProgressState(
        int pageNumber,
        Integer totalPages,
        String status
) {
}
