package com.prodigalgal.ircs.metadata.result.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.prodigalgal.ircs.common.aggregation.AggregationWorkPublisher;
import com.prodigalgal.ircs.contracts.metadata.EnrichedMetadataDTO;
import com.prodigalgal.ircs.contracts.metadata.EnrichmentResultMsg;
import com.prodigalgal.ircs.metadata.config.MetadataConfigValues;
import com.prodigalgal.ircs.metadata.dispatch.messaging.MetadataEnrichPublisher;
import com.prodigalgal.ircs.metadata.pipeline.MetadataPipelineRunRepository;
import com.prodigalgal.ircs.metadata.pipeline.MetadataPipelineRunRepository.ProviderCompletion;
import com.prodigalgal.ircs.metadata.result.domain.RawVideoMetadataRecord;
import com.prodigalgal.ircs.metadata.result.dto.MetadataResultProcessingResult;
import com.prodigalgal.ircs.metadata.result.infrastructure.RawVideoMetadataRepository;
import com.prodigalgal.ircs.metadata.result.messaging.RawSearchSyncPublisher;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
@RequiredArgsConstructor
public class MetadataResultCollectorService {

    private static final int MAX_ENRICHMENT_RETRIES = 5;

    private final RawVideoMetadataRepository repository;
    private final RawSearchSyncPublisher searchSyncPublisher;
    private final MetadataPipelineRunRepository pipelineRunRepository;
    private final MetadataEnrichPublisher metadataEnrichPublisher;
    private final AggregationWorkPublisher aggregationWorkPublisher;
    private final MetadataConfigValues configValues;
    private final ObjectMapper objectMapper;

    @Transactional
    public MetadataResultProcessingResult process(EnrichmentResultMsg message) {
        Optional<RawVideoMetadataRecord> recordOpt = repository.findById(message.getVideoId());
        if (recordOpt.isEmpty()) {
            log.warn("Metadata result references missing raw video: {}", message.getVideoId());
            return new MetadataResultProcessingResult(message.getVideoId(), false, null);
        }

        RawVideoMetadataRecord record = recordOpt.get();
        if (isStale(message.getPipelineVersion(), record.getDataHash())) {
            log.info(
                    "Ignoring stale metadata result: videoId={}, provider={}, commandVersion={}, currentVersion={}",
                    message.getVideoId(),
                    message.getProviderType(),
                    message.getPipelineVersion(),
                    record.getDataHash());
            return new MetadataResultProcessingResult(record.getId(), true, "STALE");
        }

        boolean metadataUpdated = false;
        if (message.isSuccess() && message.getMetadata() != null) {
            metadataUpdated = applyMetadata(record, message.getMetadata());
        }

        ProviderCompletion completion = pipelineRunRepository.completeProvider(
                record.getId(),
                record.getDataHash(),
                message.getProviderType(),
                message.isSuccess(),
                message.isRetryable(),
                message.getErrorCode(),
                message.getErrorMessage());
        if (!completion.tracked()) {
            log.warn(
                    "Metadata result has no durable provider run: videoId={}, provider={}, pipelineVersion={}",
                    record.getId(),
                    message.getProviderType(),
                    message.getPipelineVersion());
            return new MetadataResultProcessingResult(record.getId(), true, record.getEnrichmentStatus());
        }
        if (!completion.newlyCompleted()) {
            return new MetadataResultProcessingResult(record.getId(), true, record.getEnrichmentStatus());
        }
        if (!completion.allCompleted()) {
            repository.save(record);
            return new MetadataResultProcessingResult(record.getId(), true, record.getEnrichmentStatus());
        }

        finalizeVideoStatus(record, completion, metadataUpdated);
        repository.save(record);
        pipelineRunRepository.markRunFinished(record.getId(), record.getDataHash(), record.getEnrichmentStatus());
        searchSyncPublisher.publishIndex(record.getId());
        aggregationWorkPublisher.enqueue(record.getId(), record.getDataHash(), "metadata-finalized");
        scheduleRetryIfNeeded(record);

        return new MetadataResultProcessingResult(record.getId(), true, record.getEnrichmentStatus());
    }

