package com.prodigalgal.ircs.interaction;

import java.util.List;

public record PageResponse<T>(
        List<T> content,
        int totalPages,
        long totalElements,
        int size,
        int number,
        boolean last,
        boolean first,
        boolean empty) {

    public static <T> PageResponse<T> of(List<T> content, long totalElements, PageBounds bounds) {
        int totalPages = bounds.size() == 0 ? 0 : (int) Math.ceil((double) totalElements / bounds.size());
        boolean first = bounds.page() == 0;
        boolean last = totalPages == 0 || bounds.page() >= totalPages - 1;
        return new PageResponse<>(
                content,
                totalPages,
                totalElements,
                bounds.size(),
                bounds.page(),
                last,
                first,
                content.isEmpty());
    }
}
