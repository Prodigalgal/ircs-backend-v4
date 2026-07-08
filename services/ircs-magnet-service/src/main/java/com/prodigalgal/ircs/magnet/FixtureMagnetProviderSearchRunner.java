package com.prodigalgal.ircs.magnet;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class FixtureMagnetProviderSearchRunner implements MagnetProviderSearchRunner {

    @Override
    public MagnetProviderSearchResult search(
            MagnetProviderSummary provider,
            MagnetExternalIdQuery query,
            UUID unifiedVideoId) {
        String infoHash = sha1Hex(provider.code() + ":" + unifiedVideoId + ":" + query.type() + ":" + query.value());
        String title = "IRCS dev-safe fixture " + query.value() + " " + provider.code();
        String encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8);
        MagnetProviderCandidate candidate = new MagnetProviderCandidate(
                infoHash,
                "magnet:?xt=urn:btih:" + infoHash + "&dn=" + encodedTitle,
                title,
                1_073_741_824L,
                "1.0 GB",
                Instant.parse("2026-06-07T00:00:00Z"),
                12,
                1,
                "WEB",
                "1080p",
                query.type(),
                query.value(),
                100,
                "fixture://magnet-provider/" + provider.code() + "/" + query.type() + "/" + query.value(),
                List.of("dev-safe", provider.code().toLowerCase()),
                Map.of(
                        "mode", "fixture",
                        "providerCode", provider.code(),
                        "externalIdType", query.type(),
                        "externalIdValue", query.value()));
        return new MagnetProviderSearchResult(
                "fixture://magnet-provider/" + provider.code() + "?externalId=" + query.value(),
                200,
                List.of(candidate));
    }

    private String sha1Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成 dev-safe magnet fixture info hash", ex);
        }
    }
}
