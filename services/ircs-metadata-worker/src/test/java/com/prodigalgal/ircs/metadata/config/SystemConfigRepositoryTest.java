package com.prodigalgal.ircs.metadata.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SystemConfigRepositoryTest {

    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final SystemConfigRepository repository = SystemConfigRepository.forTest(jdbcTemplate);

    @Test
    void cachesValuesUntilEvicted() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("app.metadata.tmdb.enabled")))
                .thenReturn("false")
                .thenReturn("true");

        assertEquals("false", repository.findValue("app.metadata.tmdb.enabled").orElseThrow());
        assertEquals("false", repository.findValue("app.metadata.tmdb.enabled").orElseThrow());

        repository.evict("app.metadata.tmdb.enabled");

        assertEquals("true", repository.findValue("app.metadata.tmdb.enabled").orElseThrow());
        verify(jdbcTemplate, times(2))
                .queryForObject(anyString(), eq(String.class), eq("app.metadata.tmdb.enabled"));
    }
}
