package com.prodigalgal.ircs.magnet;

import java.util.Locale;
import org.springframework.util.StringUtils;

class GenericMagnetRequestUrlBuilder {

    String build(MagnetProviderSummary provider, MagnetExternalIdQuery query) {
        String base = provider == null ? null : provider.baseUrl();
        if (!StringUtils.hasText(base)) {
            throw new MagnetProviderRunnerException(GenericMagnetProviderSearchRunner.HTTP_ERROR, null, null);
        }
        String normalizedBase = trimTrailingSlash(base.trim());
        String encodedQuery = MagnetUriUtils.encodeQueryValue(query.value());
        if (normalizedBase.contains("{query}")) {
            return normalizedBase.replace("{query}", encodedQuery);
        }
        if (normalizedBase.contains("{rawQuery}")) {
            return normalizedBase.replace("{rawQuery}", query.value());
        }
        return switch (normalize(provider.providerType())) {
            case "EZTV", "EZTVX" -> eztvUrl(normalizedBase, query);
            case "THE_PIRATE_BAY", "APIBAY" -> normalizedBase + "/q.php?q=" + encodedQuery + "&cat=0";
            case "THE_PIRATE_BAY_FRONTEND", "TPB_FRONTEND" -> normalizedBase + "/search.php?q=" + encodedQuery;
            case "EXT_TO" -> normalizedBase + "/search/?q=" + encodedQuery;
            case "NYAA" -> normalizedBase + "/?f=0&c=0_0&q=" + encodedQuery;
            case "BT4G" -> normalizedBase + "/search/" + encodedQuery;
            case "TORRENT_GALAXY" -> normalizedBase + "/torrents.php?search=" + encodedQuery;
            case "SOLIDTORRENTS" -> normalizedBase + "/search?q=" + encodedQuery;
            default -> appendQuery(normalizedBase, "q", encodedQuery);
        };
    }

    private String eztvUrl(String normalizedBase, MagnetExternalIdQuery query) {
        String base = normalizedBase + "/api/get-torrents?limit=50";
        if ("IMDB".equalsIgnoreCase(query.type())) {
            String imdbId = query.value().trim();
            if (imdbId.regionMatches(true, 0, "tt", 0, 2)) {
                imdbId = imdbId.substring(2);
            }
            return base + "&imdb_id=" + MagnetUriUtils.encodeQueryValue(imdbId);
        }
        return base + "&keywords=" + MagnetUriUtils.encodeQueryValue(query.value());
    }

    private String appendQuery(String base, String key, String encodedValue) {
        return base + (base.contains("?") ? "&" : "/search?") + key + "=" + encodedValue;
    }

    private String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
