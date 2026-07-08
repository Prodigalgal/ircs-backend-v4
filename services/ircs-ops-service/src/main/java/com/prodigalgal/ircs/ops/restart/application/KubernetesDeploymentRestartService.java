package com.prodigalgal.ircs.ops.restart.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.ops.restart.dto.ServiceRestartCapabilitiesResponse;
import com.prodigalgal.ircs.ops.restart.dto.ServiceRestartResponse;
import com.prodigalgal.ircs.ops.restart.dto.ServiceRestartResult;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
public class KubernetesDeploymentRestartService {

    private static final Path SERVICE_ACCOUNT_TOKEN =
            Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
    private static final Path SERVICE_ACCOUNT_NAMESPACE =
            Path.of("/var/run/secrets/kubernetes.io/serviceaccount/namespace");
    private static final Path SERVICE_ACCOUNT_CA =
            Path.of("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt");
    private static final String DEFAULT_KUBERNETES_HOST = "https://kubernetes.default.svc";
    private static final Set<String> DEFAULT_ALLOWED_SERVICES = Set.of(
            "ircs-api-gateway",
            "ircs-aggregation-worker",
            "ircs-catalog-service",
            "ircs-config-service",
            "ircs-content-service",
            "ircs-credential-service",
            "ircs-identity-service",
            "ircs-ingestion-worker",
            "ircs-interaction-service",
            "ircs-magnet-service",
            "ircs-metadata-worker",
            "ircs-normalization-worker",
            "ircs-notification-worker",
            "ircs-ops-service",
            "ircs-portal-service",
            "ircs-scraper-service",
            "ircs-search-service",
            "ircs-storage-service",
            "ircs-task-service");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final RuntimeConfigService runtimeConfig;
    private final boolean enabledByDeployment;
    private final String apiBaseUrlByDeployment;
    private final String namespaceByDeployment;
    private final Set<String> allowedServicesByDeployment;
    private final Duration requestTimeoutByDeployment;

    KubernetesDeploymentRestartService(
            ObjectMapper objectMapper,
            RuntimeConfigService runtimeConfig,
            @Value("${app.ops.service-restart.enabled:false}") boolean enabled,
            @Value("${app.ops.service-restart.kubernetes-api-base-url:}") String apiBaseUrl,
            @Value("${app.ops.service-restart.namespace:}") String namespace,
            @Value("${app.ops.service-restart.allowed-services:}") String allowedServices,
            @Value("${app.ops.service-restart.request-timeout:PT10S}") String requestTimeout) {
        this.objectMapper = objectMapper;
        this.httpClient = createHttpClient();
        this.runtimeConfig = runtimeConfig;
        this.enabledByDeployment = enabled;
        this.apiBaseUrlByDeployment = StringUtils.hasText(apiBaseUrl) ? apiBaseUrl.trim() : DEFAULT_KUBERNETES_HOST;
        this.namespaceByDeployment = StringUtils.hasText(namespace) ? namespace.trim() : readNamespace();
        this.allowedServicesByDeployment = parseAllowedServices(allowedServices);
        this.requestTimeoutByDeployment = parseDuration(requestTimeout, Duration.ofSeconds(10));
    }

    public ServiceRestartCapabilitiesResponse capabilities() {
        boolean restartEnabled = enabled();
        String token = readToken();
        String reason = restartEnabled
                ? (StringUtils.hasText(token) ? "" : "Kubernetes service account token is unavailable")
                : "Service restart is disabled";
        return new ServiceRestartCapabilitiesResponse(
                restartEnabled && StringUtils.hasText(token),
                namespace(),
                allowedServices().stream().sorted().toList(),
                reason);
    }

    public ServiceRestartResponse restart(List<String> requestedServices, String reason) {
        Instant requestedAt = Instant.now();
        List<String> services = normalizeServices(requestedServices);
        if (services.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No valid service selected");
        }
        if (!enabled()) {
            List<ServiceRestartResult> results = services.stream()
                    .map(service -> ServiceRestartResult.rejected(service, "Service restart is disabled"))
                    .toList();
            return new ServiceRestartResponse(requestedAt, namespace(), results);
        }

        String token = readToken();
        if (!StringUtils.hasText(token)) {
            List<ServiceRestartResult> results = services.stream()
                    .map(service -> ServiceRestartResult.rejected(service, "Kubernetes service account token is unavailable"))
                    .toList();
            return new ServiceRestartResponse(requestedAt, namespace(), results);
        }

        List<ServiceRestartResult> results = services.stream()
                .map(service -> restartOne(service, requestedAt, reason, token))
                .toList();
        return new ServiceRestartResponse(requestedAt, namespace(), results);
    }

