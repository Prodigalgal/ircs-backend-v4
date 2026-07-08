package com.prodigalgal.ircs.catalog;

import java.util.List;

public record CatalogPageRequest(int page, int size, List<String> sort) {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    public CatalogPageRequest {
        page = Math.max(0, page);
        size = normalizeSize(size);
        sort = sort == null ? List.of() : List.copyOf(sort);
    }

    public static CatalogPageRequest of(Integer page, Integer size, List<String> sort) {
        return new CatalogPageRequest(
                page == null ? 0 : page,
                size == null ? DEFAULT_SIZE : size,
                sort);
    }

    public int offset() {
        return page * size;
    }

    private static int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
