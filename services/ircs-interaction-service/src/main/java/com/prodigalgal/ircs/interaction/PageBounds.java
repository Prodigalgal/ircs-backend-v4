package com.prodigalgal.ircs.interaction;

public record PageBounds(int page, int size) {

    public static PageBounds of(int page, int size, int defaultSize, int maxSize) {
        int normalizedPage = Math.max(page, 0);
        int requestedSize = size <= 0 ? defaultSize : size;
        int normalizedSize = Math.min(Math.max(requestedSize, 1), maxSize);
        return new PageBounds(normalizedPage, normalizedSize);
    }

    public long offset() {
        return (long) page * size;
    }
}
