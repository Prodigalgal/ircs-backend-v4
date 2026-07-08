package com.prodigalgal.ircs.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CredentialControllerTest {

    private final CredentialService credentialService = org.mockito.Mockito.mock(CredentialService.class);
    private final CredentialController controller = new CredentialController(credentialService);

    @Test
    void delegatesListFiltersToService() {
        CredentialSummary summary = summary(UUID.randomUUID());
        when(credentialService.list("tmdb", true, 10)).thenReturn(List.of(summary));

        List<CredentialSummary> result = controller.listCredentials("tmdb", true, 10);

        assertEquals(List.of(summary), result);
        verify(credentialService).list("tmdb", true, 10);
    }

    @Test
    void returnsCredentialWhenFound() {
        UUID id = UUID.randomUUID();
        CredentialSummary summary = summary(id);
        when(credentialService.findById(id)).thenReturn(Optional.of(summary));

        var response = controller.getCredential(id);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(summary, response.getBody());
    }

    @Test
    void returnsNotFoundWhenMissing() {
        UUID id = UUID.randomUUID();
        when(credentialService.findById(id)).thenReturn(Optional.empty());

        var response = controller.getCredential(id);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void delegatesTemplateLookup() {
        CredentialTemplateField field = new CredentialTemplateField(
                "api_key", "TMDB API Key", "text", true, null, null, null, null);
        when(credentialService.templates("TMDB")).thenReturn(List.of(field));

        assertEquals(List.of(field), controller.credentialTemplates("TMDB"));
    }

    @Test
    void createsCredentialWithLocation() {
        CredentialWriteRequest request = writeRequest();
        CredentialSummary summary = summary(UUID.randomUUID());
        when(credentialService.create(request)).thenReturn(summary);

        var response = controller.createCredential(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("/api/v1/credentials/" + summary.id(), response.getHeaders().getLocation().toString());
        assertSame(summary, response.getBody());
    }

    @Test
    void updatesCredentialWhenFound() {
        UUID id = UUID.randomUUID();
        CredentialWriteRequest request = writeRequest();
        CredentialSummary summary = summary(id);
        when(credentialService.update(id, request)).thenReturn(Optional.of(summary));

        var response = controller.updateCredential(id, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(summary, response.getBody());
    }

    @Test
    void deletesCredentialAsNoContent() {
        UUID id = UUID.randomUUID();

        var response = controller.deleteCredential(id);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(credentialService).delete(id);
    }

    @Test
    void refreshesCredentialPoolContract() {
        var response = controller.refreshCredentials();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(credentialService).refreshPool();
    }

    private CredentialSummary summary(UUID id) {
        return new CredentialSummary(
                id,
                Instant.parse("2026-06-03T00:00:00Z"),
                Instant.parse("2026-06-03T00:00:00Z"),
                "TMDB",
                "dev",
                "abcdef12",
                Map.of("api_key", "secret"),
                true,
                1,
                30,
                "MINUTE",
                0L,
                0L,
                0L,
                0L,
                "remark",
                0L,
                0L,
                0L,
                0L,
                0.0,
                0L,
                List.of("api_key"));
    }

    private CredentialWriteRequest writeRequest() {
        return new CredentialWriteRequest(
                "TMDB",
                "dev",
                Map.of("api_key", "secret"),
                true,
                1,
                30,
                "MINUTE",
                0L,
                0L,
                0L,
                0L,
                "remark");
    }
}
