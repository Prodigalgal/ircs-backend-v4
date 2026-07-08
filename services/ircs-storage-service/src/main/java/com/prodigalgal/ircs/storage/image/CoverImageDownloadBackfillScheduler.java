package com.prodigalgal.ircs.storage.image;

import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
class CoverImageDownloadBackfillScheduler {

    private final CoverImageAdminService service;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-cover-download-backfill-trigger-vt-");
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${app.storage.image.download-backfill.enabled:true}")
    private boolean enabled;

    @Value("${app.storage.image.download-backfill.batch-size:25}")
    private int batchSize;

    @Scheduled(
            initialDelayString = "${app.storage.image.download-backfill.initial-delay-ms:60000}",
            fixedDelayString = "${app.storage.image.download-backfill.fixed-delay-ms:30000}")
    void runScheduled() {
        ScheduledTriggers.submit(triggerExecutor, this::runOnce, log, "storage.cover-download-backfill.run");
    }

    @PreDestroy
    void shutdownTriggerExecutor() {
        triggerExecutor.shutdownNow();
    }

    void runOnce() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            int queued = service.enqueueDownloadBackfill(Math.max(1, batchSize));
            if (queued > 0) {
                log.info("Queued cover image download backfill: count={}", queued);
            }
        } catch (RuntimeException ex) {
            log.warn("Cover image download backfill failed: {}", ex.getMessage());
        } finally {
            running.set(false);
        }
    }
}
