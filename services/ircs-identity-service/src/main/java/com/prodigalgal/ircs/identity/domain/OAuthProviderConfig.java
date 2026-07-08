package com.prodigalgal.ircs.identity.domain;

import java.net.URI;

public record OAuthProviderConfig(
        OAuthProviderRegistry.Definition definition,
        String clientId,
        String clientSecret,
        String scope,
        URI redirectUri,
        String frontendBaseUrl) {

    public String code() {
        return definition.code();
    }

    public String label() {
        return definition.label();
    }
}
