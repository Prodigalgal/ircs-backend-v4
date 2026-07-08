package com.prodigalgal.ircs.search.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchTextHelperTest {

    @Test
    void normalizesTitleLikeV1SearchHelper() {
        assertEquals("三体 season 1", SearchTextHelper.normalizeTitleForSearch("【三体】Season-1"));
    }

    @Test
    void buildsSeasonAndSubtitleVariants() {
        List<String> variants = SearchTextHelper.buildTitleVariants("三体", "Three Body", "周年版", "2024", 1);

        assertTrue(variants.contains("三体 周年版"));
        assertTrue(variants.contains("三体: 周年版"));
        assertTrue(variants.contains("三体 第1季"));
        assertTrue(variants.contains("三体第1季周年版"));
        assertTrue(variants.contains("three body 2024"));
    }
}
