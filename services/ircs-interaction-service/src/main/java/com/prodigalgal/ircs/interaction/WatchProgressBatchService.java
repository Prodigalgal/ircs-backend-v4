package com.prodigalgal.ircs.interaction;

import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;

import com.prodigalgal.ircs.contracts.interaction.WatchProgressMessage;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
@RequiredArgsConstructor
public class WatchProgressBatchService {

    static final String DEFAULT_EPISODE_NAME = "未知剧集";

    private final JdbcInteractionRepository repository;

    @Transactional
    public void batchUpsert(List<WatchProgressMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        Map<ProgressKey, NormalizedWatchProgress> latestByKey = new HashMap<>();
        for (WatchProgressMessage message : messages) {
            NormalizedWatchProgress normalized = normalize(message);
            if (normalized == null) {
                continue;
            }
            latestByKey.merge(
                    new ProgressKey(normalized.memberId(), normalized.unifiedVideoId()),
                    normalized,
                    (left, right) -> right.timestamp().isAfter(left.timestamp()) ? right : left);
        }

        for (NormalizedWatchProgress progress : latestByKey.values()) {
            upsert(progress);
        }
    }

    private NormalizedWatchProgress normalize(WatchProgressMessage message) {
        if (message == null || message.getMemberId() == null || message.getUnifiedVideoId() == null) {
            return null;
        }
        Instant timestamp = message.getTimestamp() == null ? Instant.now() : message.getTimestamp();
        String episodeName = StringUtils.hasText(message.getEpisodeName())
                ? message.getEpisodeName().trim()
                : DEFAULT_EPISODE_NAME;
        return new NormalizedWatchProgress(
                message.getMemberId(),
                message.getUnifiedVideoId(),
                message.getVideoId(),
                message.getEpisodeId(),
                episodeName,
                Math.max(message.getProgressSeconds(), 0),
                Math.max(message.getDurationSeconds(), 0),
                timestamp);
    }

    private void upsert(NormalizedWatchProgress progress) {
        try {
            repository.upsertWatchHistoryIfNewer(
                    IrcsUuidGenerators.nextId(),
                    progress.memberId(),
                    progress.unifiedVideoId(),
                    progress.videoId(),
                    progress.episodeId(),
                    progress.episodeName(),
                    progress.progressSeconds(),
                    progress.durationSeconds(),
                    progress.timestamp());
        } catch (DataIntegrityViolationException ex) {
            log.info(
                    "Ignored orphaned watch progress message: memberId={}, unifiedVideoId={}",
                    progress.memberId(),
                    progress.unifiedVideoId());
        }
    }

    private record ProgressKey(UUID memberId, UUID unifiedVideoId) {
    }

    private record NormalizedWatchProgress(
            UUID memberId,
            UUID unifiedVideoId,
            UUID videoId,
            UUID episodeId,
            String episodeName,
            int progressSeconds,
            int durationSeconds,
            Instant timestamp) {
    }
}