    private ServiceRestartResult restartOne(String service, Instant requestedAt, String reason, String token) {
        if (!allowedServices().contains(service)) {
            return ServiceRestartResult.rejected(service, "Service is not allowed for restart");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(restartUri(service))
                    .timeout(requestTimeout())
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/strategic-merge-patch+json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(patchPayload(requestedAt, reason)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return ServiceRestartResult.accepted(service);
            }
            String message = "Kubernetes API returned " + response.statusCode();
            log.warn("Kubernetes deployment restart failed: service={}, status={}, body={}",
                    service,
                    response.statusCode(),
                    response.body());
            return ServiceRestartResult.rejected(service, message);
        } catch (IOException ex) {
            log.warn("Kubernetes deployment restart I/O failed: service={}, error={}", service, ex.getMessage());
            return ServiceRestartResult.rejected(service, "Kubernetes API I/O failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ServiceRestartResult.rejected(service, "Kubernetes API request interrupted");
        }
    }

    private URI restartUri(String service) {
        return URI.create(apiBaseUrl()
                + "/apis/apps/v1/namespaces/"
                + url(namespace())
                + "/deployments/"
                + url(service));
    }

    private boolean enabled() {
        return runtimeConfig.booleanValue("app.ops.service-restart.enabled", enabledByDeployment);
    }

    private String apiBaseUrl() {
        return runtimeConfig.stringValue(
                "app.ops.service-restart.kubernetes-api-base-url",
                apiBaseUrlByDeployment);
    }

    private String namespace() {
        return runtimeConfig.stringValue("app.ops.service-restart.namespace", namespaceByDeployment);
    }

    private Set<String> allowedServices() {
        String raw = runtimeConfig.stringValue("app.ops.service-restart.allowed-services", "");
        return StringUtils.hasText(raw) ? parseAllowedServices(raw) : allowedServicesByDeployment;
    }

    private Duration requestTimeout() {
        Duration value = runtimeConfig.durationValue("app.ops.service-restart.request-timeout", requestTimeoutByDeployment);
        return value == null || !value.isPositive() ? requestTimeoutByDeployment : value;
    }

    private String patchPayload(Instant requestedAt, String reason) throws JsonProcessingException {
        Map<String, Object> annotations = new java.util.LinkedHashMap<>();
        annotations.put("kubectl.kubernetes.io/restartedAt", requestedAt.toString());
        annotations.put("ircs.prodigalgal.com/restarted-by", "ircs-ops-service");
        if (StringUtils.hasText(reason)) {
            annotations.put("ircs.prodigalgal.com/restart-reason", reason.trim());
        }
        return objectMapper.writeValueAsString(Map.of(
                "spec",
                Map.of(
                        "template",
                        Map.of(
                                "metadata",
                                Map.of("annotations", annotations)))));
    }

    private List<String> normalizeServices(List<String> services) {
        if (services == null) {
            return List.of();
        }
        return services.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private Set<String> parseAllowedServices(String raw) {
        if (!StringUtils.hasText(raw)) {
            return DEFAULT_ALLOWED_SERVICES;
        }
        Set<String> values = new LinkedHashSet<>();
        for (String value : raw.split(",")) {
            if (StringUtils.hasText(value)) {
                values.add(value.trim());
            }
        }
        return values.isEmpty() ? DEFAULT_ALLOWED_SERVICES : Set.copyOf(values);
    }

    private static String readToken() {
        return readFile(SERVICE_ACCOUNT_TOKEN);
    }

    private static String readNamespace() {
        String namespace = readFile(SERVICE_ACCOUNT_NAMESPACE);
        return StringUtils.hasText(namespace) ? namespace : "ircs-dev";
    }

    private static String readFile(Path path) {
        try {
            if (Files.exists(path)) {
                return Files.readString(path, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException ignored) {
            return "";
        }
        return "";
    }

    private static Duration parseDuration(String raw, Duration fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        try {
            return DurationStyle.detectAndParse(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static HttpClient createHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3));
        createServiceAccountSslContext().ifPresent(builder::sslContext);
        return builder.build();
    }

    private static java.util.Optional<SSLContext> createServiceAccountSslContext() {
        if (!Files.exists(SERVICE_ACCOUNT_CA)) {
            return java.util.Optional.empty();
        }
        try (java.io.InputStream input = Files.newInputStream(SERVICE_ACCOUNT_CA)) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate certificate = (X509Certificate) factory.generateCertificate(input);
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry("kubernetes", certificate);
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
            return java.util.Optional.of(sslContext);
        } catch (Exception ex) {
            log.warn("Unable to initialize Kubernetes service account trust store: {}", ex.getMessage());
            return java.util.Optional.empty();
        }
    }
}
