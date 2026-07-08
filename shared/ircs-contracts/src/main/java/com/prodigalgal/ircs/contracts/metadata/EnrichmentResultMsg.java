package com.prodigalgal.ircs.contracts.metadata;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnrichmentResultMsg implements Serializable {
    private UUID videoId;
    private String pipelineVersion;
    private ProviderType providerType;
    private boolean success;
    private boolean retryable;
    private String errorCode;
    private EnrichedMetadataDTO metadata;
    private String errorMessage;

    public static EnrichmentResultMsg success(
            UUID videoId,
            String pipelineVersion,
            ProviderType providerType,
            EnrichedMetadataDTO metadata) {
        return EnrichmentResultMsg.builder()
                .videoId(videoId)
                .pipelineVersion(pipelineVersion)
                .providerType(providerType)
                .success(true)
                .retryable(false)
                .metadata(metadata)
                .build();
    }

    public static EnrichmentResultMsg failure(
            UUID videoId,
            String pipelineVersion,
            ProviderType providerType,
            String errorMessage,
            boolean retryable,
            String errorCode) {
        return EnrichmentResultMsg.builder()
                .videoId(videoId)
                .pipelineVersion(pipelineVersion)
                .providerType(providerType)
                .success(false)
                .retryable(retryable)
                .errorMessage(errorMessage)
                .errorCode(errorCode)
                .build();
    }
}
