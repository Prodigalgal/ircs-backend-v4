package com.prodigalgal.ircs.interaction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.prodigalgal.ircs.contracts.interaction.WatchProgressMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class WatchProgressBatchServiceTest {

    private final JdbcInteractionRepository repository = org.mockito.Mockito.mock(JdbcInteractionRepository.class);
    private final WatchProgressBatchService service = new WatchProgressBatchService(repository);

    @Test
    void batchUpsertKeepsLatestMessagePerMemberAndUnifiedVideo() {
        UUID memberId = UUID.randomUUID();
        UUID unifiedVideoId = UUID.randomUUID();
        UUID oldVideoId = UUID.randomUUID();
        UUID newVideoId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        Instant older = Instant.parse("2026-06-07T01:00:00Z");
        Instant newer = Instant.parse("2026-06-07T01:01:00Z");

        WatchProgressMessage oldMessage = new WatchProgressMessage(
                memberId,
                unifiedVideoId,
                oldVideoId,
                null,
                "第1集",
                10,
                100,
                older);
        WatchProgressMessage newMessage = new WatchProgressMessage(
                memberId,
                unifiedVideoId,
                newVideoId,
                episodeId,
                " ",
                -1,
                -10,
                newer);

        service.batchUpsert(List.of(oldMessage, newMessage));

        verify(repository).upsertWatchHistoryIfNewer(
                any(UUID.class),
                eq(memberId),
                eq(unifiedVideoId),
                eq(newVideoId),
                eq(episodeId),
                eq(WatchProgressBatchService.DEFAULT_EPISODE_NAME),
                eq(0),
                eq(0),
                eq(newer));
    }

    @Test
    void batchUpsertIgnoresNullAndMissingKeyMessages() {
        service.batchUpsert(List.of(
                new WatchProgressMessage(null, UUID.randomUUID(), null, null, "第1集", 1, 10, Instant.now()),
                new WatchProgressMessage(UUID.randomUUID(), null, null, null, "第1集", 1, 10, Instant.now())));

        verify(repository, never()).upsertWatchHistoryIfNewer(
                any(UUID.class),
                any(UUID.class),
                any(UUID.class),
                any(),
                any(),
                any(),
                any(Integer.class),
                any(Integer.class),
                any(Instant.class));
    }

    @Test
    void batchUpsertIgnoresOrphanedDataIntegrityViolation() {
        UUID memberId = UUID.randomUUID();
        UUID unifiedVideoId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-06-07T01:02:00Z");
        doThrow(new DataIntegrityViolationException("fk"))
                .when(repository)
                .upsertWatchHistoryIfNewer(
                        any(UUID.class),
                        eq(memberId),
                        eq(unifiedVideoId),
                        any(),
                        any(),
                        any(),
                        any(Integer.class),
                        any(Integer.class),
                        eq(timestamp));

        WatchProgressMessage message = new WatchProgressMessage(
                memberId,
                unifiedVideoId,
                null,
                null,
                "第1集",
                1,
                10,
                timestamp);

        assertDoesNotThrow(() -> service.batchUpsert(List.of(message)));
    }
}
