package com.prodigalgal.ircs.common.web;

import java.util.List;
import org.springframework.data.domain.Page;

public record PageEnvelope<T>(
        List<T> content,
        PageMetadata page) {

    public static <T> PageEnvelope<T> from(Page<T> page) {
        if (page == null) {
            return empty();
        }
        return of(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    public static <T> PageEnvelope<T> of(List<T> content, int number, int size, long totalElements) {
        List<T> safeContent = content == null ? List.of() : List.copyOf(content);
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / (double) size);
        boolean first = number == 0;
        boolean last = totalPages == 0 || number >= totalPages - 1;
        return new PageEnvelope<>(
                safeContent,
                new PageMetadata(
                        number,
                        size,
                        totalElements,
                        totalPages,
                        first,
                        last,
                        safeContent.size(),
                        safeContent.isEmpty()));
    }

    public static <T> PageEnvelope<T> empty() {
        return new PageEnvelope<>(
                List.of(),
                new PageMetadata(0, 0, 0, 0, true, true, 0, true));
    }

    public record PageMetadata(
            int number,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last,
            int numberOfElements,
            boolean empty) {
    }
}
