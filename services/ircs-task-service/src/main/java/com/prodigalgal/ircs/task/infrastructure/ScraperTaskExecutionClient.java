package com.prodigalgal.ircs.task.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundCircuitOpenException;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.task.domain.TaskExecutionPlan;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ScraperTaskExecutionClient {

    private static final TypeReference<List<Map<String, Object>>> DIRECT_ITEMS_TYPE = new TypeReference<>() {
    };
    private static final String EXECUTION_PATH = "/internal/v1/scraper/task-executions";

    private final ObjectMapper objectMapper;
    private final OutboundHttpClient httpClient;
    private final String scraperBaseUrl;
    private final Duration requestTimeout;

    public ScraperTaskExecutionClient(
            ObjectMapper objectMapper,
            OutboundHttpClient httpClient,
            @Value("${app.task.runner.scraper-base-url:http://ircs-scraper-service.ircs-dev.svc.cluster.local:8080}")
                    String scraperBaseUrl,
            @Value("${app.task.runner.scraper-request-timeout:10s}") Duration requestTimeout) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.scraperBaseUrl = scraperBaseUrl;
        this.requestTimeout = requestTimeout;
    }

    public ScraperTaskExecutionResult execute(TaskExecutionPlan plan, boolean resume) {
        try {
            OutboundHttpPolicy policy = OutboundHttpPolicy.internalService(requestTimeout)
                    .withCallerCircuitBreakerKey("task-scraper-execution");
            byte[] body = objectMapper.writeValueAsBytes(toPayload(plan, resume));
            OutboundHttpRequest request = OutboundHttpRequest.post(executionUri(), policy)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Accept", "application/json")
                    .withBody(body);
            OutboundHttpResponse response = httpClient.execute(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("scraper-service task execution failed: upstream status "
                        + response.statusCode());
            }
            return objectMapper.readValue(response.body(), ScraperTaskExecutionResult.class);
        } catch (OutboundCircuitOpenException ex) {
            throw new IllegalStateException("scraper-service task execution failed: outbound circuit open", ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("scraper-service task execution failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("scraper-service task execution failed: interrupted", ex);
        }
    }

    private URI executionUri() {
        String normalizedBase = scraperBaseUrl.endsWith("/")
                ? scraperBaseUrl.substring(0, scraperBaseUrl.length() - 1)
                : scraperBaseUrl;
        return URI.create(normalizedBase + EXECUTION_PATH);
    }

    private ScraperTaskExecutionPayload toPayload(TaskExecutionPlan plan, boolean resume) {
        RunnerHeaderOptions options = parseRunnerHeaderOptions(plan.headers());
        return new ScraperTaskExecutionPayload(
                plan.id(),
                plan.dataSourceId(),
                plan.filterKeywords(),
                plan.filterType(),
                plan.filterHours(),
                plan.effectiveStartPage(resume),
                plan.effectiveEndPage(resume),
                plan.userAgent(),
                Boolean.TRUE.equals(plan.enableRandomUa()),
                Boolean.TRUE.equals(plan.useCustomProxy()),
                plan.proxyType(),
                plan.proxyHost(),
                plan.proxyPort(),
                plan.proxyUsername(),
                plan.proxyPassword(),
                options.httpHeaders(),
                plan.fixedDelayMs(),
                false,
                options.directItems());
    }

    private RunnerHeaderOptions parseRunnerHeaderOptions(String headers) {
        if (headers == null || headers.isBlank()) {
            return new RunnerHeaderOptions(null, List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(headers);
            JsonNode runner = root.path("ircsTaskRunner");
            if (!runner.isObject()) {
                return new RunnerHeaderOptions(headers, List.of());
            }
            List<Map<String, Object>> directItems = runner.path("directItems").isArray()
                    ? objectMapper.convertValue(runner.path("directItems"), DIRECT_ITEMS_TYPE)
                    : List.of();
            JsonNode httpHeaders = root.path("httpHeaders");
            String forwardedHeaders = httpHeaders.isObject() ? objectMapper.writeValueAsString(httpHeaders) : "{}";
            return new RunnerHeaderOptions(forwardedHeaders, directItems);
        } catch (Exception ex) {
            return new RunnerHeaderOptions(headers, List.of());
        }
    }
}

record ScraperTaskExecutionPayload(
        UUID taskId,
        UUID dataSourceId,
        String keyword,
        String filterType,
        Integer filterHours,
        Integer startPage,
        Integer endPage,
        String userAgent,
        boolean enableRandomUa,
        boolean useCustomProxy,
        String proxyType,
        String proxyHost,
        Integer proxyPort,
        String proxyUsername,
        String proxyPassword,
        String headers,
        Integer fixedDelayMs,
        boolean forceIngest,
        List<Map<String, Object>> directItems) {
}

record RunnerHeaderOptions(
        String httpHeaders,
        List<Map<String, Object>> directItems) {
}