    private void scheduleRetryIfNeeded(RawVideoMetadataRecord record) {
        if (!"FAILED".equals(record.getEnrichmentStatus())) {
            return;
        }
        try {
            metadataEnrichPublisher.publish(record.getId(), record.getDataHash(), configValues.retryDelay());
        } catch (RuntimeException ex) {
            log.warn(
                    "Metadata retry Valkey submit failed: videoId={}, pipelineVersion={}, error={}",
                    record.getId(),
                    record.getDataHash(),
                    ex.getMessage());
            throw ex;
        }
    }

    private boolean isStale(String pipelineVersion, String currentDataHash) {
        if (StringUtils.hasText(currentDataHash)) {
            return !StringUtils.hasText(pipelineVersion) || !pipelineVersion.trim().equals(currentDataHash);
        }
        return StringUtils.hasText(pipelineVersion);
    }

    private boolean applyMetadata(RawVideoMetadataRecord record, EnrichedMetadataDTO metadata) {
        boolean updated = false;
        updated |= fillAuthoritativeId(record::getDoubanId, record::setDoubanId, metadata.getDoubanId());
        updated |= fillAuthoritativeId(record::getTmdbId, record::setTmdbId, metadata.getTmdbId());
        updated |= fillAuthoritativeId(record::getImdbId, record::setImdbId, metadata.getImdbId());
        updated |= fillAuthoritativeId(
                record::getRottenTomatoesId,
                record::setRottenTomatoesId,
                metadata.getRottenTomatoesId());

        if (!record.isFieldLocked("description") && RawVideoMetadataRecord.hasText(metadata.getDescription())) {
            record.setDescription(metadata.getDescription());
            updated = true;
        }
        if (!record.isFieldLocked("score") && metadata.getScore() != null) {
            record.setScore(metadata.getScore());
            updated = true;
        }
        if (!record.isFieldLocked("year") && RawVideoMetadataRecord.hasText(metadata.getYear())) {
            record.setYear(metadata.getYear());
            updated = true;
        }
        if (RawVideoMetadataRecord.hasText(metadata.getOriginalTitle())) {
            String originalTitle = metadata.getOriginalTitle();
            if (!RawVideoMetadataRecord.hasText(record.getAliasTitle())) {
                record.setAliasTitle(originalTitle);
                updated = true;
            } else if (!record.getAliasTitle().contains(originalTitle)) {
                record.setAliasTitle(record.getAliasTitle() + "/" + originalTitle);
                updated = true;
            }
        }
        updated |= mergeRawMetadata(record, metadata);
        return updated;
    }

    private boolean mergeRawMetadata(RawVideoMetadataRecord record, EnrichedMetadataDTO metadata) {
        ObjectNode merged = readRawMetadata(record.getRawMetadata());
        String before = merged.toString();

        mergeDelimitedText(merged, "area", metadata.getArea(), ", ");
        mergeDelimitedText(merged, "language", metadata.getLanguage(), "/");
        mergeArrayValues(merged, "genreNames", metadata.getGenreNames());
        mergeArrayValues(merged, "actorNames", metadata.getActorNames());
        mergeArrayValues(merged, "directorNames", metadata.getDirectorNames());
        putIfAbsent(merged, "posterUrl", metadata.getPosterUrl());
        putIfAbsent(merged, "coverImageUrl", metadata.getPosterUrl());
        putIfAbsent(merged, "backdropUrl", metadata.getBackdropUrl());
        if (metadata.getImageSource() != null && isMissing(merged.get("imageSource"))) {
            merged.put("imageSource", metadata.getImageSource().name());
        }

        String after = merged.toString();
        if (!after.equals(before)) {
            record.setRawMetadata(after);
            return true;
        }
        return false;
    }

