package com.prodigalgal.ircs.ops.infrastructure.rabbit;

public record RabbitManagementMessageSample(
        String messageId,
        String correlationId,
        Integer retryCount,
        String disposition,
        String errorClass,
        String errorMessage,
        int bodyBytes,
        String bodyPreview
) {
}
