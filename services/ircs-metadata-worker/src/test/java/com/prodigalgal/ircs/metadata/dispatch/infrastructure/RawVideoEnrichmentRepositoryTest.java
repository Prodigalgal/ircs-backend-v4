package com.prodigalgal.ircs.metadata.dispatch.infrastructure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RawVideoEnrichmentRepositoryTest {

    @Test
    void usesCurrentCategoryTables() {
        String sql = RawVideoEnrichmentRepository.SELECT_BY_ID;

        assertTrue(sql.contains("standard_category"));
        assertTrue(sql.contains("c.slug = v.category_code"));
        assertFalse(sql.contains("raw_category"));
        assertFalse(sql.contains("data_source_categories"));
        assertFalse(sql.contains("categories c"));
    }
}
