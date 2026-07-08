package com.prodigalgal.ircs.portal;

import com.prodigalgal.ircs.common.cache.CacheRegistry;
import com.prodigalgal.ircs.common.cache.TtlGovernedCache;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class PortalStandardNameCache {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TtlGovernedCache<Dictionary, Map<String, String>> cache;

    PortalStandardNameCache(
            NamedParameterJdbcTemplate jdbcTemplate,
            CacheRegistry cacheRegistry,
            @Value("${app.portal.cache.dictionary-ttl:PT30M}") Duration ttl) {
        this.jdbcTemplate = jdbcTemplate;
        this.cache = new TtlGovernedCache<>(
                "portal.standard-name-dictionary",
                normalizeTtl(ttl),
                dictionary -> dictionary.externalKey);
        cacheRegistry.register(cache);
    }

    List<String> resolveGenreNames(Iterable<String> codes) {
        return resolveNames(Dictionary.GENRE, codes);
    }

    List<String> resolveAreaNames(Iterable<String> codes) {
        return resolveNames(Dictionary.AREA, codes);
    }

    List<String> resolveLanguageNames(Iterable<String> codes) {
        return resolveNames(Dictionary.LANGUAGE, codes);
    }

    private List<String> resolveNames(Dictionary dictionary, Iterable<String> codes) {
        List<String> normalizedCodes = normalizeCodes(codes);
        if (normalizedCodes.isEmpty()) {
            return List.of();
        }
        Map<String, String> namesByCode = cache.get(dictionary, () -> loadDictionary(dictionary));
        return normalizedCodes.stream()
                .map(code -> fallback(namesByCode.get(code), code))
                .sorted()
                .toList();
    }

    private List<String> normalizeCodes(Iterable<String> codes) {
        List<String> normalizedCodes = new ArrayList<>();
        if (codes == null) {
            return normalizedCodes;
        }
        for (String code : codes) {
            if (StringUtils.hasText(code) && !normalizedCodes.contains(code.trim())) {
                normalizedCodes.add(code.trim());
            }
        }
        return normalizedCodes;
    }

    private Map<String, String> loadDictionary(Dictionary dictionary) {
        Map<String, String> namesByCode = new LinkedHashMap<>();
        jdbcTemplate.query(
                "select code, name from " + dictionary.tableName + " where code is not null",
                Map.of(),
                rs -> {
                    String code = rs.getString("code");
                    if (StringUtils.hasText(code)) {
                        namesByCode.putIfAbsent(code.trim(), fallback(rs.getString("name"), code.trim()));
                    }
                });
        return Map.copyOf(namesByCode);
    }

    private String fallback(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static Duration normalizeTtl(Duration value) {
        return value == null || value.isZero() || value.isNegative() ? DEFAULT_TTL : value;
    }

    private enum Dictionary {
        GENRE("standard_genre", "genre"),
        AREA("standard_areas", "area"),
        LANGUAGE("standard_languages", "language");

        private final String tableName;
        private final String externalKey;

        Dictionary(String tableName, String externalKey) {
            this.tableName = tableName;
            this.externalKey = externalKey;
        }
    }
}
