package com.prodigalgal.ircs.identity.infrastructure;

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
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("security.jwt.secret")))
                .thenReturn("first")
                .thenReturn("second");

        assertEquals("first", repository.findValue("security.jwt.secret").orElseThrow());
        assertEquals("first", repository.findValue("security.jwt.secret").orElseThrow());

        repository.evict("security.jwt.secret");

        assertEquals("second", repository.findValue("security.jwt.secret").orElseThrow());
        verify(jdbcTemplate, times(2))
                .queryForObject(anyString(), eq(String.class), eq("security.jwt.secret"));
    }
}
