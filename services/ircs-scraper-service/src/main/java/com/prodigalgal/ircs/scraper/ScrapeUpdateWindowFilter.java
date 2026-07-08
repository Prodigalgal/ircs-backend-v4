package com.prodigalgal.ircs.scraper;

import com.prodigalgal.ircs.scraper.ScraperDtos.ListItem;
import com.prodigalgal.ircs.scraper.ScraperDtos.ListPage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class ScrapeUpdateWindowFilter {

    private static final ZoneId SOURCE_TIME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.BASIC_ISO_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd"));

    private final Clock clock;

    ScrapeUpdateWindowFilter(ObjectProvider<Clock> clockProvider) {
        Clock provided = clockProvider == null ? null : clockProvider.getIfAvailable();
        this.clock = provided == null ? Clock.systemUTC() : provided;
    }

    FilteredListPage filter(ListPage page, int pageNumber, Integer filterHours) {
        if (page == null || filterHours == null || filterHours <= 0 || page.items().isEmpty()) {
            return new FilteredListPage(page, false);
        }
        Instant cutoff = Instant.now(clock).minus(Duration.ofHours(filterHours));
        List<ListItem> kept = new ArrayList<>(page.items().size());
        boolean exhausted = false;
        for (ListItem item : page.items()) {
            Optional<Instant> updatedAt = parseUpdateTime(item.updateTime());
            if (updatedAt.isEmpty() || !updatedAt.get().isBefore(cutoff)) {
                kept.add(item);
            } else {
                exhausted = true;
            }
        }
        Integer totalPages = exhausted ? pageNumber : page.totalPages();
        return new FilteredListPage(new ListPage(kept, totalPages, page.totalItems()), exhausted);
    }

    private Optional<Instant> parseUpdateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        String normalized = value.trim();
        Optional<Instant> epoch = parseEpoch(normalized);
        if (epoch.isPresent()) {
            return epoch;
        }
        try {
            return Optional.of(Instant.parse(normalized));
        } catch (Exception ignored) {
        }
        try {
            return Optional.of(OffsetDateTime.parse(normalized).toInstant());
        } catch (Exception ignored) {
        }
        for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
            try {
                return Optional.of(LocalDateTime.parse(normalized, formatter).atZone(SOURCE_TIME_ZONE).toInstant());
            } catch (Exception ignored) {
            }
        }
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                LocalDate date = LocalDate.parse(normalized, formatter);
                return Optional.of(date.atTime(LocalTime.MAX).atZone(SOURCE_TIME_ZONE).toInstant());
            } catch (Exception ignored) {
            }
        }
        return Optional.empty();
    }

    private Optional<Instant> parseEpoch(String value) {
        if (!value.matches("\\d{10,13}")) {
            return Optional.empty();
        }
        try {
            long numeric = Long.parseLong(value);
            if (value.length() == 13) {
                return Optional.of(Instant.ofEpochMilli(numeric));
            }
            if (value.length() == 10) {
                return Optional.of(Instant.ofEpochSecond(numeric));
            }
        } catch (NumberFormatException ignored) {
        }
        return Optional.empty();
    }

    record FilteredListPage(ListPage page, boolean exhausted) {
    }
}
