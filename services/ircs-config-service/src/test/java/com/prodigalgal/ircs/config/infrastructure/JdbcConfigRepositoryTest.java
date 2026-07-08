package com.prodigalgal.ircs.config.infrastructure;


import com.prodigalgal.ircs.config.application.SystemConfigDefaults;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcConfigRepositoryTest {

    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final JdbcConfigRepository repository = JdbcConfigRepository.forTest(jdbcTemplate);

    @Test
    void upsertDefaultOnlyRepairsBlankOrLegacyDefaultConfigValueOnConflict() {
        when(jdbcTemplate.query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(List.of());

        repository.upsertDefault(new SystemConfigDefaults.ResolvedDefault(
                "app.mail.enabled",
                "false",
                "Mail enabled default seed"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any());

        String sql = sqlCaptor.getValue().toLowerCase();
        String conflictClause = sql.substring(sql.indexOf("on conflict"));
        assertTrue(conflictClause.contains("do update"));
        assertTrue(conflictClause.contains("config_value = case"));
        assertTrue(conflictClause.contains("btrim(system_configs.config_value)"));
        assertTrue(conflictClause.contains("lower(coalesce(btrim(system_configs.config_value), '')) = 'false'"));
        assertTrue(conflictClause.contains("btrim(system_configs.config_value) = ?"));
        assertFalse(conflictClause.contains("config_value = excluded.config_value"));
    }
}
