package com.prodigalgal.ircs.task.application;

import com.prodigalgal.ircs.contracts.trend.TrendDiscoveryScheduleRequest;
import com.prodigalgal.ircs.contracts.trend.TrendDiscoveryScheduleResponse;
import com.prodigalgal.ircs.task.domain.MediaRequestBatchItemCandidate;
import com.prodigalgal.ircs.task.domain.MediaRequestCandidate;
import com.prodigalgal.ircs.task.dto.MediaRequestBatchActionResponse;
import com.prodigalgal.ircs.task.dto.MediaRequestBatchResponse;
import com.prodigalgal.ircs.task.dto.TaskCardSummary;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.infrastructure.MediaRequestContentLookupRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MediaRequestBatchService {

    private final JdbcCollectionTaskRepository taskRepository;
    private final MediaRequestContentLookupRepository contentLookupRepository;
    private final TrendDiscoveryTaskService trendDiscoveryTaskService;
    private final TaskCommandService taskCommandService;

    @Value("${app.task.media-request.start-page:1}")
    private int startPage;

    @Value("${app.task.media-request.end-page:1}")
    private int endPage;

    @Value("${app.task.media-request.request-fixed-delay-ms:0}")
    private long requestFixedDelayMs;

    @Value("${app.task.media-request.max-data-sources:0}")
    private int maxDataSources;

    public Page<MediaRequestBatchResponse> findBatches(Pageable pageable, String status) {
        return taskRepository.findMediaRequestBatches(pageable, normalizeStatus(status));
    }

    public MediaRequestBatchResponse findBatch(UUID batchId) {
        return taskRepository.findMediaRequestBatch(batchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media request batch not found"));
    }

    public Page<TaskCardSummary> findCollectionTasks(UUID batchId, Pageable pageable) {
        if (!taskRepository.mediaRequestBatchExists(batchId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media request batch not found");
        }
        return taskRepository.findAll(pageable, null, null, null, null, batchId);
    }

    @Transactional
    public Optional<UUID> createBatchFromPendingRequests(List<MediaRequestCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("media request candidates are required");
        }
        List<MediaRequestCandidate> missingCandidates = new ArrayList<>(candidates.size());
        for (MediaRequestCandidate candidate : candidates) {
            var existing = contentLookupRepository.findExistingVideo(candidate.title(), candidate.releaseYear());
            if (existing.isPresent()) {
                taskRepository.markMediaRequestSkippedExisting(
                        candidate.id(),
                        existing.get().id(),
                        existing.get().source());
            } else {
                missingCandidates.add(candidate);
            }
        }
        if (missingCandidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(taskRepository.createMediaRequestBatch(missingCandidates));
    }

    @Transactional
    public MediaRequestBatchActionResponse startBatch(UUID batchId) {
        if (!taskRepository.markMediaRequestBatchRunning(batchId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Media request batch is not ready");
        }
        int scheduled = 0;
        int skipped = 0;
        int failed = 0;
        for (MediaRequestBatchItemCandidate item : taskRepository.findReadyMediaRequestBatchItems(batchId)) {
            var existing = contentLookupRepository.findExistingVideo(item.title(), item.releaseYear());
            if (existing.isPresent()) {
                taskRepository.markMediaRequestBatchItemSkipped(
                        item.itemId(),
                        item.mediaRequestId(),
                        existing.get().id(),
                        existing.get().source());
                skipped++;
                continue;
            }
            try {
                TrendDiscoveryScheduleResponse response = trendDiscoveryTaskService.prepare(
                        new TrendDiscoveryScheduleRequest(
                                List.of(item.keyword()),
                                positiveOrDefault(startPage, 1),
                                positiveOrDefault(endPage, 1),
                                boundedFixedDelayMs(),
                                false,
                                Math.max(0, maxDataSources)),
                        "media-request-batch:" + batchId + ":" + item.itemId(),
                        mediaRequestTaskMetadata(batchId, item));
                if (!response.errors().isEmpty()) {
                    failed++;
                    taskRepository.markMediaRequestBatchItemFailed(
                            item.itemId(),
                            item.mediaRequestId(),
                            String.join("; ", response.errors()));
                } else if (response.tasks().isEmpty()) {
                    failed++;
                    taskRepository.markMediaRequestBatchItemFailed(
                            item.itemId(),
                            item.mediaRequestId(),
                            "No collection tasks were prepared for media request");
                } else {
                    int acceptedCount = startPreparedTasks(response);
                    int preparedCount = response.tasks().size();
                    if (acceptedCount == preparedCount) {
                        taskRepository.markMediaRequestBatchItemScheduled(item.itemId(), item.mediaRequestId(), acceptedCount);
                        scheduled++;
                    } else {
                        failed++;
                        taskRepository.markMediaRequestBatchItemFailed(
                                item.itemId(),
                                item.mediaRequestId(),
                                "Only %d/%d prepared collection tasks were accepted for execution"
                                        .formatted(acceptedCount, preparedCount));
                    }
                }
            } catch (RuntimeException ex) {
                failed++;
                taskRepository.markMediaRequestBatchItemFailed(
                        item.itemId(),
                        item.mediaRequestId(),
                        safeMessage(ex));
            }
        }
        String finalStatus = failed > 0 && (scheduled > 0 || skipped > 0) ? "PARTIAL"
                : failed > 0 ? "FAILED" : "COMPLETED";
        taskRepository.finishMediaRequestBatch(batchId, finalStatus, failed > 0 ? "Some media requests failed" : null);
        return new MediaRequestBatchActionResponse(batchId, finalStatus, scheduled, skipped, failed);
    }

    @Transactional
    public MediaRequestBatchActionResponse cancelBatch(UUID batchId) {
        if (!taskRepository.cancelMediaRequestBatch(batchId, "Cancelled by administrator")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Media request batch cannot be cancelled");
        }
        return new MediaRequestBatchActionResponse(batchId, "CANCELLED", 0, 0, 0);
    }

    private Map<String, Object> mediaRequestTaskMetadata(UUID batchId, MediaRequestBatchItemCandidate item) {
        return Map.of(
                "ircsMediaRequestBatch",
                Map.of(
                        "batchId", batchId.toString(),
                        "itemId", item.itemId().toString(),
                        "mediaRequestId", item.mediaRequestId().toString(),
                        "title", item.title(),
                        "releaseYear", item.releaseYear() == null ? 0 : item.releaseYear()));
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        return status.trim().toUpperCase();
    }

    private String safeMessage(Throwable ex) {
        if (ex == null || !StringUtils.hasText(ex.getMessage())) {
            return ex == null ? "unknown" : ex.getClass().getSimpleName();
        }
        String message = ex.getMessage().replaceAll("\\s+", " ").trim();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private int startPreparedTasks(TrendDiscoveryScheduleResponse response) {
        int accepted = 0;
        for (var task : response.tasks()) {
            if (taskCommandService.startInternal(task.taskId(), false)) {
                accepted++;
            }
        }
        return accepted;
    }

    private int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private int boundedFixedDelayMs() {
        long bounded = Math.min(Integer.MAX_VALUE, Math.max(0L, requestFixedDelayMs));
        return (int) bounded;
    }
}
