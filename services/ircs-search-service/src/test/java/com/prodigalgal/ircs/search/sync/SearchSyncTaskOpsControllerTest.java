package com.prodigalgal.ircs.search.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class SearchSyncTaskOpsControllerTest {

    private final SearchSyncTaskOpsRepository repository =
            org.mockito.Mockito.mock(SearchSyncTaskOpsRepository.class);
    private final SearchSyncTaskOpsController controller = new SearchSyncTaskOpsController(repository);

    @Test
    void returnsNoStoreStats() {
        SearchSyncTaskStatsResponse stats = new SearchSyncTaskStatsResponse(
                true,
                null,
                2,
                1,
                0,
                1,
                0,
                1,
                1,
                1,
                2,
                0,
                3,
                null,
                null,
                SearchSyncQueueStats.empty(),
                SearchSyncQueueStats.empty(),
                SearchSyncWorkerStats.empty());
        when(repository.stats()).thenReturn(stats);

        var response = controller.stats();

        assertEquals(stats, response.getBody());
        assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        verify(repository).stats();
    }
}
