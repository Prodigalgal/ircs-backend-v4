package com.prodigalgal.ircs.content.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CoverImageReferenceRepositoryTest {

    @Test
    void findsRawVideoIdsByCoverImageId() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        UUID id = UUID.randomUUID();
        UUID rawId = UUID.randomUUID();
        when(jdbcTemplate.query(
                org.mockito.Mockito.eq("select id from raw_videos where cover_image_id = ?"),
                org.mockito.Mockito.<RowMapper<UUID>>any(),
                org.mockito.Mockito.eq(id)))
                .thenReturn(List.of(rawId));

        List<UUID> ids = new CoverImageReferenceRepository(jdbcTemplate).findRawVideoIds(id);

        assertEquals(List.of(rawId), ids);
        verify(jdbcTemplate).query(
                org.mockito.Mockito.eq("select id from raw_videos where cover_image_id = ?"),
                org.mockito.Mockito.<RowMapper<UUID>>any(),
                org.mockito.Mockito.eq(id));
    }

    @Test
    void findsUnifiedVideoIdsByCoverImageId() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        UUID id = UUID.randomUUID();
        UUID unifiedId = UUID.randomUUID();
        when(jdbcTemplate.query(
                org.mockito.Mockito.eq("select id from unified_videos where cover_image_id = ?"),
                org.mockito.Mockito.<RowMapper<UUID>>any(),
                org.mockito.Mockito.eq(id)))
                .thenReturn(List.of(unifiedId));

        List<UUID> ids = new CoverImageReferenceRepository(jdbcTemplate).findUnifiedVideoIds(id);

        assertEquals(List.of(unifiedId), ids);
        verify(jdbcTemplate).query(
                org.mockito.Mockito.eq("select id from unified_videos where cover_image_id = ?"),
                org.mockito.Mockito.<RowMapper<UUID>>any(),
                org.mockito.Mockito.eq(id));
    }

    @Test
    void unlinksRawVideosByCoverImageId() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.update("update raw_videos set cover_image_id = null where cover_image_id = ?", id))
                .thenReturn(3);

        int count = new CoverImageReferenceRepository(jdbcTemplate).unlinkRawVideos(id);

        assertEquals(3, count);
        verify(jdbcTemplate).update("update raw_videos set cover_image_id = null where cover_image_id = ?", id);
    }

    @Test
    void unlinksUnifiedVideosByCoverImageId() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.update("update unified_videos set cover_image_id = null where cover_image_id = ?", id))
                .thenReturn(1);

        int count = new CoverImageReferenceRepository(jdbcTemplate).unlinkUnifiedVideos(id);

        assertEquals(1, count);
        verify(jdbcTemplate).update("update unified_videos set cover_image_id = null where cover_image_id = ?", id);
    }
}
