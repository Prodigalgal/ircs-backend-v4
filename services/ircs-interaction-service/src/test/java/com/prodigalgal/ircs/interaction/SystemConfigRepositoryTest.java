package com.prodigalgal.ircs.interaction;

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
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("member.message.daily-limit")))
                .thenReturn("5")
                .thenReturn("1");

        assertEquals("5", repository.findValue("member.message.daily-limit").orElseThrow());
        assertEquals("5", repository.findValue("member.message.daily-limit").orElseThrow());

        repository.evict("member.message.daily-limit");

        assertEquals("1", repository.findValue("member.message.daily-limit").orElseThrow());
        verify(jdbcTemplate, times(2))
                .queryForObject(anyString(), eq(String.class), eq("member.message.daily-limit"));
    }
}
