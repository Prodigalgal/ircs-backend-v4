package com.prodigalgal.ircs.scraper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TmdbCredentialResolverTest {

    private final OutboundHttpClient httpClient = org.mockito.Mockito.mock(OutboundHttpClient.class);
    private final ScraperTrendConfigValues configValues = org.mockito.Mockito.mock(ScraperTrendConfigValues.class);
    private final TmdbCredentialResolver resolver =
            TmdbCredentialResolver.forTest(new ObjectMapper(), httpClient, configValues);

    @Test
    void leasesTmdbApiKeyFromCredentialServiceBeforeEnvFallback() throws Exception {
        when(configValues.tmdbCredentialServiceEnabled()).thenReturn(true);
        when(configValues.tmdbCredentialServiceBaseUrl()).thenReturn("http://credential-service");
        when(configValues.tmdbCredentialServiceLeaseLimit()).thenReturn(1);
        when(configValues.tmdbCredentialServiceRequestTimeout()).thenReturn(java.time.Duration.ofSeconds(10));
        when(configValues.tmdbCredentialServiceId()).thenReturn("scraper-service");
        when(configValues.tmdbCredentialServiceToken()).thenReturn("service-token");
        when(configValues.tmdbCredentialServiceScopes()).thenReturn("credential:lease");
        when(configValues.tmdbApiKey()).thenReturn("env-key");
        when(httpClient.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new OutboundHttpResponse(
                        200,
                        Map.of(),
                        """
                                [{"secretPayload":{"api_key":"leased-key"}}]
                                """.getBytes(StandardCharsets.UTF_8)));

        assertThat(resolver.resolveApiKey()).contains("leased-key");
    }

    @Test
    void fallsBackToEnvApiKeyWhenCredentialServiceHasNoLease() throws Exception {
        when(configValues.tmdbCredentialServiceEnabled()).thenReturn(true);
        when(configValues.tmdbCredentialServiceBaseUrl()).thenReturn("http://credential-service");
        when(configValues.tmdbCredentialServiceLeaseLimit()).thenReturn(1);
        when(configValues.tmdbCredentialServiceRequestTimeout()).thenReturn(java.time.Duration.ofSeconds(10));
        when(configValues.tmdbCredentialServiceId()).thenReturn("scraper-service");
        when(configValues.tmdbCredentialServiceToken()).thenReturn("service-token");
        when(configValues.tmdbCredentialServiceScopes()).thenReturn("credential:lease");
        when(configValues.tmdbApiKey()).thenReturn("env-key");
        when(httpClient.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new OutboundHttpResponse(200, Map.of(), "[]".getBytes(StandardCharsets.UTF_8)));

        assertThat(resolver.resolveApiKey()).contains("env-key");
    }
}
