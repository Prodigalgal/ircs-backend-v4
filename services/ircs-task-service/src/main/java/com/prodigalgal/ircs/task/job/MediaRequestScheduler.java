package com.prodigalgal.ircs.task.job;

import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.task.application.MediaRequestBatchService;
import com.prodigalgal.ircs.task.domain.MediaRequestCandidate;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.infrastructure.TaskDistributedLockRunner;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "app.task.media-request.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
class MediaRequestScheduler {

    static final String LOCK_NAME = "task:media-request:schedule";

    private final JdbcCollectionTaskRepository taskRepository;
    private final MediaRequestBatchService batchService;
    private final TaskDistributedLockRunner lockRunner;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-media-request-scheduler-trigger-vt-");

    @Value("${app.task.media-request.batch-size:20}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${app.task.media-request.fixed-delay-ms:60000}",
            initialDelayString = "${app.task.media-request.initial-delay-ms:30000}")
    void schedulePendingMediaRequests() {
        ScheduledTriggers.submit(triggerExecutor, this::schedulePendingMediaRequestsOnce, log, "task.media-request.schedule");
    }

    @PreDestroy
    void shutdownTriggerExecutor() {
        triggerExecutor.shutdownNow();
    }

    void schedulePendingMediaRequestsOnce() {
        if (!lockRunner.runExclusive(LOCK_NAME, this::schedulePendingMediaRequestsLocked)) {
            log.debug("Media request scheduler skipped: distributed lock is held by another instance");
        }
    }

    void schedulePendingMediaRequestsLocked() {
        List<MediaRequestCandidate> candidates = taskRepository.claimPendingMediaRequests(batchSize);
        if (candidates.isEmpty()) {
            return;
        }
        List<String> keywords = candidates.stream()
                .map(MediaRequestCandidate::keyword)
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .distinct()
                .toList();
        if (keywords.isEmpty()) {
            taskRepository.markMediaRequestsPending(ids(candidates), "No valid media request keywords");
            return;
        }
        try {
            Optional<UUID> batchId = batchService.createBatchFromPendingRequests(candidates);
            if (batchId.isPresent()) {
                log.info("Created media request batch {}, claimedRequestCount={}", batchId.get(), candidates.size());
            } else {
                log.info("Skipped media request batch creation because all {} claimed requests already exist internally",
                        candidates.size());
            }
        } catch (RuntimeException ex) {
            taskRepository.markMediaRequestsPending(ids(candidates), safeMessage(ex));
            throw ex;
        }
    }

    private static List<UUID> ids(List<MediaRequestCandidate> candidates) {
        return candidates.stream().map(MediaRequestCandidate::id).toList();
    }

    private static String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
