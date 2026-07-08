package com.prodigalgal.ircs.common.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestAuditSanitizerTest {

    @Test
    void redactsSensitiveQueryParametersAndKeepsSafeParameters() {
        String sanitized = RequestAuditSanitizer.sanitizeQueryString(
                "keyword=codex&token=secret-token&password=secret-password&api_key=secret-api-key"
                        + "&redirect=%2Fadmin&clientSecret=secret-client&Authorization=Bearer+secret");

        assertThat(sanitized)
                .isEqualTo("keyword=codex&token=***&password=***&api_key=***"
                        + "&redirect=%2Fadmin&clientSecret=***&Authorization=***");
        assertThat(sanitized)
                .doesNotContain("secret-token")
                .doesNotContain("secret-password")
                .doesNotContain("secret-api-key")
                .doesNotContain("secret-client")
                .doesNotContain("Bearer+secret");
    }

    @Test
    void keepsNullAndBlankQueryStringAsIs() {
        assertThat(RequestAuditSanitizer.sanitizeQueryString(null)).isNull();
        assertThat(RequestAuditSanitizer.sanitizeQueryString("")).isEmpty();
        assertThat(RequestAuditSanitizer.sanitizeQueryString("   ")).isEqualTo("   ");
    }
}
