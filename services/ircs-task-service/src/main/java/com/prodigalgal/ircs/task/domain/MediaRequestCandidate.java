package com.prodigalgal.ircs.task.domain;

import java.util.UUID;

public record MediaRequestCandidate(
        UUID id,
        String title,
        Integer releaseYear,
        int requestCount) {

    public String keyword() {
        return releaseYear == null || releaseYear <= 0 ? title : title + " " + releaseYear;
    }
}
