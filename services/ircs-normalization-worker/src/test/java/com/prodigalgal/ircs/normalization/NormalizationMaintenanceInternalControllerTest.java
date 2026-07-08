package com.prodigalgal.ircs.normalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.normalization.NormalizationMaintenanceRunResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class NormalizationMaintenanceInternalControllerTest {

    private final RawVideoNormalizationService normalizationService =
            org.mockito.Mockito.mock(RawVideoNormalizationService.class);
    private final NormalizationInternalAccessPolicy accessPolicy = new NormalizationInternalAccessPolicy();
    private final NormalizationMaintenanceInternalController controller =
            new NormalizationMaintenanceInternalController(normalizationService, accessPolicy);

    @Test
    void resetsRawNormalizationThroughInternalEndpoint() {
        UUID rawVideoId = UUID.randomUUID();
        when(normalizationService.resetAllNormalizationPending(7, false, 500))
                .thenReturn(new NormalizationMaintenanceRunResponse(
                        "sanitize",
                        12,
                        9,
                        0,
                        List.of(rawVideoId)));

        var response = controller.resetNormalization(7, false, 500, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().taskName()).isEqualTo("sanitize");
        assertThat(response.getBody().rawVideoCount()).isEqualTo(12);
        assertThat(response.getBody().changedRows()).isEqualTo(9);
        assertThat(response.getBody().enqueuedRows()).isZero();
        assertThat(response.getBody().sampleRawVideoIds()).containsExactly(rawVideoId);
        verify(normalizationService).resetAllNormalizationPending(7, false, 500);
    }

    @Test
    void enforcesInternalServiceIdentityWhenTokenIsRequired() {
        ReflectionTestUtils.setField(accessPolicy, "requireToken", true);
        ReflectionTestUtils.setField(accessPolicy, "configuredToken", "internal-token");
        ReflectionTestUtils.setField(accessPolicy, "requiredScope", "normalization:maintenance");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.resetNormalization(
                        5,
                        false,
                        500,
                        "ops-service",
                        "wrong-token",
                        "normalization:maintenance"));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
