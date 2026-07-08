package com.prodigalgal.ircs.metadata.provider.messaging;

import com.prodigalgal.ircs.contracts.metadata.EnrichedMetadataDTO;
import com.prodigalgal.ircs.contracts.metadata.EnrichmentResultMsg;
import com.prodigalgal.ircs.contracts.metadata.MetadataSearchContext;
import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import com.prodigalgal.ircs.metadata.result.application.MetadataResultCollectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MetadataProviderResultPublisher {

    private final MetadataResultCollectorService collectorService;

    public void publishSuccess(MetadataSearchContext context, ProviderType providerType, EnrichedMetadataDTO metadata) {
        publish(EnrichmentResultMsg.success(
                context.getVideoId(),
                context.getPipelineVersion(),
                providerType,
                metadata));
    }

    public void publishFailure(
            MetadataSearchContext context,
            ProviderType providerType,
            String reason,
            boolean retryable,
            String errorCode) {
        publish(EnrichmentResultMsg.failure(
                context.getVideoId(),
                context.getPipelineVersion(),
                providerType,
                reason,
                retryable,
                errorCode));
    }

    private void publish(EnrichmentResultMsg message) {
        collectorService.process(message);
    }
}
