package com.prodigalgal.ircs.interaction;

public record MediaRequestSubmitRequest(
        String title,
        Integer releaseYear,
        String extraInfo) {
}
