package com.prodigalgal.ircs.notification.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpException;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.credential.ProviderCredentialLease;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

public class CredentialServiceMailCredentialRepository {

    private static final String PROVIDER = "MAIL";
    private static final String USERNAME_KEY = "username";
    private static final String PASSWORD_KEY = "password";
    private static final String SMTP_HOST_KEY = "smtp_host";
    private static final String SMTP_PORT_KEY = "smtp_port";
    private static final String SMTP_PROTOCOL_KEY = "smtp_protocol";
    private static final String SMTP_AUTH_KEY = "smtp_auth";
    private static final String SMTP_STARTTLS_KEY = "smtp_starttls_enabled";
    private static final String SMTP_SSL_KEY = "smtp_ssl_enabled";
    private static final String SMTP_TIMEOUT_KEY = "smtp_timeout_ms";

    private final OutboundHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final NotificationMailProperties properties;
    private final AtomicLong selectionCursor = new AtomicLong();

    CredentialServiceMailCredentialRepository(
            OutboundHttpClient httpClient,
            ObjectMapper objectMapper,
            NotificationMailProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    MailCredential leaseRequired() {
        List<MailCredential> candidates = leaseCandidates();
        if (candidates.isEmpty()) {
            throw new MailCredentialLeaseException("MAIL credential is not available");
        }
        int index = (int) Math.floorMod(selectionCursor.getAndIncrement(), candidates.size());
        return candidates.get(index);
    }

    List<MailCredential> leaseCandidates() {
        try {
            OutboundHttpRequest request = OutboundHttpRequest.get(
                    credentialLeaseUri(),
                    OutboundHttpPolicy.internalService(properties.getCredentialService().getRequestTimeout()));
            request = InternalServiceAuthHeaders.apply(
                    request,
                    properties.getCredentialService().getServiceId(),
                    properties.getCredentialService().getToken(),
                    properties.getCredentialService().getScopes());

            OutboundHttpResponse response = httpClient.execute(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OutboundHttpException("Credential-service returned status " + response.statusCode());
            }
            ProviderCredentialLease[] leases = objectMapper.readValue(response.body(), ProviderCredentialLease[].class);
            if (leases == null) {
                throw new MailCredentialLeaseException("MAIL credential is not available");
            }
            return Arrays.stream(leases)
                    .map(this::mapLease)
                    .filter(this::hasRequiredSecrets)
                    .toList();
        } catch (MailCredentialLeaseException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new MailCredentialLeaseException(
                    "Unable to lease MAIL credentials from credential-service",
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MailCredentialLeaseException(
                    "Unable to lease MAIL credentials from credential-service",
                    ex);
        }
    }

    private URI credentialLeaseUri() {
        return UriComponentsBuilder.fromUriString(properties.getCredentialService().getBaseUrl())
                .path("/internal/credentials/providers/{provider}/leases")
                .queryParam("requiredPayloadKey", USERNAME_KEY)
                .queryParam("limit", properties.getCredentialService().getLeaseLimit())
                .build(PROVIDER);
    }

    private MailCredential mapLease(ProviderCredentialLease lease) {
        Map<String, String> secretPayload = lease.getSecretPayload();
        return new MailCredential(
                lease.getId(),
                secretPayload == null ? null : secretPayload.get(USERNAME_KEY),
                secretPayload == null ? null : secretPayload.get(PASSWORD_KEY),
                text(secretPayload, SMTP_HOST_KEY),
                integer(secretPayload, SMTP_PORT_KEY),
                text(secretPayload, SMTP_PROTOCOL_KEY),
                bool(secretPayload, SMTP_AUTH_KEY),
                bool(secretPayload, SMTP_STARTTLS_KEY),
                bool(secretPayload, SMTP_SSL_KEY),
                integer(secretPayload, SMTP_TIMEOUT_KEY),
                lease.getRateLimit(),
                lease.getRateLimitUnit(),
                lease.getDayLimit(),
                lease.getMonthLimit());
    }

    private boolean hasRequiredSecrets(MailCredential credential) {
        return StringUtils.hasText(credential.username()) && StringUtils.hasText(credential.password());
    }

    private String text(Map<String, String> payload, String key) {
        if (payload == null) {
            return null;
        }
        String value = payload.get(key);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Integer integer(Map<String, String> payload, String key) {
        String value = text(payload, key);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Boolean bool(Map<String, String> payload, String key) {
        String value = text(payload, key);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "yes", "y", "on", "enabled" -> true;
            case "false", "0", "no", "n", "off", "disabled" -> false;
            default -> null;
        };
    }
}
