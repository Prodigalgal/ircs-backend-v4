package com.prodigalgal.ircs.aggregation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

final class AggregationMatchKeys {

    private static final int MAX_KEYS = 12;
    private static final Pattern SEASON_PATTERN = Pattern.compile(
            "(?i)(?:season|s)\\s*(\\d+)|第\\s*([0-9一二三四五六七八九十百两]+)\\s*[季部]");
    private static final Pattern VERSION_REAL = Pattern.compile("(?i)(真人版|剧版|live\\s*action)");
    private static final Pattern VERSION_ANIME = Pattern.compile("(?i)(动画版|动漫|anime|animation)");
    private static final Pattern RELEASE_NOISE = Pattern.compile(
            "(?i)(全集|更新至|完结|国语|粤语|中字|4k|1080p|2160p|bluray|web-dl|第\\d+集|\\d+集全)");

    private AggregationMatchKeys() {
    }

    static List<String> forRawVideo(RawVideoAggregationRecord rawVideo) {
        if (rawVideo == null) {
            return List.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        addExternalId(keys, "douban", rawVideo.doubanId());
        addExternalId(keys, "tmdb", rawVideo.tmdbId());
        addExternalId(keys, "imdb", rawVideo.imdbId());
        addExternalId(keys, "rt", rawVideo.rottenTomatoesId());
        addTitleKeys(keys, rawVideo.title(), rawVideo.aliasTitle(), rawVideo.subtitle(), rawVideo.year(), rawVideo.season());
        if (keys.isEmpty() && rawVideo.id() != null) {
            keys.add("raw:" + rawVideo.id());
        }
        return limit(keys);
    }

    static List<String> forCluster(RawVideoAggregationCluster cluster) {
        if (cluster == null) {
            return List.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (RawVideoAggregationRecord rawVideo : cluster.members()) {
            keys.addAll(forRawVideo(rawVideo));
        }
        for (UUID unifiedVideoId : cluster.contextUnifiedVideoIds()) {
            if (unifiedVideoId != null) {
                keys.add("unified:" + unifiedVideoId);
            }
        }
        return limit(keys);
    }

    static List<String> withMatchPlan(Collection<String> keys, AggregationMatchPlan matchPlan) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        if (keys != null) {
            all.addAll(keys);
        }
        if (matchPlan != null) {
            if (matchPlan.rootUnifiedVideoId() != null) {
                all.add("unified:" + matchPlan.rootUnifiedVideoId());
            }
            for (UUID victimUnifiedVideoId : matchPlan.victimUnifiedVideoIds()) {
                if (victimUnifiedVideoId != null) {
                    all.add("unified:" + victimUnifiedVideoId);
                }
            }
        }
        return limit(all);
    }

    private static void addExternalId(Set<String> keys, String provider, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("0".equals(normalized) || "0.0".equals(normalized)) {
            return;
        }
        keys.add(provider + ":" + digest(normalized));
    }

    private static void addTitleKeys(
            Set<String> keys,
            String title,
            String aliasTitle,
            String subtitle,
            String year,
            Integer season) {
        List<String> titles = new ArrayList<>();
        addIfText(titles, title);
        addIfText(titles, aliasTitle);
        addIfText(titles, subtitle);
        Integer parsedSeason = season;
        VideoVersion version = VideoVersion.NORMAL;
        for (String rawTitle : titles) {
            if (VERSION_REAL.matcher(rawTitle).find()) {
                version = VideoVersion.REAL_PERSON;
            } else if (VERSION_ANIME.matcher(rawTitle).find()) {
                version = VideoVersion.ANIME;
            }
            if (parsedSeason == null) {
                parsedSeason = parseSeason(rawTitle);
            }
        }
        for (String rawTitle : titles) {
            String normalized = normalizeTitle(rawTitle);
            if (StringUtils.hasText(normalized)) {
                keys.add("title:" + digest(normalized + "|year=" + normalize(year)
                        + "|season=" + (parsedSeason == null ? 1 : parsedSeason)
                        + "|version=" + version.name()));
            }
        }
    }

    private static String normalizeTitle(String value) {
        String normalized = RELEASE_NOISE.matcher(value).replaceAll(" ");
        normalized = SEASON_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = VERSION_REAL.matcher(normalized).replaceAll(" ");
        normalized = VERSION_ANIME.matcher(normalized).replaceAll(" ");
        normalized = normalized.replaceAll("[\\p{Punct}\\s]+", "");
        return normalized.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    private static void addIfText(List<String> values, String value) {
        if (StringUtils.hasText(value)) {
            values.add(value);
        }
    }

    private static Integer parseSeason(String value) {
        Matcher matcher = SEASON_PATTERN.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        String token = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
        if (!StringUtils.hasText(token)) {
            return null;
        }
        if (token.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(token);
        }
        return parseChineseNumber(token);
    }

    private static Integer parseChineseNumber(String token) {
        int result = 0;
        int current = 0;
        for (char c : token.toCharArray()) {
            current = switch (c) {
                case '一' -> 1;
                case '二', '两' -> 2;
                case '三' -> 3;
                case '四' -> 4;
                case '五' -> 5;
                case '六' -> 6;
                case '七' -> 7;
                case '八' -> 8;
                case '九' -> 9;
                case '十' -> {
                    result += current == 0 ? 10 : current * 10;
                    current = 0;
                    yield 0;
                }
                case '百' -> {
                    result += current == 0 ? 100 : current * 100;
                    current = 0;
                    yield 0;
                }
                default -> current;
            };
        }
        int parsed = result + current;
        return parsed == 0 ? null : parsed;
    }

    private static List<String> limit(Collection<String> keys) {
        return keys.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .limit(MAX_KEYS)
                .toList();
    }

    private static String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private enum VideoVersion {
        NORMAL,
        REAL_PERSON,
        ANIME
    }
}
