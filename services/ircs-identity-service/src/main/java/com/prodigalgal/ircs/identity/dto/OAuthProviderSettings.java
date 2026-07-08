package com.prodigalgal.ircs.identity.dto;

import java.util.List;

public record OAuthProviderSettings(
        boolean enabled,
        List<Provider> providers) {

    public OAuthProviderSettings {
        providers = providers == null ? List.of() : List.copyOf(providers);
    }

    public record Provider(
            String code,
            String label,
            String loginUrl) {
    }
}
