package com.prodigalgal.ircs.aggregation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AggregationMatchingStrategy {

    private static final double MATCH_THRESHOLD = 0.80;
    private static final double TITLE_BLOCK_THRESHOLD = 0.80;
    private static final double TITLE_WEIGHT = 0.70;
    private static final double METADATA_WEIGHT = 0.30;
    private static final double TITLE_ONLY_CONFIDENCE = 0.95;
    private static final double EPISODE_PENALTY = 0.15;
    private static final double AREA_PENALTY = 0.10;
    private static final Pattern SEASON_PATTERN = Pattern.compile(
            "(?i)(?:season|s)\\s*(\\d+)|第\\s*([0-9一二三四五六七八九十百两]+)\\s*[季部]");
    private static final Pattern VERSION_REAL = Pattern.compile("(?i)(真人版|剧版|live\\s*action)");
    private static final Pattern VERSION_ANIME = Pattern.compile("(?i)(动画版|动漫|anime|animation)");
    private static final Pattern RELEASE_NOISE = Pattern.compile(
            "(?i)(全集|更新至|完结|国语|粤语|中字|4k|1080p|2160p|bluray|web-dl|第\\d+集|\\d+集全)");
    private static final Map<Character, Integer> CHINESE_DIGITS = Map.ofEntries(
            Map.entry('零', 0),
            Map.entry('一', 1),
            Map.entry('二', 2),
            Map.entry('两', 2),
            Map.entry('三', 3),
            Map.entry('四', 4),
            Map.entry('五', 5),
            Map.entry('六', 6),
            Map.entry('七', 7),
            Map.entry('八', 8),
            Map.entry('九', 9));

    private final JaroWinklerSimilarity stringSimilarity = new JaroWinklerSimilarity();

    public Optional<UUID> findBestMatch(
            RawVideoAggregationRecord rawVideo,
            List<UnifiedVideoAggregationCandidate> candidates) {
        List<UUID> matches = findMergeableMatches(rawVideo, candidates);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.getFirst());
    }

    public AggregationMatchPlan findMatchPlan(
            RawVideoAggregationRecord rawVideo,
            List<UnifiedVideoAggregationCandidate> candidates) {
        List<UUID> matches = findMergeableMatches(rawVideo, candidates);
        if (matches.isEmpty()) {
            return AggregationMatchPlan.none();
        }
        if (matches.size() == 1) {
            return AggregationMatchPlan.rootOnly(matches.getFirst());
        }
        return AggregationMatchPlan.rootWithVictims(matches.getFirst(), matches.subList(1, matches.size()));
    }

    private List<UUID> findMergeableMatches(
            RawVideoAggregationRecord rawVideo,
            List<UnifiedVideoAggregationCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> new ScoredCandidate(candidate.id(), calculateSimilarity(rawVideo, candidate)))
                .filter(candidate -> candidate.score() >= MATCH_THRESHOLD)
                .sorted(Comparator
                        .comparingDouble(ScoredCandidate::score)
                        .reversed()
                        .thenComparing(candidate -> candidate.id().toString()))
                .map(ScoredCandidate::id)
                .toList();
    }

    double calculateSimilarity(RawVideoAggregationRecord rawVideo, UnifiedVideoAggregationCandidate candidate) {
        VideoFingerprint rawFingerprint = fingerprint(rawVideo);
        VideoFingerprint candidateFingerprint = fingerprint(candidate);

        if (!isSeasonCompatible(rawFingerprint.season(), candidateFingerprint.season())) {
            return 0.0;
        }
        if (!isVersionCompatible(rawFingerprint.version(), candidateFingerprint.version())) {
            return 0.0;
        }
        if (!isYearCompatible(rawVideo.year(), candidate.year())) {
            return 0.0;
        }

        int externalIdCheck = checkExternalIds(rawVideo, candidate);
        if (externalIdCheck < 0) {
            return 0.0;
        }
        if (externalIdCheck > 0) {
            return 1.0;
        }

        double titleScore = maxTitleSimilarity(rawFingerprint.titles(), candidateFingerprint.titles());
        if (titleScore < TITLE_BLOCK_THRESHOLD) {
            return 0.0;
        }

        return scoreWithMetadataAndPenalties(
                titleScore,
                rawVideo.actorNames(),
                rawVideo.directorNames(),
                candidate.actorNames(),
                candidate.directorNames(),
                rawVideo.totalEpisodes(),
                candidate.totalEpisodes(),
                rawVideo.areaNames(),
                candidate.areaNames());
    }

    double calculateSimilarity(RawVideoAggregationRecord left, RawVideoAggregationRecord right) {
        VideoFingerprint leftFingerprint = fingerprint(left);
        VideoFingerprint rightFingerprint = fingerprint(right);

        if (!isSeasonCompatible(leftFingerprint.season(), rightFingerprint.season())) {
            return 0.0;
        }
        if (!isVersionCompatible(leftFingerprint.version(), rightFingerprint.version())) {
            return 0.0;
        }
        int externalIdCheck = checkExternalIds(left, right);
        if (externalIdCheck < 0) {
            return 0.0;
        }
        if (externalIdCheck > 0) {
            return 1.0;
        }

        double titleScore = maxTitleSimilarity(leftFingerprint.titles(), rightFingerprint.titles());
        if (titleScore < TITLE_BLOCK_THRESHOLD) {
            return 0.0;
        }

        return scoreWithMetadataAndPenalties(
                titleScore,
                left.actorNames(),
                left.directorNames(),
                right.actorNames(),
                right.directorNames(),
                left.totalEpisodes(),
                right.totalEpisodes(),
                left.areaNames(),
                right.areaNames());
    }

    double calculateSimilarity(AggregationGraphCandidate left, AggregationGraphCandidate right) {
        if (left instanceof AggregationGraphCandidate.RawNode leftRaw
                && right instanceof AggregationGraphCandidate.RawNode rightRaw) {
            return calculateSimilarity(leftRaw.rawVideo(), rightRaw.rawVideo());
        }
        if (left instanceof AggregationGraphCandidate.RawNode leftRaw
                && right instanceof AggregationGraphCandidate.UnifiedNode rightUnified) {
            return calculateSimilarity(leftRaw.rawVideo(), rightUnified.unifiedVideo());
        }
        if (left instanceof AggregationGraphCandidate.UnifiedNode leftUnified
                && right instanceof AggregationGraphCandidate.RawNode rightRaw) {
            return calculateSimilarity(rightRaw.rawVideo(), leftUnified.unifiedVideo());
        }
        return 0.0;
    }

    private VideoFingerprint fingerprint(RawVideoAggregationRecord rawVideo) {
        return fingerprint(rawVideo.title(), rawVideo.aliasTitle(), rawVideo.subtitle(), rawVideo.remarks(), rawVideo.season());
    }

    private VideoFingerprint fingerprint(UnifiedVideoAggregationCandidate candidate) {
        return fingerprint(
                candidate.title(),
                candidate.aliasTitle(),
                candidate.subtitle(),
                candidate.remarks(),
                candidate.season());
    }

    private VideoFingerprint fingerprint(String title, String aliasTitle, String subtitle, String remarks, Integer season) {
        List<String> rawTitles = new ArrayList<>();
        addIfText(rawTitles, title);
        addIfText(rawTitles, aliasTitle);
        addIfText(rawTitles, subtitle);

        Integer parsedSeason = season;
        VideoVersion version = VideoVersion.NORMAL;
        List<String> normalizedTitles = new ArrayList<>();
        for (String rawTitle : rawTitles) {
            if (VERSION_REAL.matcher(rawTitle).find()) {
                version = VideoVersion.REAL_PERSON;
            } else if (VERSION_ANIME.matcher(rawTitle).find()) {
                version = VideoVersion.ANIME;
            }
            if (parsedSeason == null) {
                parsedSeason = parseSeason(rawTitle);
            }

            String normalized = normalizeTitle(rawTitle);
            if (StringUtils.hasText(normalized)) {
                normalizedTitles.add(normalized);
            }
        }
        if (version == VideoVersion.NORMAL && StringUtils.hasText(remarks)) {
            if (VERSION_REAL.matcher(remarks).find()) {
                version = VideoVersion.REAL_PERSON;
            } else if (VERSION_ANIME.matcher(remarks).find()) {
                version = VideoVersion.ANIME;
            }
        }
        if (normalizedTitles.isEmpty() && StringUtils.hasText(title)) {
            normalizedTitles.add(title.trim().toLowerCase(Locale.ROOT));
        }
        return new VideoFingerprint(normalizedTitles, parsedSeason, version);
    }

    private String normalizeTitle(String value) {
        String normalized = RELEASE_NOISE.matcher(value).replaceAll(" ");
        normalized = SEASON_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = VERSION_REAL.matcher(normalized).replaceAll(" ");
        normalized = VERSION_ANIME.matcher(normalized).replaceAll(" ");
        normalized = normalized.replaceAll("[\\p{Punct}\\s]+", "");
        return normalized.trim().toLowerCase(Locale.ROOT);
    }

    private double maxTitleSimilarity(List<String> leftTitles, List<String> rightTitles) {
        double max = 0.0;
        for (String leftTitle : leftTitles) {
            for (String rightTitle : rightTitles) {
                max = Math.max(max, stringSimilarity.apply(leftTitle, rightTitle));
            }
        }
        return max;
    }

    private double scoreWithMetadataAndPenalties(
            double titleScore,
            Set<String> leftActors,
            Set<String> leftDirectors,
            Set<String> rightActors,
            Set<String> rightDirectors,
            String leftTotalEpisodes,
            String rightTotalEpisodes,
            Set<String> leftAreas,
            Set<String> rightAreas) {
        double score;
        if (isMetadataEmpty(leftActors, leftDirectors) || isMetadataEmpty(rightActors, rightDirectors)) {
            score = titleScore * TITLE_ONLY_CONFIDENCE;
        } else {
            score = titleScore * TITLE_WEIGHT
                    + metadataJaccard(leftActors, leftDirectors, rightActors, rightDirectors) * METADATA_WEIGHT;
        }
        if (!isEpisodeCountCompatible(leftTotalEpisodes, rightTotalEpisodes)) {
            score -= EPISODE_PENALTY;
        }
        if (!areaMatch(leftAreas, rightAreas)) {
            score -= AREA_PENALTY;
        }
        return clamp(score);
    }

    private boolean isMetadataEmpty(Set<String> actors, Set<String> directors) {
        return (actors == null || actors.isEmpty()) && (directors == null || directors.isEmpty());
    }

    private double metadataJaccard(
            Set<String> leftActors,
            Set<String> leftDirectors,
            Set<String> rightActors,
            Set<String> rightDirectors) {
        Set<String> left = metadataNameSet(leftActors, leftDirectors);
        Set<String> right = metadataNameSet(rightActors, rightDirectors);
        if (left.isEmpty() || right.isEmpty()) {
            return 0.5;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0.5 : (double) intersection.size() / union.size();
    }

    private Set<String> metadataNameSet(Set<String> actors, Set<String> directors) {
        Set<String> names = new HashSet<>();
        addNormalizedNames(names, actors);
        addNormalizedNames(names, directors);
        return names;
    }

    private void addNormalizedNames(Set<String> target, Set<String> values) {
        if (values == null) {
            return;
        }
        values.stream()
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .forEach(target::add);
    }

    private boolean isEpisodeCountCompatible(String left, String right) {
        Integer leftCount = parseEpisodeCount(left);
        Integer rightCount = parseEpisodeCount(right);
        if (leftCount == null || rightCount == null) {
            return true;
        }
        return (leftCount > 1 || rightCount <= 5) && (rightCount > 1 || leftCount <= 5);
    }

    private Integer parseEpisodeCount(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        if (!StringUtils.hasText(digits)) {
            return null;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean areaMatch(Set<String> leftAreas, Set<String> rightAreas) {
        if (leftAreas == null || leftAreas.isEmpty() || rightAreas == null || rightAreas.isEmpty()) {
            return true;
        }
        for (String left : leftAreas) {
            for (String right : rightAreas) {
                if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
                    continue;
                }
                String normalizedLeft = left.trim().toLowerCase(Locale.ROOT);
                String normalizedRight = right.trim().toLowerCase(Locale.ROOT);
                if (normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft)) {
                    return true;
                }
            }
        }
        return false;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private int checkExternalIds(RawVideoAggregationRecord rawVideo, UnifiedVideoAggregationCandidate candidate) {
        return checkExternalIds(
                rawVideo.doubanId(),
                rawVideo.tmdbId(),
                rawVideo.imdbId(),
                rawVideo.rottenTomatoesId(),
                candidate.doubanId(),
                candidate.tmdbId(),
                candidate.imdbId(),
                candidate.rottenTomatoesId());
    }

    private int checkExternalIds(RawVideoAggregationRecord left, RawVideoAggregationRecord right) {
        return checkExternalIds(
                left.doubanId(),
                left.tmdbId(),
                left.imdbId(),
                left.rottenTomatoesId(),
                right.doubanId(),
                right.tmdbId(),
                right.imdbId(),
                right.rottenTomatoesId());
    }

    private int checkExternalIds(
            String leftDoubanId,
            String leftTmdbId,
            String leftImdbId,
            String leftRottenTomatoesId,
            String rightDoubanId,
            String rightTmdbId,
            String rightImdbId,
            String rightRottenTomatoesId) {
        int douban = checkExternalId(leftDoubanId, rightDoubanId);
        int tmdb = checkExternalId(leftTmdbId, rightTmdbId);
        int imdb = checkExternalId(leftImdbId, rightImdbId);
        int rottenTomatoes = checkExternalId(leftRottenTomatoesId, rightRottenTomatoesId);
        if (douban < 0 || tmdb < 0 || imdb < 0 || rottenTomatoes < 0) {
            return -1;
        }
        if (douban > 0 || tmdb > 0 || imdb > 0 || rottenTomatoes > 0) {
            return 1;
        }
        return 0;
    }

    private int checkExternalId(String left, String right) {
        if (!isValidExternalId(left) || !isValidExternalId(right)) {
            return 0;
        }
        return left.trim().equalsIgnoreCase(right.trim()) ? 1 : -1;
    }

    private boolean isValidExternalId(String value) {
        return StringUtils.hasText(value) && !"0".equals(value.trim()) && !"0.0".equals(value.trim());
    }

    private boolean isYearCompatible(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return true;
        }
        return left.trim().equals(right.trim());
    }

    private boolean isSeasonCompatible(Integer left, Integer right) {
        int normalizedLeft = left == null ? 1 : left;
        int normalizedRight = right == null ? 1 : right;
        return normalizedLeft == normalizedRight;
    }

    private boolean isVersionCompatible(VideoVersion left, VideoVersion right) {
        return left == right || left == VideoVersion.NORMAL || right == VideoVersion.NORMAL;
    }

    private Integer parseSeason(String value) {
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

    private Integer parseChineseNumber(String token) {
        int result = 0;
        int current = 0;
        for (char c : token.toCharArray()) {
            if (CHINESE_DIGITS.containsKey(c)) {
                current = CHINESE_DIGITS.get(c);
            } else if (c == '十') {
                result += current == 0 ? 10 : current * 10;
                current = 0;
            } else if (c == '百') {
                result += current == 0 ? 100 : current * 100;
                current = 0;
            }
        }
        int parsed = result + current;
        return parsed == 0 ? null : parsed;
    }

    private void addIfText(List<String> values, String value) {
        if (StringUtils.hasText(value)) {
            values.add(value);
        }
    }

    private record VideoFingerprint(List<String> titles, Integer season, VideoVersion version) {
    }

    private enum VideoVersion {
        NORMAL,
        REAL_PERSON,
        ANIME
    }

    private record ScoredCandidate(UUID id, double score) {
    }
}
