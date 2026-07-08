package com.prodigalgal.ircs.portal;

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

    public static <T> PageResponse<T> of(List<T> content, long totalElements, int page, int size) {
        List<T> safeContent = content == null ? List.of() : content;
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(
                safeContent,
                totalPages,
                totalElements,
                size,
                page,
                page >= Math.max(totalPages - 1, 0),
                page == 0,
                safeContent.isEmpty());
    }
}

