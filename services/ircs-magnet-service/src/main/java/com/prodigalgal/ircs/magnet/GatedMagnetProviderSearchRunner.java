package com.prodigalgal.ircs.magnet;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
class GatedMagnetProviderSearchRunner implements MagnetProviderSearchRunner {

    private static final String YTS_BZ = "YTS_BZ";

    private final MagnetProviderSearchRunner fixtureRunner;
    private final MagnetProviderSearchRunner ytsBzRunner;
    private final MagnetProviderSearchRunner genericRunner;
    private final RuntimeConfigService runtimeConfig;
    private final boolean realProviderEnabledByDeployment;
    private final String realProviderAllowlistByDeployment;
    GatedMagnetProviderSearchRunner(
            FixtureMagnetProviderSearchRunner fixtureRunner,
            YtsBzMagnetProviderSearchRunner ytsBzRunner,
            GenericMagnetProviderSearchRunner genericRunner,
            ObjectProvider<RuntimeConfigService> runtimeConfigProvider,
            @Value("${app.magnet.real-provider.enabled:false}") boolean realProviderEnabled,
            @Value("${app.magnet.real-provider.allowlist:YTS_BZ,THE_PIRATE_BAY,EZTV,EXT_TO,THE_PIRATE_BAY_FRONTEND}")
                    String realProviderAllowlist) {
        this.fixtureRunner = fixtureRunner;
        this.ytsBzRunner = ytsBzRunner;
        this.genericRunner = genericRunner;
        this.runtimeConfig = runtimeConfigProvider == null ? null : runtimeConfigProvider.getIfAvailable();
        this.realProviderEnabledByDeployment = realProviderEnabled;
        this.realProviderAllowlistByDeployment = realProviderAllowlist;
    }

    @Override
    public MagnetProviderSearchResult search(
            MagnetProviderSummary provider,
            MagnetExternalIdQuery query,
            UUID unifiedVideoId) {
        if (shouldUseYtsBz(provider)) {
            return ytsBzRunner.search(provider, query, unifiedVideoId);
        }
        if (shouldUseGeneric(provider)) {
            return genericRunner.search(provider, query, unifiedVideoId);
        }
        return fixtureRunner.search(provider, query, unifiedVideoId);
    }

    private boolean shouldUseYtsBz(MagnetProviderSummary provider) {
        if (!realProviderEnabled() || provider == null || !isYtsBz(provider)) {
            return false;
        }
        Set<String> allowlist = realProviderAllowlist();
        return allowlist.contains(normalize(provider.providerType()))
                || allowlist.contains(normalize(provider.code()));
    }

    private boolean isYtsBz(MagnetProviderSummary provider) {
        return YTS_BZ.equals(normalize(provider.providerType()))
                || YTS_BZ.equals(normalize(provider.code()));
    }

    private boolean shouldUseGeneric(MagnetProviderSummary provider) {
        if (!realProviderEnabled() || provider == null || isYtsBz(provider)) {
            return false;
        }
        Set<String> allowlist = realProviderAllowlist();
        return allowlist.contains(normalize(provider.providerType()))
                || allowlist.contains(normalize(provider.code()))
                || allowlist.contains("GENERIC_HTML");
    }

    private boolean realProviderEnabled() {
        return runtimeConfig == null
                ? realProviderEnabledByDeployment
                : runtimeConfig.booleanValue("app.magnet.real-provider.enabled", realProviderEnabledByDeployment);
    }

    private Set<String> realProviderAllowlist() {
        String value = runtimeConfig == null
                ? realProviderAllowlistByDeployment
                : runtimeConfig.stringValue("app.magnet.real-provider.allowlist", realProviderAllowlistByDeployment);
        return parseAllowlist(value);
    }

    private Set<String> parseAllowlist(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
    }
}
