package com.prodigalgal.ircs.content.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SystemConfigRepositoryTest {

    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final SystemConfigRepository repository = SystemConfigRepository.forTest(jdbcTemplate);

    @Test
    void cachesValuesUntilEvicted() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq(ContentConfigValues.SCRAPER_BASE_URL_KEY)))
                .thenReturn("http://scraper-one:8080")
                .thenReturn("http://scraper-two:8080");

        assertEquals("http://scraper-one:8080", repository.findValue(ContentConfigValues.SCRAPER_BASE_URL_KEY).orElseThrow());
        assertEquals("http://scraper-one:8080", repository.findValue(ContentConfigValues.SCRAPER_BASE_URL_KEY).orElseThrow());

        repository.evict(ContentConfigValues.SCRAPER_BASE_URL_KEY);

        assertEquals("http://scraper-two:8080", repository.findValue(ContentConfigValues.SCRAPER_BASE_URL_KEY).orElseThrow());
        verify(jdbcTemplate, times(2))
                .queryForObject(anyString(), eq(String.class), eq(ContentConfigValues.SCRAPER_BASE_URL_KEY));
    }

    @Test
    void changedEventEvictsCachedValueSoNextReadUsesFreshDbValue() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq(ContentConfigValues.SCRAPER_BASE_URL_KEY)))
                .thenReturn("http://scraper-before-event:8080")
                .thenReturn("http://scraper-after-event:8080");
        SystemConfigChangedListener listener = new SystemConfigChangedListener(repository, objectMapper);

        assertEquals(
                "http://scraper-before-event:8080",
                repository.findValue(ContentConfigValues.SCRAPER_BASE_URL_KEY).orElseThrow());
        assertEquals(
                "http://scraper-before-event:8080",
                repository.findValue(ContentConfigValues.SCRAPER_BASE_URL_KEY).orElseThrow());

        String payload = objectMapper.writeValueAsString(new SystemConfigChangedEvent(
                UUID.randomUUID(),
                ContentConfigValues.SCRAPER_BASE_URL_KEY,
                SystemConfigChangedEvent.Action.UPDATED,
                "DB",
                false,
                1L,
                0L,
                Instant.now()));
        listener.handle(payload);

        assertEquals(
                "http://scraper-after-event:8080",
                repository.findValue(ContentConfigValues.SCRAPER_BASE_URL_KEY).orElseThrow());
        verify(jdbcTemplate, times(2))
                .queryForObject(anyString(), eq(String.class), eq(ContentConfigValues.SCRAPER_BASE_URL_KEY));
    }
}
