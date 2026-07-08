package com.prodigalgal.ircs.metadata.result.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.aggregation.AggregationWorkPublisher;
import com.prodigalgal.ircs.contracts.metadata.EnrichedMetadataDTO;
import com.prodigalgal.ircs.contracts.metadata.EnrichmentResultMsg;
import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import com.prodigalgal.ircs.metadata.config.MetadataConfigValues;
import com.prodigalgal.ircs.metadata.dispatch.messaging.MetadataEnrichPublisher;
import com.prodigalgal.ircs.metadata.pipeline.MetadataPipelineRunRepository;
import com.prodigalgal.ircs.metadata.pipeline.MetadataPipelineRunRepository.ProviderCompletion;
import com.prodigalgal.ircs.metadata.result.domain.RawVideoMetadataRecord;
import com.prodigalgal.ircs.metadata.result.dto.MetadataResultProcessingResult;
import com.prodigalgal.ircs.metadata.result.infrastructure.RawVideoMetadataRepository;
import com.prodigalgal.ircs.metadata.result.messaging.RawSearchSyncPublisher;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MetadataResultCollectorServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private RawVideoMetadataRepository repository;

    @Mock
    private RawSearchSyncPublisher searchSyncPublisher;

    @Mock
    private MetadataPipelineRunRepository pipelineRunRepository;

    @Mock
    private MetadataEnrichPublisher metadataEnrichPublisher;

    @Mock
    private AggregationWorkPublisher aggregationWorkPublisher;

    @Mock
    private MetadataConfigValues configValues;

    @Test
    void appliesSuccessfulMetadataAndPublishesSearchSync() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(record(id)));
        EnrichedMetadataDTO metadata = new EnrichedMetadataDTO();
        metadata.setTmdbId("tmdb-1");
        metadata.setImdbId("tt001");
        metadata.setDescription("description");
        metadata.setYear("2026");
        metadata.setScore(new BigDecimal("8.5"));
        metadata.setOriginalTitle("Original Title");

        MetadataResultProcessingResult result = newService(completedSuccess()).process(EnrichmentResultMsg.builder()
                .videoId(id)
                .pipelineVersion("hash-v1")
                .providerType(ProviderType.TMDB)
                .success(true)
                .metadata(metadata)
                .build());

        ArgumentCaptor<RawVideoMetadataRecord> record = ArgumentCaptor.forClass(RawVideoMetadataRecord.class);
        verify(repository).save(record.capture());
        assertEquals("SUCCESS", result.enrichmentStatus());
        assertEquals("tmdb-1", record.getValue().getTmdbId());
        assertEquals("tt001", record.getValue().getImdbId());
        assertEquals("description", record.getValue().getDescription());
        assertEquals("2026", record.getValue().getYear());
        assertEquals(new BigDecimal("8.5"), record.getValue().getScore());
        assertEquals("Original Title", record.getValue().getAliasTitle());
        assertEquals("PENDING", record.getValue().getAggregationStatus());
        verify(searchSyncPublisher).publishIndex(id);
        verify(aggregationWorkPublisher).enqueue(id, "hash-v1", "metadata-finalized");
    }

    @Test
    void treatsRottenTomatoesIdAsAuthoritativeSuccess() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(record(id)));
        EnrichedMetadataDTO metadata = new EnrichedMetadataDTO();
        metadata.setRottenTomatoesId("matrix");

        MetadataResultProcessingResult result = newService(completedSuccess()).process(EnrichmentResultMsg.builder()
                .videoId(id)
                .pipelineVersion("hash-v1")
                .providerType(ProviderType.ROTTEN_TOMATOES)
                .success(true)
                .metadata(metadata)
                .build());

        ArgumentCaptor<RawVideoMetadataRecord> record = ArgumentCaptor.forClass(RawVideoMetadataRecord.class);
        verify(repository).save(record.capture());
        assertEquals("SUCCESS", result.enrichmentStatus());
        assertEquals("matrix", record.getValue().getRottenTomatoesId());
        assertEquals("PENDING", record.getValue().getAggregationStatus());
        verify(searchSyncPublisher).publishIndex(id);
    }

    @Test
    void mergesEnrichmentRichFieldsIntoRawMetadataForNormalization() throws Exception {
        UUID id = UUID.randomUUID();
        RawVideoMetadataRecord existing = record(id);
        existing.setRawMetadata("""
                {
                  "genreNames": ["动作"],
                  "language": "国语",
                  "area": "中国大陆",
                  "actorNames": ["演员甲"],
                  "posterUrl": "https://img.example.invalid/existing.jpg",
                  "sourceNote": "keep"
                }
                """);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        EnrichedMetadataDTO metadata = new EnrichedMetadataDTO();
        metadata.setTmdbId("tmdb-rich");
        metadata.setArea("美国");
        metadata.setLanguage("英语");
        metadata.addGenre("科幻");
        metadata.addActor("演员乙");
        metadata.addDirector("导演甲");
        metadata.setPosterUrl("https://img.example.invalid/provider.jpg");
        metadata.setBackdropUrl("https://img.example.invalid/backdrop.jpg");
        metadata.setImageSource(ProviderType.TMDB);

        MetadataResultProcessingResult result = newService(completedSuccess()).process(EnrichmentResultMsg.builder()
                .videoId(id)
                .pipelineVersion("hash-v1")
                .providerType(ProviderType.TMDB)
                .success(true)
                .metadata(metadata)
                .build());

        ArgumentCaptor<RawVideoMetadataRecord> record = ArgumentCaptor.forClass(RawVideoMetadataRecord.class);
        verify(repository).save(record.capture());
        JsonNode rawMetadata = objectMapper.readTree(record.getValue().getRawMetadata());
        assertEquals("SUCCESS", result.enrichmentStatus());
        assertEquals("中国大陆, 美国", rawMetadata.path("area").asText());
        assertEquals("国语/英语", rawMetadata.path("language").asText());
        assertArrayContains(rawMetadata.path("genreNames"), "动作", "科幻");
        assertArrayContains(rawMetadata.path("actorNames"), "演员甲", "演员乙");
        assertArrayContains(rawMetadata.path("directorNames"), "导演甲");
        assertEquals("https://img.example.invalid/existing.jpg", rawMetadata.path("posterUrl").asText());
        assertEquals("https://img.example.invalid/provider.jpg", rawMetadata.path("coverImageUrl").asText());
        assertEquals("https://img.example.invalid/backdrop.jpg", rawMetadata.path("backdropUrl").asText());
        assertEquals("TMDB", rawMetadata.path("imageSource").asText());
        assertEquals("keep", rawMetadata.path("sourceNote").asText());
        assertEquals("PENDING", record.getValue().getAggregationStatus());
        verify(searchSyncPublisher).publishIndex(id);
    }

    @Test
    void honorsLockedFields() {
        UUID id = UUID.randomUUID();
        RawVideoMetadataRecord existing = record(id);
        existing.setDescription("locked description");
        existing.setYear("2025");
        existing.setLockedFields(Set.of("description", "year"));
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        EnrichedMetadataDTO metadata = new EnrichedMetadataDTO();
        metadata.setTmdbId("tmdb-2");
        metadata.setDescription("new description");
        metadata.setYear("2026");

        newService(completedSuccess()).process(EnrichmentResultMsg.builder()
                .videoId(id)
                .pipelineVersion("hash-v1")
                .providerType(ProviderType.TMDB)
                .success(true)
                .metadata(metadata)
                .build());

        ArgumentCaptor<RawVideoMetadataRecord> record = ArgumentCaptor.forClass(RawVideoMetadataRecord.class);
        verify(repository).save(record.capture());
        assertEquals("locked description", record.getValue().getDescription());
        assertEquals("2025", record.getValue().getYear());
    }

    @Test
    void retryableFailureMovesToFailedAndIncrementsRetryCount() {
        UUID id = UUID.randomUUID();
        RawVideoMetadataRecord existing = record(id);
        existing.setEnrichmentRetryCount(1);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(configValues.retryDelay()).thenReturn(Duration.ofMinutes(5));

        MetadataResultProcessingResult result = newService(completedRetryableFailure()).process(EnrichmentResultMsg.builder()
                .videoId(id)
                .pipelineVersion("hash-v1")
                .providerType(ProviderType.DOUBAN)
                .success(false)
                .retryable(true)
                .build());

        ArgumentCaptor<RawVideoMetadataRecord> record = ArgumentCaptor.forClass(RawVideoMetadataRecord.class);
        verify(repository).save(record.capture());
        assertEquals("FAILED", result.enrichmentStatus());
        assertEquals(2, record.getValue().getEnrichmentRetryCount());
        verify(metadataEnrichPublisher).publish(id, "hash-v1", Duration.ofMinutes(5));
    }

    @Test
    void terminalFailureMovesToPermanentFailureAndPublishesSearchSync() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(record(id)));

        MetadataResultProcessingResult result = newService(completedPermanentFailure()).process(EnrichmentResultMsg.builder()
                .videoId(id)
                .pipelineVersion("hash-v1")
                .providerType(ProviderType.DOUBAN)
                .success(false)
                .retryable(false)
                .errorCode("PROVIDER_NOT_FOUND")
                .errorMessage("Provider implementation not found")
                .build());

        ArgumentCaptor<RawVideoMetadataRecord> record = ArgumentCaptor.forClass(RawVideoMetadataRecord.class);
        verify(repository).save(record.capture());
        assertEquals("PERMANENT_FAILURE", result.enrichmentStatus());
        assertEquals("PERMANENT_FAILURE", record.getValue().getEnrichmentStatus());
        verify(searchSyncPublisher).publishIndex(id);
    }

    @Test
    void stalePipelineVersionDoesNotSaveMetadata() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(record(id)));

        MetadataResultProcessingResult result = newService().process(EnrichmentResultMsg.builder()
                .videoId(id)
                .pipelineVersion("old-hash")
                .providerType(ProviderType.TMDB)
                .success(true)
                .metadata(new EnrichedMetadataDTO())
                .build());

        assertEquals("STALE", result.enrichmentStatus());
        verify(repository, never()).save(any());
        verify(searchSyncPublisher, never()).publishIndex(id);
    }

    @Test
    void missingVideoDoesNotPublishSearchSync() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        MetadataResultProcessingResult result = newService().process(EnrichmentResultMsg.builder()
                .videoId(id)
                .pipelineVersion("hash-v1")
                .success(true)
                .build());

        assertEquals(false, result.videoFound());
        verify(repository, never()).save(any());
        verify(searchSyncPublisher, never()).publishIndex(id);
    }

    @Test
    void firstProviderResultWithRemainingPendingOnlySavesMetadata() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(record(id)));
        EnrichedMetadataDTO metadata = new EnrichedMetadataDTO();
        metadata.setTmdbId("tmdb-early");
        metadata.addGenre("动作");

        MetadataResultProcessingResult result = newService(pendingCompletion()).process(EnrichmentResultMsg.builder()
                .videoId(id)
                .pipelineVersion("hash-v1")
                .providerType(ProviderType.TMDB)
                .success(true)
                .metadata(metadata)
                .build());

        ArgumentCaptor<RawVideoMetadataRecord> record = ArgumentCaptor.forClass(RawVideoMetadataRecord.class);
        verify(repository).save(record.capture());
        assertEquals("PENDING", result.enrichmentStatus());
        assertEquals("tmdb-early", record.getValue().getTmdbId());
        assertEquals("PENDING", record.getValue().getEnrichmentStatus());
        assertTrue(record.getValue().getRawMetadata().contains("动作"));
        verify(searchSyncPublisher, never()).publishIndex(id);
    }

    @Test
    void untrackedProviderResultDoesNotFinalizeOrSaveMetadata() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(record(id)));
        EnrichedMetadataDTO metadata = new EnrichedMetadataDTO();
        metadata.setTmdbId("tmdb-untracked");

        MetadataResultProcessingResult result = newService(ProviderCompletion.untracked()).process(EnrichmentResultMsg.builder()
                .videoId(id)
                .pipelineVersion("hash-v1")
                .providerType(ProviderType.TMDB)
                .success(true)
                .metadata(metadata)
                .build());

        assertEquals("PENDING", result.enrichmentStatus());
        verify(repository, never()).save(any());
        verify(searchSyncPublisher, never()).publishIndex(id);
    }

    @Test
    void finalProviderResultSettlesStatusClearsPendingAndPublishesSearchSync() {
        UUID id = UUID.randomUUID();
        RawVideoMetadataRecord existing = record(id);
        existing.setTmdbId("tmdb-previous");
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        MetadataResultProcessingResult result = newService(completedPermanentFailure()).process(EnrichmentResultMsg.builder()
                .videoId(id)
                .pipelineVersion("hash-v1")
                .providerType(ProviderType.DOUBAN)
                .success(false)
                .retryable(false)
                .build());

        ArgumentCaptor<RawVideoMetadataRecord> record = ArgumentCaptor.forClass(RawVideoMetadataRecord.class);
        verify(repository).save(record.capture());
        assertEquals("SUCCESS", result.enrichmentStatus());
        assertEquals("SUCCESS", record.getValue().getEnrichmentStatus());
        verify(pipelineRunRepository).markRunFinished(id, "hash-v1", "SUCCESS");
        verify(searchSyncPublisher).publishIndex(id);
    }

    private MetadataResultCollectorService newService(ProviderCompletion completion) {
        when(pipelineRunRepository.completeProvider(
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        anyBoolean(),
                        any(),
                        any()))
                .thenReturn(completion);
        return newService();
    }

    private MetadataResultCollectorService newService() {
        return new MetadataResultCollectorService(
                repository,
                searchSyncPublisher,
                pipelineRunRepository,
                metadataEnrichPublisher,
                aggregationWorkPublisher,
                configValues,
                objectMapper);
    }

    private ProviderCompletion completedSuccess() {
        return ProviderCompletion.tracked(true, 1, 1, 1, 0, 0, 0);
    }

    private ProviderCompletion completedRetryableFailure() {
        return ProviderCompletion.tracked(true, 1, 1, 0, 1, 1, 0);
    }

    private ProviderCompletion completedPermanentFailure() {
        return ProviderCompletion.tracked(true, 1, 1, 0, 1, 0, 1);
    }

    private ProviderCompletion pendingCompletion() {
        return ProviderCompletion.tracked(true, 2, 1, 1, 0, 0, 0);
    }

    private RawVideoMetadataRecord record(UUID id) {
        return new RawVideoMetadataRecord(
                id,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "{}",
                Set.of(),
                "PENDING",
                0,
                "BOUND",
                "hash-v1");
    }

    private void assertArrayContains(JsonNode node, String... values) {
        for (String value : values) {
            boolean found = false;
            for (JsonNode item : node) {
                found |= value.equals(item.asText());
            }
            assertTrue(found, "expected array to contain " + value + ": " + node);
        }
    }
}
