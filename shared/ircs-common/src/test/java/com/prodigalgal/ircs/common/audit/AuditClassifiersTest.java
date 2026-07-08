package com.prodigalgal.ircs.common.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuditClassifiersTest {

    @Test
    void classifiesSystemRoutes() {
        assertThat(AuditClassifiers.request("/internal/health")).isEqualTo(AuditClass.SYSTEM);
        assertThat(AuditClassifiers.request("/api/v1/ops/request-audit")).isEqualTo(AuditClass.SYSTEM);
    }

    @Test
    void classifiesSecurityRoutes() {
        assertThat(AuditClassifiers.request("/api/v1/admin/auth/login")).isEqualTo(AuditClass.SECURITY);
        assertThat(AuditClassifiers.request("/api/portal/profile")).isEqualTo(AuditClass.SECURITY);
    }

    @Test
    void classifiesBehaviorRoutes() {
        assertThat(AuditClassifiers.request("/api/portal/search/suggest")).isEqualTo(AuditClass.BEHAVIOR);
        assertThat(AuditClassifiers.request("/api/v1/content/items")).isEqualTo(AuditClass.BEHAVIOR);
    }
}
