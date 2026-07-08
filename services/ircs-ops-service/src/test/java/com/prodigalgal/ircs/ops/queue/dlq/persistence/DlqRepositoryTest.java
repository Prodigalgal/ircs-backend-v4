package com.prodigalgal.ircs.ops.queue.dlq.persistence;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class DlqRepositoryTest {

    private final NamedParameterJdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(NamedParameterJdbcTemplate.class);
    private final DlqRepository repository = new DlqRepository(jdbcTemplate);

    @Test
    void rejectsUnsupportedStatusBeforeSqlExecution() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.findAll(PageRequest.of(0, 10), "BROKEN", null, null));

        verifyNoInteractions(jdbcTemplate);
    }
}
