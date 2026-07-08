package com.prodigalgal.ircs.catalog;

import java.util.List;

public record CatalogPage<T>(
        List<T> content,
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        int numberOfElements,
        boolean empty) {

    public static <T> CatalogPage<T> of(List<T> content, CatalogPageRequest pageRequest, long totalElements) {
        int totalPages = pageRequest.size() == 0
                ? 0
                : (int) Math.ceil((double) totalElements / (double) pageRequest.size());
        boolean first = pageRequest.page() == 0;
        boolean last = totalPages == 0 || pageRequest.page() >= totalPages - 1;
        return new CatalogPage<>(
                List.copyOf(content),
                pageRequest.page(),
                pageRequest.size(),
                totalElements,
                totalPages,
                first,
                last,
                content.size(),
                content.isEmpty());
    }
}
