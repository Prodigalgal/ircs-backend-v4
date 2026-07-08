package com.prodigalgal.ircs.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class RabbitTaskFailureClassifierTest {

    private final RabbitTaskFailureClassifier classifier = new RabbitTaskFailureClassifier();

    @Test
    void illegalArgumentIsFatal() {
        assertThat(classifier.classify(new IllegalArgumentException("bad mapping config")))
                .isEqualTo(RabbitTaskFailureDisposition.DLQ);
    }

    @Test
    void nestedIoFailureIsRetryable() {
        RuntimeException error = new RuntimeException("source fetch failed", new IOException("connection reset"));

        assertThat(classifier.classify(error)).isEqualTo(RabbitTaskFailureDisposition.RETRY);
    }

    @Test
    void unknownRuntimeFailureDefaultsRetryable() {
        assertThat(classifier.classify(new IllegalStateException("redis unavailable")))
                .isEqualTo(RabbitTaskFailureDisposition.RETRY);
    }

    @Test
    void httpRateLimitAndServerErrorsAreRetryable() {
        assertThat(classifier.classify(new RabbitTaskHttpStatusException(429, "Fake", "https://source.example/list")))
                .isEqualTo(RabbitTaskFailureDisposition.RETRY);
        assertThat(classifier.classify(new IllegalStateException("provider fetch failed: upstream status 503")))
                .isEqualTo(RabbitTaskFailureDisposition.RETRY);
    }

    @Test
    void deterministicHttpClientErrorsAreFatal() {
        assertThat(classifier.classify(new RabbitTaskHttpStatusException(404, "Fake", "https://source.example/list")))
                .isEqualTo(RabbitTaskFailureDisposition.DLQ);
        assertThat(classifier.classify(new IllegalStateException(
                "wrapped",
                new IllegalArgumentException("HTTP status 410 from data source Fake"))))
                .isEqualTo(RabbitTaskFailureDisposition.DLQ);
    }

    @Test
    void sourceTerminalFailureIsFatal() {
        assertThat(classifier.classify(new TaskSourceTerminalException(
                "Fake",
                "invalid JSON list response",
                new RuntimeException("html"))))
                .isEqualTo(RabbitTaskFailureDisposition.DLQ);
    }
}
