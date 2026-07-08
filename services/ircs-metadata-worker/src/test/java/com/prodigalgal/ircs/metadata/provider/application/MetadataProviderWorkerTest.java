package com.prodigalgal.ircs.metadata.provider.application;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.contracts.metadata.EnrichedMetadataDTO;
import com.prodigalgal.ircs.contracts.metadata.MetadataSearchContext;
import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import com.prodigalgal.ircs.metadata.config.MetadataConfigValues;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProvider;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderRetryableException;
import com.prodigalgal.ircs.metadata.provider.messaging.MetadataProviderResultPublisher;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

@ExtendWith(MockitoExtension.class)
class MetadataProviderWorkerTest {

    @Mock
    private MetadataProvider provider;

    @Mock
    private MetadataProviderResultPublisher resultPublisher;

    @Mock
    private MetadataConfigValues configValues;

    @Mock
    private WorkerJobAuditWriter auditWriter;

    private MetadataProviderWorker worker;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(configValues.isProviderEnabled(ProviderType.TMDB)).thenReturn(true);
        worker = new MetadataProviderWorker(
                List.of(provider),
                new MetadataSearchContextMessageParser(new ObjectMapper()),
                resultPublisher,
                configValues,
                auditWriter);
    }

    @Test
    void publishesSuccessWhenProviderFindsMetadata() {
        MetadataSearchContext context = context();
        EnrichedMetadataDTO metadata = new EnrichedMetadataDTO();
        metadata.setTmdbId("603");
        when(provider.getType()).thenReturn(ProviderType.TMDB);
        when(provider.supports(context)).thenReturn(true);
        when(provider.enrich(context)).thenReturn(Optional.of(metadata));

        worker.execute(context, ProviderType.TMDB);

        verify(resultPublisher).publishSuccess(context, ProviderType.TMDB, metadata);
        verify(resultPublisher, never()).publishFailure(
                org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.anyBoolean(),
                org.mockito.Mockito.any());
        WorkerJobAuditEvent event = captureAuditEvent();
        org.assertj.core.api.Assertions.assertThat(event.jobType()).isEqualTo("queue-consumer");
        org.assertj.core.api.Assertions.assertThat(event.jobName()).isEqualTo("metadata.provider.tmdb");
        org.assertj.core.api.Assertions.assertThat(event.correlationId()).isEqualTo(context.getVideoId().toString());
        org.assertj.core.api.Assertions.assertThat(event.status()).isEqualTo("succeeded");
    }

    @Test
    void publishesRetryableFailureWhenProviderPoolIsExhausted() {
        MetadataSearchContext context = context();
        when(provider.getType()).thenReturn(ProviderType.TMDB);
        when(provider.supports(context)).thenReturn(true);
        when(provider.enrich(context)).thenThrow(new MetadataProviderRetryableException("POOL_EXHAUSTED", "no keys"));

        worker.execute(context, ProviderType.TMDB);

        verify(resultPublisher).publishFailure(context, ProviderType.TMDB, "no keys", true, "POOL_EXHAUSTED");
        WorkerJobAuditEvent event = captureAuditEvent();
        org.assertj.core.api.Assertions.assertThat(event.status()).isEqualTo("failed");
        org.assertj.core.api.Assertions.assertThat(event.error().getMessage()).isEqualTo("POOL_EXHAUSTED");
    }

    @Test
    void publishesTerminalFailureWhenContextIsUnsupported() {
        MetadataSearchContext context = context();
        when(provider.getType()).thenReturn(ProviderType.TMDB);
        when(provider.supports(context)).thenReturn(false);

        worker.execute(context, ProviderType.TMDB);

        verify(resultPublisher).publishFailure(
                context,
                ProviderType.TMDB,
                "Provider does not support context",
                false,
                "UNSUPPORTED_CONTEXT");
        verify(provider, never()).enrich(context);
        WorkerJobAuditEvent event = captureAuditEvent();
        org.assertj.core.api.Assertions.assertThat(event.status()).isEqualTo("skipped");
        org.assertj.core.api.Assertions.assertThat(event.error().getMessage()).isEqualTo("UNSUPPORTED_CONTEXT");
    }

    @Test
    void publishesTerminalFailureWhenProviderIsDisabled() {
        MetadataSearchContext context = context();
        when(configValues.isProviderEnabled(ProviderType.TMDB)).thenReturn(false);

        worker.execute(context, ProviderType.TMDB);

        verify(resultPublisher).publishFailure(
                context,
                ProviderType.TMDB,
                "Provider disabled by configuration",
                false,
                "PROVIDER_DISABLED");
        verify(provider, never()).supports(org.mockito.Mockito.any());
        verify(provider, never()).enrich(org.mockito.Mockito.any());
        WorkerJobAuditEvent event = captureAuditEvent();
        org.assertj.core.api.Assertions.assertThat(event.status()).isEqualTo("skipped");
        org.assertj.core.api.Assertions.assertThat(event.error().getMessage()).isEqualTo("PROVIDER_DISABLED");
    }

    @Test
    void doubanListenerPublishesTerminalFailureWhenProviderIsMissing() throws Exception {
        MetadataSearchContext context = context();
        when(configValues.isProviderEnabled(ProviderType.DOUBAN)).thenReturn(true);
        when(provider.getType()).thenReturn(ProviderType.TMDB);

        worker.onDoubanTask(message(context));

        verify(resultPublisher).publishFailure(
                org.mockito.Mockito.argThat(argument -> argument.getVideoId().equals(context.getVideoId())),
                org.mockito.Mockito.eq(ProviderType.DOUBAN),
                org.mockito.Mockito.eq("Provider implementation not found"),
                org.mockito.Mockito.eq(false),
                org.mockito.Mockito.eq("PROVIDER_NOT_FOUND"));
        verify(provider, never()).supports(org.mockito.Mockito.any());
        WorkerJobAuditEvent event = captureAuditEvent();
        org.assertj.core.api.Assertions.assertThat(event.jobName()).isEqualTo("metadata.provider.douban");
        org.assertj.core.api.Assertions.assertThat(event.status()).isEqualTo("skipped");
    }

    @Test
    void rottenTomatoesListenerPublishesTerminalFailureWhenProviderIsMissing() throws Exception {
        MetadataSearchContext context = context();
        when(configValues.isProviderEnabled(ProviderType.ROTTEN_TOMATOES)).thenReturn(true);
        when(provider.getType()).thenReturn(ProviderType.TMDB);

        worker.onRottenTomatoesTask(message(context));

        verify(resultPublisher).publishFailure(
                org.mockito.Mockito.argThat(argument -> argument.getVideoId().equals(context.getVideoId())),
                org.mockito.Mockito.eq(ProviderType.ROTTEN_TOMATOES),
                org.mockito.Mockito.eq("Provider implementation not found"),
                org.mockito.Mockito.eq(false),
                org.mockito.Mockito.eq("PROVIDER_NOT_FOUND"));
        verify(provider, never()).supports(org.mockito.Mockito.any());
        WorkerJobAuditEvent event = captureAuditEvent();
        org.assertj.core.api.Assertions.assertThat(event.jobName()).isEqualTo("metadata.provider.rotten-tomatoes");
        org.assertj.core.api.Assertions.assertThat(event.status()).isEqualTo("skipped");
    }

    private MetadataSearchContext context() {
        return MetadataSearchContext.builder()
                .videoId(UUID.randomUUID())
                .title("The Matrix")
                .categorySlug("movie")
                .year("1999")
                .build();
    }

    private Message message(MetadataSearchContext context) throws Exception {
        String json = """
                {"videoId":"%s","title":"%s","categorySlug":"%s","year":"%s"}
                """.formatted(context.getVideoId(), context.getTitle(), context.getCategorySlug(), context.getYear());
        return new Message(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), new MessageProperties());
    }

    private WorkerJobAuditEvent captureAuditEvent() {
        ArgumentCaptor<WorkerJobAuditEvent> captor = ArgumentCaptor.forClass(WorkerJobAuditEvent.class);
        verify(auditWriter).record(captor.capture());
        return captor.getValue();
    }
}
