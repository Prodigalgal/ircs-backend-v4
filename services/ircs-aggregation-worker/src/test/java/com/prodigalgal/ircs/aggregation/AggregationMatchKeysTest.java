package com.prodigalgal.ircs.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AggregationMatchKeysTest {

    @Test
    void titleVariantsShareFallbackMatchKey() {
        RawVideoAggregationRecord left = rawVideo("Codex Signal 1080p", null);
        RawVideoAggregationRecord right = rawVideo("Codex Signal", null);

        List<String> leftKeys = AggregationMatchKeys.forRawVideo(left);
        List<String> rightKeys = AggregationMatchKeys.forRawVideo(right);

        assertThat(leftKeys).containsAnyElementsOf(rightKeys);
        assertThat(leftKeys).allMatch(key -> key.startsWith("title:"));
    }

    @Test
    void externalIdsProduceProviderSpecificKeysBeforeTitleFallback() {
        RawVideoAggregationRecord rawVideo = rawVideo("Codex Signal", "1234567");

        List<String> keys = AggregationMatchKeys.forRawVideo(rawVideo);

        assertThat(keys).hasSizeGreaterThan(1);
        assertThat(keys.getFirst()).startsWith("douban:");
        assertThat(keys).anyMatch(key -> key.startsWith("title:"));
    }

    private RawVideoAggregationRecord rawVideo(String title, String doubanId) {
        return new RawVideoAggregationRecord(
                UUID.randomUUID(),
                title,
                null,
                "Aggregation description",
                "2026",
                new BigDecimal("8.9"),
                LocalDate.of(2026, 6, 4),
                "12",
                "45m",
                null,
                null,
                null,
                "电影",
                doubanId,
                null,
                null,
                null,
                "READY",
                "SUCCESS",
                "PROCESSING");
    }
}
