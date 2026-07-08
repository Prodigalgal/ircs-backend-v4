package com.prodigalgal.ircs.notification.mail;

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
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("app.mail.host")))
                .thenReturn("smtp.first.example.invalid")
                .thenReturn("smtp.second.example.invalid");

        assertEquals("smtp.first.example.invalid", repository.findValue("app.mail.host").orElseThrow());
        assertEquals("smtp.first.example.invalid", repository.findValue("app.mail.host").orElseThrow());

        repository.evict("app.mail.host");

        assertEquals("smtp.second.example.invalid", repository.findValue("app.mail.host").orElseThrow());
        verify(jdbcTemplate, times(2))
                .queryForObject(anyString(), eq(String.class), eq("app.mail.host"));
    }
}
