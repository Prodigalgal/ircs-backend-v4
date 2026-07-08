package com.prodigalgal.ircs.task.controller;


import com.prodigalgal.ircs.task.security.TaskInternalAccessPolicy;
import com.prodigalgal.ircs.task.application.TrendDiscoveryTaskService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.trend.TrendDiscoveryScheduleRequest;
import com.prodigalgal.ircs.contracts.trend.TrendDiscoveryScheduleResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TrendDiscoveryInternalControllerTest {

    private final TrendDiscoveryTaskService service = org.mockito.Mockito.mock(TrendDiscoveryTaskService.class);
    private final TaskInternalAccessPolicy accessPolicy = new TaskInternalAccessPolicy();
    private final TrendDiscoveryInternalController controller =
            new TrendDiscoveryInternalController(service, accessPolicy);

    @Test
    void schedulesTrendDiscoveryThroughInternalEndpoint() {
        TrendDiscoveryScheduleRequest request = new TrendDiscoveryScheduleRequest(
                List.of("Codex Trend"),
                1,
                1,
                0,
                false);
        when(service.schedule(request, "corr-trend"))
                .thenReturn(new TrendDiscoveryScheduleResponse(
                        "trend-discovery",
                        1,
                        1,
                        1,
                        0,
                        1,
                        List.of(),
                        List.of()));

        var response = controller.schedule(request, "corr-trend", null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().queuedTasks()).isEqualTo(1);
        verify(service).schedule(request, "corr-trend");
    }
}
