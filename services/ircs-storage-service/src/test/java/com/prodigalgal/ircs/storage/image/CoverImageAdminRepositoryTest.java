package com.prodigalgal.ircs.storage.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.storage.image.CoverImageDtos.CoverImageRow;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.NormalizedFile;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class CoverImageAdminRepositoryTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Test
    void markFailedRecordsRetryBackoffBeforeMaxRetries() {
        UUID imageId = UUID.randomUUID();
        givenFindRow(row(imageId, CoverImageStatus.FETCHING, 0));

        repository().markFailed(imageId, "download failed", 3);

        MapSqlParameterSource params = captureUpdateParams();
        assertEquals("FAILED", params.getValue("status"));
        assertEquals(1, params.getValue("retryCount"));
        assertNotNull(params.getValue("nextRetryTime"));
        assertTrue(params.getValue("nextRetryTime") instanceof Timestamp);
        assertEquals("download failed", params.getValue("lastError"));
    }

    @Test
    void markFailedSetsDeadWhenRetryCountReachesMaxRetries() {
        UUID imageId = UUID.randomUUID();
        givenFindRow(row(imageId, CoverImageStatus.FETCHING, 2));

        repository().markFailed(imageId, "unsafe", 3);

        MapSqlParameterSource params = captureUpdateParams();
        assertEquals("DEAD", params.getValue("status"));
        assertEquals(3, params.getValue("retryCount"));
        assertNull(params.getValue("nextRetryTime"));
        assertEquals("unsafe", params.getValue("lastError"));
    }

    @Test
    void markFetchingOnlyAllowsExternalUnprocessedOrFailedRows() {
        UUID imageId = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        assertTrue(repository().markFetching(imageId));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(MapSqlParameterSource.class));
        assertTrue(sql.getValue().contains("storage_type = 'EXTERNAL'"));
        assertTrue(sql.getValue().contains("status in ('UNPROCESSED', 'FAILED')"));
        assertFalse(sql.getValue().contains("'DEAD'"));
    }

    @Test
    void finalizeDownloadStoresLocalMetadataWithoutDbR2Schedule() {
        UUID imageId = UUID.randomUUID();
        NormalizedFile file = new NormalizedFile(
                new byte[] {1},
                "hash",
                "image/png",
                ".png",
                1,
                "covers/hash.png");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);

        repository().finalizeDownload(imageId, file);
        verify(jdbcTemplate).update(sql.capture(), params.capture());
        assertTrue(sql.getValue().contains("storage_type = 'LOCAL'"));
        assertTrue(sql.getValue().contains("status = 'LOCAL_STORED'"));
        assertTrue(sql.getValue().contains("next_retry_time = null"));
        assertEquals(imageId, params.getValue().getValue("id"));
        assertEquals("covers/hash.png", params.getValue().getValue("storagePath"));
    }

    @Test
    void findDownloadCandidatesOnlySelectsEligibleExternalRows() {
        repository().findDownloadCandidates(25);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).queryForList(sql.capture(), params.capture(), org.mockito.Mockito.eq(UUID.class));
        assertTrue(sql.getValue().contains("storage_type = 'EXTERNAL'"));
        assertTrue(sql.getValue().contains("status in ('UNPROCESSED', 'FAILED')"));
        assertTrue(sql.getValue().contains("next_retry_time is null or next_retry_time <= now()"));
        assertEquals(25, params.getValue().getValue("limit"));
    }

    private CoverImageAdminRepository repository() {
        SystemConfigRepository configRepository = org.mockito.Mockito.mock(SystemConfigRepository.class);
        return new CoverImageAdminRepository(
                jdbcTemplate,
                new CoverImageUrlResolver(new StorageConfigValues(
                        new org.springframework.mock.env.MockEnvironment(),
                        configRepository)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void givenFindRow(CoverImageRow row) {
        doReturn(row).when(jdbcTemplate).queryForObject(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class));
    }

    private MapSqlParameterSource captureUpdateParams() {
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(anyString(), params.capture());
        return params.getValue();
    }

    private CoverImageRow row(UUID id, CoverImageStatus status, int retryCount) {
        Instant now = Instant.now();
        return new CoverImageRow(
                id,
                CoverImageStorageType.EXTERNAL,
                status,
                "/cover.png",
                null,
                null,
                null,
                null,
                UUID.randomUUID(),
                "http://images.example.test",
                retryCount,
                null,
                now,
                now,
                now);
    }
}