    private ObjectNode readRawMetadata(String rawMetadata) {
        if (!StringUtils.hasText(rawMetadata)) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode root = objectMapper.readTree(rawMetadata);
            if (root != null && root.isObject()) {
                return (ObjectNode) root;
            }
            ObjectNode wrapper = objectMapper.createObjectNode();
            if (root != null && !root.isNull()) {
                wrapper.set("previousRawMetadata", root);
            }
            return wrapper;
        } catch (Exception ex) {
            log.warn("Unable to parse raw_metadata for enrichment merge; replacing with provider fields", ex);
            return objectMapper.createObjectNode();
        }
    }

    private void mergeDelimitedText(ObjectNode target, String fieldName, String incoming, String delimiter) {
        if (!StringUtils.hasText(incoming)) {
            return;
        }
        JsonNode current = target.get(fieldName);
        if (isMissing(current)) {
            target.put(fieldName, incoming.trim());
            return;
        }

        Set<String> values = relationValues(current);
        boolean changed = values.add(incoming.trim());
        if (changed) {
            target.put(fieldName, String.join(delimiter, values));
        }
    }

    private void mergeArrayValues(ObjectNode target, String fieldName, Set<String> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }
        Set<String> values = relationValues(target.get(fieldName));
        boolean changed = false;
        for (String value : incoming) {
            if (StringUtils.hasText(value)) {
                changed |= values.add(value.trim());
            }
        }
        if (!changed && !values.isEmpty() && target.get(fieldName) != null && target.get(fieldName).isArray()) {
            return;
        }
        if (!values.isEmpty() && (changed || target.get(fieldName) == null || !target.get(fieldName).isArray())) {
            ArrayNode array = objectMapper.createArrayNode();
            values.forEach(array::add);
            target.set(fieldName, array);
        }
    }

    private Set<String> relationValues(JsonNode node) {
        Set<String> values = new LinkedHashSet<>();
        collectRelationValues(node, values);
        return values;
    }

    private void collectRelationValues(JsonNode node, Set<String> values) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectRelationValues(item, values));
            return;
        }
        String text = node.isTextual() ? node.asText() : node.asText(node.toString());
        if (!StringUtils.hasText(text)) {
            return;
        }
        for (String part : text.split("[,，、/|;；]+")) {
            if (StringUtils.hasText(part)) {
                values.add(part.trim());
            }
        }
    }

    private void putIfAbsent(ObjectNode target, String fieldName, String value) {
        if (StringUtils.hasText(value) && isMissing(target.get(fieldName))) {
            target.put(fieldName, value.trim());
        }
    }

    private boolean isMissing(JsonNode node) {
        return node == null || node.isNull() || (node.isTextual() && !StringUtils.hasText(node.asText()));
    }

    private boolean fillAuthoritativeId(
            java.util.function.Supplier<String> current,
            java.util.function.Consumer<String> setter,
            String incoming) {
        if (!RawVideoMetadataRecord.hasText(current.get()) && RawVideoMetadataRecord.hasText(incoming)) {
            setter.accept(incoming);
            return true;
        }
        return false;
    }

    private void finalizeVideoStatus(
            RawVideoMetadataRecord record,
            ProviderCompletion completion,
            boolean metadataUpdated) {
        if (record.hasAuthoritativeId()) {
            record.setEnrichmentStatus("SUCCESS");
            record.setEnrichmentRetryCount(0);
            if (metadataUpdated) {
                record.setAggregationStatus("PENDING");
            }
            return;
        }

        if (completion.retryableFailureCount() <= 0) {
            record.setEnrichmentStatus("PERMANENT_FAILURE");
            return;
        }

        int nextRetry = (record.getEnrichmentRetryCount() == null ? 0 : record.getEnrichmentRetryCount()) + 1;
        if (nextRetry >= MAX_ENRICHMENT_RETRIES) {
            record.setEnrichmentStatus("PERMANENT_FAILURE");
            return;
        }

        record.setEnrichmentStatus("FAILED");
        record.setEnrichmentRetryCount(nextRetry);
    }
}
