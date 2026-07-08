package com.prodigalgal.ircs.metadata.provider.application;

import com.prodigalgal.ircs.contracts.metadata.EnrichedMetadataDTO;
import com.prodigalgal.ircs.contracts.metadata.MetadataSearchContext;
import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.metadata.config.MetadataConfigValues;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProvider;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderRetryableException;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderTerminalException;
import com.prodigalgal.ircs.metadata.provider.messaging.MetadataProviderResultPublisher;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

@Component
@Slf4j
public class MetadataProviderWorker {

    private final List<MetadataProvider> providers;
    private final MetadataSearchContextMessageParser messageParser;
    private final MetadataProviderResultPublisher resultPublisher;
    private final MetadataConfigValues configValues;
    private final WorkerJobAuditWriter auditWriter;

    public MetadataProviderWorker(
            List<MetadataProvider> providers,
            MetadataSearchContextMessageParser messageParser,
            MetadataProviderResultPublisher resultPublisher,
            MetadataConfigValues configValues,
            WorkerJobAuditWriter auditWriter) {
        this.providers = List.copyOf(providers);
        this.messageParser = messageParser;
        this.resultPublisher = resultPublisher;
        this.configValues = configValues;
        this.auditWriter = auditWriter == null ? WorkerJobAuditWriter.noop() : auditWriter;
    }

    public void onTmdbTask(Message message) {
        executeMessage(message, ProviderType.TMDB);
    }

    public void onDoubanTask(Message message) {
        executeMessage(message, ProviderType.DOUBAN);
    }

    public void onRottenTomatoesTask(Message message) {
        executeMessage(message, ProviderType.ROTTEN_TOMATOES);
    }

    private void executeMessage(Message message, ProviderType providerType) {
        Instant startedAt = Instant.now();
        MetadataSearchContext context;
        try {
            context = messageParser.parse(message);
        } catch (RuntimeException ex) {
            recordFailed(startedAt, providerType, messageCorrelationId(message), "provider task parse failed", ex);
            throw ex;
        }
        execute(context, providerType, startedAt);
    }

    public void execute(MetadataSearchContext context, ProviderType providerType) {
        execute(context, providerType, Instant.now());
    }

    private void execute(MetadataSearchContext context, ProviderType providerType, Instant startedAt) {
        try {
            if (!isProviderEnabled(providerType)) {
                sendFinalFailure(context, providerType, "Provider disabled by configuration", "PROVIDER_DISABLED");
                recordSkipped(startedAt, providerType, correlationId(context), "PROVIDER_DISABLED");
                return;
            }

            Optional<MetadataProvider> providerOpt = providers.stream()
                    .filter(provider -> provider.getType() == providerType)
                    .findFirst();
            if (providerOpt.isEmpty()) {
                sendFinalFailure(context, providerType, "Provider implementation not found", "PROVIDER_NOT_FOUND");
                recordSkipped(startedAt, providerType, correlationId(context), "PROVIDER_NOT_FOUND");
                return;
            }

            MetadataProvider provider = providerOpt.get();
            if (!provider.supports(context)) {
                sendFinalFailure(context, providerType, "Provider does not support context", "UNSUPPORTED_CONTEXT");
                recordSkipped(startedAt, providerType, correlationId(context), "UNSUPPORTED_CONTEXT");
                return;
            }

            Optional<EnrichedMetadataDTO> result = provider.enrich(context);
            if (result.isPresent()) {
                resultPublisher.publishSuccess(context, providerType, result.get());
                recordSucceeded(startedAt, providerType, correlationId(context));
                return;
            }

            sendFinalFailure(context, providerType, "Not Found (Empty result)", "NOT_FOUND");
            recordSucceeded(startedAt, providerType, correlationId(context));
        } catch (MetadataProviderRetryableException ex) {
            log.info("Retryable metadata provider failure. provider={}, videoId={}, code={}",
                    providerType, context.getVideoId(), ex.getErrorCode());
            sendRetryableFailure(context, providerType, ex.getMessage(), ex.getErrorCode());
            recordFailed(startedAt, providerType, correlationId(context), ex.getErrorCode(), ex);
        } catch (MetadataProviderTerminalException ex) {
            log.warn("Terminal metadata provider failure. provider={}, videoId={}, code={}",
                    providerType, context.getVideoId(), ex.getErrorCode());
            sendFinalFailure(context, providerType, ex.getMessage(), ex.getErrorCode());
            recordFailed(startedAt, providerType, correlationId(context), ex.getErrorCode(), ex);
        } catch (Exception ex) {
            if (isRetryable(ex)) {
                sendRetryableFailure(context, providerType, "Retryable exception: " + rootCause(ex).getMessage(),
                        classifyRetryable(rootCause(ex)));
                recordFailed(startedAt, providerType, correlationId(context), classifyRetryable(rootCause(ex)), ex);
                return;
            }
            recordFailed(startedAt, providerType, correlationId(context), "UNEXPECTED", ex);
            throw asRuntimeException(ex);
        }
    }

