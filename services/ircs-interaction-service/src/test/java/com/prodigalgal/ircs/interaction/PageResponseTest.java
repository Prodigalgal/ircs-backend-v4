package com.prodigalgal.ircs.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PageResponseTest {

    @Test
    void clampsPageSizeAndComputesFlags() {
        PageBounds bounds = PageBounds.of(-1, 1000, 20, 70);
        PageResponse<String> response = PageResponse.of(List.of("a"), 141, bounds);

        assertEquals(0, response.number());
        assertEquals(70, response.size());
        assertEquals(3, response.totalPages());
        assertTrue(response.first());
        assertFalse(response.last());
        assertFalse(response.empty());
    }
}
