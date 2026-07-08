package com.prodigalgal.ircs.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class InternalServiceAccessPolicyTest {

    @Test
    void acceptsMatchingServiceIdentityAndScope() {
        InternalServiceAccessPolicy.require(
                "credential",
                "token",
                "credential:lease",
                "metadata-worker",
                "token",
                "credential:lease search:sync");
    }

    @Test
    void rejectsMissingScope() {
        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> InternalServiceAccessPolicy.require(
                        "credential",
                        "token",
                        "credential:lease",
                        "metadata-worker",
                        "token",
                        "search:sync"));

        assertThat(ex.getMessage()).contains("Internal service scope is missing");
    }

    @Test
    void rejectsMissingConfiguredToken() {
        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> InternalServiceAccessPolicy.require(
                        "credential",
                        "",
                        "credential:lease",
                        "metadata-worker",
                        "token",
                        "credential:lease"));

        assertThat(ex.getMessage()).contains("internal service token is not configured");
    }
}
