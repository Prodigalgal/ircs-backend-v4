package com.prodigalgal.ircs.identity.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PageResponseTest {

    @Test
    void clampsPageSizeAndKeepsSpringPageFlags() {
        PageBounds bounds = PageBounds.of(-1, 999, 20, 100);

        PageResponse<String> response = PageResponse.of(List.of("a"), 141, bounds);

        assertEquals(0, response.number());
        assertEquals(100, response.size());
        assertEquals(2, response.totalPages());
        assertTrue(response.first());
        assertFalse(response.last());
        assertFalse(response.empty());
    }
}
