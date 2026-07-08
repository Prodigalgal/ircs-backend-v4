package com.prodigalgal.ircs.portal;

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
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("app.storage.public-path")))
                .thenReturn("/media")
                .thenReturn("/assets");

        assertEquals("/media", repository.findValue("app.storage.public-path").orElseThrow());
        assertEquals("/media", repository.findValue("app.storage.public-path").orElseThrow());

        repository.evict("app.storage.public-path");

        assertEquals("/assets", repository.findValue("app.storage.public-path").orElseThrow());
        verify(jdbcTemplate, times(2))
                .queryForObject(anyString(), eq(String.class), eq("app.storage.public-path"));
    }
}
