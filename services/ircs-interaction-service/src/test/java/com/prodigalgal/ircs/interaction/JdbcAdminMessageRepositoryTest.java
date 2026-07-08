package com.prodigalgal.ircs.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcAdminMessageRepositoryTest {

    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final JdbcAdminMessageRepository repository = new JdbcAdminMessageRepository(jdbcTemplate);

    @Test
    void keepsWhitespaceBetweenDynamicWhereClauseAndOrderBy() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        repository.findMessages(PageBounds.of(0, 20, 20, 100), Optional.of("codex"), Optional.of("PENDING"), Optional.of(true));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));
        String sql = sqlCaptor.getValue();

        assertTrue(sql.contains("um.status = ?"));
        assertTrue(sql.contains("um.is_public = ?"));
        assertTrue(sql.contains("order by um.created_at desc"));
        assertFalse(sql.contains("?order"));
    }
}