    private boolean isProviderEnabled(ProviderType providerType) {
        return configValues.isProviderEnabled(providerType);
    }

    private void sendRetryableFailure(
            MetadataSearchContext context,
            ProviderType providerType,
            String reason,
            String errorCode) {
        resultPublisher.publishFailure(context, providerType, reason, true, errorCode);
    }

    private void sendFinalFailure(
            MetadataSearchContext context,
            ProviderType providerType,
            String reason,
            String errorCode) {
        resultPublisher.publishFailure(context, providerType, reason, false, errorCode);
    }

    private boolean isRetryable(Exception ex) {
        Throwable root = rootCause(ex);
        return root instanceof SocketTimeoutException
                || root instanceof ConnectException
                || root instanceof UnknownHostException
                || root instanceof IOException
                || ex instanceof ResourceAccessException
                || ex instanceof RestClientException;
    }

    private String classifyRetryable(Throwable throwable) {
        if (throwable instanceof SocketTimeoutException) {
            return "SOCKET_TIMEOUT";
        }
        if (throwable instanceof ConnectException) {
            return "CONNECT_EXCEPTION";
        }
        if (throwable instanceof UnknownHostException) {
            return "UNKNOWN_HOST";
        }
        if (throwable instanceof IOException) {
            return "IO_EXCEPTION";
        }
        return "RETRYABLE";
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private RuntimeException asRuntimeException(Exception ex) {
        if (ex instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException(ex.getMessage(), ex);
    }

    private void recordSucceeded(Instant startedAt, ProviderType providerType, String correlationId) {
        recordAudit(WorkerJobAuditEvent.succeeded(
                "queue-consumer",
                jobName(providerType),
                correlationId,
                elapsedSince(startedAt)));
    }

    private void recordSkipped(Instant startedAt, ProviderType providerType, String correlationId, String reason) {
        recordAudit(new WorkerJobAuditEvent(
                "queue-consumer",
                jobName(providerType),
                correlationId,
                "skipped",
                elapsedSince(startedAt),
                new MetadataProviderAuditException(reason)));
    }

    private void recordFailed(
            Instant startedAt,
            ProviderType providerType,
            String correlationId,
            String reason,
            Throwable error) {
        recordAudit(WorkerJobAuditEvent.failed(
                "queue-consumer",
                jobName(providerType),
                correlationId,
                elapsedSince(startedAt),
                new MetadataProviderAuditException(reason, error)));
    }

    private void recordAudit(WorkerJobAuditEvent event) {
        try {
            auditWriter.record(event);
        } catch (RuntimeException ex) {
            log.warn("Metadata provider worker audit write failed: {}", ex.getMessage());
        }
    }

    private static String jobName(ProviderType providerType) {
        String provider = providerType == null ? "unknown" : providerType.name().toLowerCase(java.util.Locale.ROOT);
        return "metadata.provider." + provider.replace('_', '-');
    }

    private static String correlationId(MetadataSearchContext context) {
        return context == null || context.getVideoId() == null ? null : context.getVideoId().toString();
    }

    private static String messageCorrelationId(Message message) {
        if (message == null || message.getMessageProperties() == null) {
            return null;
        }
        return message.getMessageProperties().getMessageId();
    }

    private static Duration elapsedSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }

    private static class MetadataProviderAuditException extends RuntimeException {
        MetadataProviderAuditException(String message) {
            super(message);
        }

        MetadataProviderAuditException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
