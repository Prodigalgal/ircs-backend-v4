package com.prodigalgal.ircs.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcSystemConfigRepositorySupportTest {

    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final TestRepository repository = new TestRepository(jdbcTemplate);

    @Test
    void cachesLoadedValuesUntilEvicted() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("app.demo.enabled")))
                .thenReturn("true")
                .thenReturn("false");

        assertThat(repository.findValue("app.demo.enabled")).contains("true");
        assertThat(repository.findValue("app.demo.enabled")).contains("true");

        repository.evict("app.demo.enabled");

        assertThat(repository.findValue("app.demo.enabled")).contains("false");
        verify(jdbcTemplate, times(2)).queryForObject(anyString(), eq(String.class), eq("app.demo.enabled"));
    }

    @Test
    void cachesMissingValues() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("app.missing")))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(repository.findValue("app.missing")).isEmpty();
        assertThat(repository.findValue("app.missing")).isEmpty();

        verify(jdbcTemplate, times(1)).queryForObject(anyString(), eq(String.class), eq("app.missing"));
    }

    private static final class TestRepository extends JdbcSystemConfigRepositorySupport {

        private TestRepository(JdbcTemplate jdbcTemplate) {
            super(
                    jdbcTemplate,
                    null,
                    SystemConfigValkeyCache.DEFAULT_KEY_PREFIX,
                    SystemConfigValkeyCache.DEFAULT_TTL,
                    SystemConfigValkeyCache.DEFAULT_LOCAL_TTL);
        }
    }
}
