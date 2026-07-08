package com.prodigalgal.ircs.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.credential.ProviderCredentialLease;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class InternalCredentialControllerTest {

    private final CredentialService credentialService = org.mockito.Mockito.mock(CredentialService.class);
    private final CredentialInternalAccessProperties properties = new CredentialInternalAccessProperties();
    private final InternalCredentialController controller = new InternalCredentialController(credentialService, properties);

    @Test
    void delegatesLeaseRequestToServiceWhenServiceIdentityMatches() {
        properties.setToken("internal-token");
        ProviderCredentialLease lease = ProviderCredentialLease.builder()
                .id(UUID.randomUUID())
                .provider("TMDB")
                .secretPayload(Map.of("api_key", "secret"))
                .build();
        when(credentialService.leaseProviderCredentials("tmdb", "api_key", 10)).thenReturn(List.of(lease));

        List<ProviderCredentialLease> result =
                controller.leaseProviderCredentials(
                        "tmdb",
                        "api_key",
                        10,
                        "metadata-worker",
                        "internal-token",
                        "credential:lease");

        assertEquals(List.of(lease), result);
        verify(credentialService).leaseProviderCredentials("tmdb", "api_key", 10);
    }

    @Test
    void rejectsLeaseRequestWhenServiceTokenIsMissing() {
        properties.setToken("internal-token");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.leaseProviderCredentials(
                        "tmdb",
                        "api_key",
                        10,
                        "metadata-worker",
                        null,
                        "credential:lease"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void rejectsLeaseRequestWhenServiceTokenIsWrong() {
        properties.setToken("internal-token");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.leaseProviderCredentials(
                        "tmdb",
                        "api_key",
                        10,
                        "metadata-worker",
                        "wrong-token",
                        "credential:lease"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void rejectsLeaseRequestWhenServiceScopeIsMissing() {
        properties.setToken("internal-token");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.leaseProviderCredentials(
                        "tmdb",
                        "api_key",
                        10,
                        "metadata-worker",
                        "internal-token",
                        "search:sync"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void rejectsLeaseRequestWhenServiceTokenIsNotConfigured() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.leaseProviderCredentials(
                        "tmdb",
                        "api_key",
                        10,
                        "metadata-worker",
                        "internal-token",
                        "credential:lease"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
    }
}
