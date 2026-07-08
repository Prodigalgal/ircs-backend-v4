package com.prodigalgal.ircs.normalization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.houbb.opencc4j.util.ZhJpConverterUtil;
import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.dictionary.other.CharTable;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RawVideoTextNormalizer {

    private static final Pattern YEAR_PATTERN = Pattern.compile("(?:18|19|20)\\d{2}");
    private static final Pattern SPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern EDGE_SYMBOLS = Pattern.compile("^[^a-zA-Z0-9\\u4e00-\\u9fa5]+|[^a-zA-Z0-9\\u4e00-\\u9fa5]+$");
    private static final Pattern TITLE_LEADING_EDGE_SYMBOLS = Pattern.compile("^[^a-zA-Z0-9\\u4e00-\\u9fa5]+");
    private static final Pattern TITLE_TRAILING_EDGE_SYMBOLS = Pattern.compile("[^a-zA-Z0-9\\u4e00-\\u9fa5)]+$");
    private static final Pattern BRACKET_YEAR_PATTERN = Pattern.compile("[\\[(]\\s*(?:18|19|20)\\d{2}\\s*[\\])]");
    private static final Pattern LEADING_RELEASE_GROUP_PATTERN = Pattern.compile(
            "(?i)^(?:\\[[a-z0-9][a-z0-9._ -]{1,31}]|\\([a-z0-9][a-z0-9._ -]{1,31}\\))\\s*");
    private static final Pattern FILE_EXTENSION_PATTERN = Pattern.compile("(?i)\\.(mp4|mkv|avi|rmvb|ts|mov)$");
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)(?:www\\.|https?://|ftp://)[a-z0-9.-]+\\.[a-z]{2,}(?:/[^\\s]*)?");
    private static final Pattern RELEASE_NOISE = Pattern.compile(
            "(?i)\\b(2160p|1440p|1080p|720p|480p|web-dl|webrip|bluray|blu-ray|hdtv|x264|x265|h264|h265|hevc|aac|ddp|hdr|4k|8k)\\b.*$");
    private static final String BRACKET_NOISE_TOKEN =
            "2160p|1440p|1080p|1080i|720p|720i|480p|uhd|fhd|hd|bd|tc|cam|dvd|web-dl|webrip|"
                    + "blu-ray|bluray|remux|bdrip|hdtv|dvdrip|hdr|hevc|x264|x265|h264|h265|aac|"
                    + "ac3|dts|ddp|webdl|mp4|mkv|avi|ts|高清|蓝光|超清|标清|枪版|正式版|修正版|"
                    + "收藏版|纯净版|网络版|tv版|短剧版|官采|国语|粤语|英语|日语|韩语|中英|中日|"
                    + "中韩|简繁|字幕|配音|普通话|汉语|双语|中字|双字|国粤|港台|台配|中配|"
                    + "日配|英配|韩配|chs|cht|eng|jpn|kr|big5|gb";
    private static final Pattern PURE_BRACKET_NOISE = Pattern.compile(
            "(?i)^[\\s\\p{Punct}_]*(?:\\d+|" + BRACKET_NOISE_TOKEN + ")(?:[\\s\\p{Punct}_]*(?:\\d+|"
                    + BRACKET_NOISE_TOKEN + "))*[\\s\\p{Punct}_]*$");
    private static final Pattern PARENTHESES_CONTENT = Pattern.compile("\\(([^)]*)(\\)|$)");
    private static final Pattern BRACKET_CONTENT = Pattern.compile("\\[([^]]*)(\\]|$)");
    private static final Pattern BILINGUAL_NUMBER_REPEAT = Pattern.compile(
            "^(.+?)(\\d+)\\s+([a-zA-Z0-9\\s\\p{Punct}]+)\\s+\\2$");
    private static final Pattern SCRIPT_BOUNDARY = Pattern.compile(
            "(?<=\\p{IsHan})(?=\\p{IsLatin})|(?<=\\p{IsLatin})(?=\\p{IsHan})");
    private static final Pattern MONOLINGUAL_REPEAT = Pattern.compile("^(.+?)[\\s:：-]*\\1$");
    private static final Pattern CHINESE_ORDINAL = Pattern.compile("第([零一二三四五六七八九十百两]+)([季部])");
    private static final Pattern SEASON_PATTERN = Pattern.compile(
            "(?i)(?:第\\s*([0-9]{1,3})\\s*[季部]|\\bseason\\s*[-_:]?\\s*([0-9]{1,3})\\b|\\bs\\s*([0-9]{1,3})\\b)");
    private static final Pattern SEASON_ONLY_PATTERN = Pattern.compile(
            "(?i)^(?:第\\s*[0-9]{1,3}\\s*[季部]|season\\s*[-_:]?\\s*[0-9]{1,3}|s\\s*[0-9]{1,3})$");
    private static final Pattern COLON_TITLE_SPLIT = Pattern.compile("^(.+?)\\s*:\\s*(.+)$");
    private static final Pattern DASH_TITLE_SPLIT = Pattern.compile("^(.+?)\\s+[—–-]\\s+(.+)$");
    private static final Pattern HAN_SPACE_TITLE_SPLIT = Pattern.compile("^(\\S.+?)\\s+([^\\s].+)$");
    private static final Pattern BRACKET_SUBTITLE_SPLIT = Pattern.compile("^(.+?)\\s*\\(([^)]{1,80})\\)\\s*$");
    private static final Pattern COMPACT_CN_SPLIT = Pattern.compile("^([\\u4e00-\\u9fa5]{2,})(\\d+)([^\\d\\s].*)$");
    private static final Pattern TRAILING_NUM_PATTERN = Pattern.compile("^(.+?)\\s*(?<!\\d)(\\d{1,2})$");
    private static final Pattern UNICODE_ROMAN_PATTERN = Pattern.compile("[\\u2160-\\u216B]");
    private static final Pattern RELATION_SPLIT_PATTERN = Pattern.compile("[,，、/|;；]+");

    private final ObjectMapper objectMapper;
    private final DescriptionMetadataExtractor descriptionMetadataExtractor;
    private final RawRelationAliasPolicy rawRelationAliasPolicy;
    public RawVideoTextNormalizer(
            ObjectMapper objectMapper,
            DescriptionMetadataExtractor descriptionMetadataExtractor,
            RawRelationAliasPolicy rawRelationAliasPolicy) {
        this.objectMapper = objectMapper;
        this.descriptionMetadataExtractor = descriptionMetadataExtractor;
        this.rawRelationAliasPolicy = rawRelationAliasPolicy;
    }

    public RawVideoPatch normalize(RawVideoRecord record, JsonNode metadata) {
        Set<String> locked = parseLockedFields(record.lockedFields());
        String title = record.title();
        String aliasTitle = record.aliasTitle();
        Integer season = record.season();
        String subtitle = record.subtitle();
        String description = record.description();
        String year = record.year();
        String area = record.area();
        String language = record.rawLanguageStr();
        String remarks = record.remarks();
        BigDecimal score = record.score();
        String totalEpisodes = record.totalEpisodes();
        String duration = record.duration();
        String doubanId = record.doubanId();
        String tmdbId = record.tmdbId();
        String imdbId = record.imdbId();
        String rottenTomatoesId = record.rottenTomatoesId();
        UUID rawCategoryDataSourceId = null;
        String rawCategorySourceCode = null;
        String rawCategorySourceName = null;

        if (!locked.contains("year")) {
            year = normalizeYear(firstText(metadata, year, "year", "releaseYear"));
        }
        String sourceTitle = firstText(metadata, title, "title", "name");
        TitleStructure titleStructure = extractTitleStructure(sourceTitle, year);
        if (!locked.contains("title")) {
            title = StringUtils.hasText(titleStructure.mainTitle())
                    ? titleStructure.mainTitle()
                    : cleanTitle(sourceTitle, year);
        }
        if (!locked.contains("season")) {
            season = parseSeason(firstText(metadata, null, "season"));
            if (season == null) {
                season = titleStructure.season();
            }
            if (season == null) {
                season = record.season();
            }
        }
        if (!isSubtitleLocked(locked)) {
            subtitle = cleanSubtitle(firstText(metadata, subtitle, "subtitle", "sub_title"), year);
            if (!StringUtils.hasText(subtitle)) {
                subtitle = titleStructure.subtitle();
            }
        }
        if (!locked.contains("aliasTitle")) {
            aliasTitle = cleanAliasTitle(
                    firstText(metadata, aliasTitle, "aliasTitle", "subTitle", "alias"),
                    title,
                    season,
                    subtitle);
        }
        if (!locked.contains("description")) {
            description = cleanDescription(firstText(metadata, description, "description", "desc", "plot"));
        }
        if (!locked.contains("area")) {
            area = trimToLength(firstText(metadata, area, "area", "region"), 50);
        }
        if (!locked.contains("language")) {
            language = trimToLength(firstText(metadata, language, "language", "lang"), 255);
        }
        if (!locked.contains("remarks")) {
            remarks = trimToLength(firstText(metadata, remarks, "remarks", "status"), 255);
        }
        if (!locked.contains("score")) {
            score = parseScore(metadata, score);
        }
        if (!locked.contains("totalEpisodes")) {
            totalEpisodes = trimToLength(firstText(metadata, totalEpisodes, "totalEpisodes", "episodes"), 50);
        }
        if (!locked.contains("duration")) {
            duration = trimToLength(firstText(metadata, duration, "duration", "runtime"), 50);
        }
        if (!locked.contains("doubanId")) {
            doubanId = normalizeId(firstText(metadata, doubanId, "doubanId"), 20);
        }
        if (!locked.contains("tmdbId")) {
            tmdbId = normalizeId(firstText(metadata, tmdbId, "tmdbId"), 20);
        }
        if (!locked.contains("imdbId")) {
            imdbId = normalizeId(firstText(metadata, imdbId, "imdbId"), 20);
        }
        if (!locked.contains("rottenTomatoesId")) {
            rottenTomatoesId = normalizeId(firstText(metadata, rottenTomatoesId, "rottenTomatoesId", "rtId"), 50);
        }
        if (!isCategoryLocked(locked)) {
            rawCategoryDataSourceId = firstUuid(metadata, record.dataSourceId(), "dataSourceId");
            rawCategorySourceCode = normalizeId(firstText(
                    metadata, null, "rawTypeId", "categoryId", "categoryCode", "typeId"), 100);
            rawCategorySourceName = trimToLength(firstText(
                    metadata, null, "rawTypeName", "categoryName", "category", "type", "videoType"), 255);
            if (!StringUtils.hasText(rawCategorySourceCode) && StringUtils.hasText(rawCategorySourceName)) {
                rawCategorySourceCode = normalizeId(rawCategorySourceName, 100);
            }
        }
        Set<String> rawGenreValues = locked.contains("genres")
                ? Set.of()
                : rawRelationAliasPolicy.expandGenres(relationValues(metadata, "genreNames", "genres", "genre"));
        if (!locked.contains("genres") && StringUtils.hasText(rawCategorySourceName)) {
            Set<String> sourceCategoryGenreValues =
                    rawRelationAliasPolicy.sourceCategoryGenreValues(rawCategorySourceName);
            if (!sourceCategoryGenreValues.isEmpty()) {
                LinkedHashSet<String> mergedGenreValues = new LinkedHashSet<>(rawGenreValues);
                mergedGenreValues.addAll(sourceCategoryGenreValues);
                rawGenreValues = mergedGenreValues;
            }
        }
        Set<String> rawLanguageValues = locked.contains("language")
                ? Set.of()
                : rawRelationAliasPolicy.expandLanguages(relationValues(metadata, "language", "lang"));
        Set<String> rawAreaValues = locked.contains("area")
                ? Set.of()
                : rawRelationAliasPolicy.expandAreas(relationValues(metadata, "area", "region"));
        Set<String> actorValues = locked.contains("actors")
                ? Set.of()
                : relationValues(metadata, 255, "actorNames", "actors", "actor");
        Set<String> directorValues = locked.contains("directors")
                ? Set.of()
                : relationValues(metadata, 255, "directorNames", "directors", "director");
        if (shouldExtractPeopleFromDescription(locked, actorValues, directorValues, description)) {
            DescriptionMetadataExtractor.ExtractedMetadata extracted =
                    descriptionMetadataExtractor.extract(description);
            if (!locked.contains("actors") && actorValues.isEmpty()) {
                actorValues = trimRelationValues(extracted.actors(), 255);
            }
            if (!locked.contains("directors") && directorValues.isEmpty()) {
                directorValues = trimRelationValues(extracted.directors(), 255);
            }
        }

        return new RawVideoPatch(
                trimToLength(title, 255),
                trimToLength(aliasTitle, 255),
                season,
                trimToLength(subtitle, 255),
                description,
                trimToLength(year, 20),
                area,
                language,
                remarks,
                score,
                totalEpisodes,
                duration,
                doubanId,
                tmdbId,
                imdbId,
                rottenTomatoesId,
                rawCategoryDataSourceId,
                rawCategorySourceCode,
                rawCategorySourceName,
                rawGenreValues,
                rawLanguageValues,
                rawAreaValues,
                actorValues,
                directorValues);
    }

    private boolean shouldExtractPeopleFromDescription(
            Set<String> locked,
            Set<String> actorValues,
            Set<String> directorValues,
            String description) {
        return StringUtils.hasText(description)
                && ((!locked.contains("actors") && actorValues.isEmpty())
                || (!locked.contains("directors") && directorValues.isEmpty()));
    }

    Set<String> parseLockedFields(String raw) {
        Set<String> locked = new HashSet<>();
        if (!StringUtils.hasText(raw)) {
            return locked;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node.isArray()) {
                node.forEach(item -> {
                    if (item.isTextual() && StringUtils.hasText(item.asText())) {
                        locked.add(item.asText());
                    }
                });
            } else if (node.isObject()) {
                Iterator<String> names = node.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    JsonNode value = node.get(name);
                    if (value == null || value.asBoolean(true)) {
                        locked.add(name);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return locked;
    }

    private String firstText(JsonNode metadata, String fallback, String... names) {
        for (String name : names) {
            JsonNode value = metadata.get(name);
            if (value != null && !value.isNull()) {
                String text = value.isTextual() ? value.asText() : value.toString();
                if (StringUtils.hasText(text)) {
                    return standardize(text.trim());
                }
            }
        }
        return fallback;
    }

    private UUID firstUuid(JsonNode metadata, UUID fallback, String... names) {
        for (String name : names) {
            JsonNode value = metadata.get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            String text = value.isTextual() ? value.asText() : value.asText(value.toString());
            if (!StringUtils.hasText(text)) {
                continue;
            }
            try {
                return UUID.fromString(text.trim());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return fallback;
    }

    private boolean isCategoryLocked(Set<String> locked) {
        return locked.contains("category")
                || locked.contains("dataSourceCategory");
    }

    private boolean isSubtitleLocked(Set<String> locked) {
        return locked.contains("subtitle") || locked.contains("sub_title");
    }

    private String cleanTitle(String value, String year) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String title = standardize(value);
        title = normalizeTitleSymbols(title);
        title = repairBrackets(title);
        title = LEADING_RELEASE_GROUP_PATTERN.matcher(title).replaceAll("");
        title = decodeBasicHtmlEntities(title);
        title = URL_PATTERN.matcher(title).replaceAll("");
        title = FILE_EXTENSION_PATTERN.matcher(title).replaceAll("");
        title = BRACKET_YEAR_PATTERN.matcher(title).replaceAll(" ");
        title = normalizeChineseOrdinals(title);
        title = removeSelfRepetition(title);
        title = removeRedundantBilingualTitle(title);
        title = RELEASE_NOISE.matcher(title).replaceAll("");
        title = processV1BracketContents(title);
        title = FILE_EXTENSION_PATTERN.matcher(title.trim()).replaceAll("");
        title = title.replaceAll("[/|\\\\&,·・　]+", " ");
        title = cleanTitleEdges(title);
        title = SPACE_PATTERN.matcher(title).replaceAll(" ").trim();
        String normalizedYear = normalizeYear(year);
        if (StringUtils.hasText(normalizedYear) && title.endsWith(normalizedYear)) {
            title = title.substring(0, title.length() - normalizedYear.length()).trim();
            title = cleanTitleEdges(title);
        }
        title = removeRedundantBilingualTitle(title);
        title = SPACE_PATTERN.matcher(title).replaceAll(" ").trim();
        return title;
    }

    private TitleStructure extractTitleStructure(String sourceTitle, String year) {
        String processing = cleanTitle(sourceTitle, year);
        if (!StringUtils.hasText(processing)) {
            return new TitleStructure(null, null, null);
        }
        String cleanedTitle = processing;
        SeasonMatch seasonMatch = findSeasonMatch(processing);
        Integer season = seasonMatch == null ? null : seasonMatch.season();
        String subtitle = null;

        if (seasonMatch != null) {
            String pre = processing.substring(0, seasonMatch.start()).trim();
            String post = processing.substring(seasonMatch.end()).trim();
            post = cleanTitleEdges(post);
            if (StringUtils.hasText(pre)) {
                processing = cleanTitleEdges(pre);
                String cleanSub = cleanTitleEdges(removeSeasonPatterns(post));
                if (StringUtils.hasText(cleanSub) && !isV1NoiseContent(cleanSub)) {
                    subtitle = cleanSub;
                }
            } else {
                processing = cleanTitleEdges(post);
            }
        }

        if (!StringUtils.hasText(subtitle)) {
            TitleSplit split = splitV1TitleStructure(processing, year);
            if (split != null) {
                processing = split.mainTitle();
                subtitle = split.subtitle();
            } else if (season == null) {
                Matcher compact = COMPACT_CN_SPLIT.matcher(processing);
                if (compact.find()) {
                    String number = compact.group(2);
                    if (!number.startsWith("0") && number.length() <= 2) {
                        Integer parsed = parsePositiveSeason(number);
                        if (parsed != null) {
                            processing = compact.group(1).trim();
                            season = parsed;
                            subtitle = compact.group(3).trim();
                        }
                    }
                }
            }
        }
        if (!StringUtils.hasText(subtitle) && containsHan(processing)) {
            TitleSplit split = splitHanSpaceTitle(processing, year);
            if (split != null) {
                processing = split.mainTitle();
                subtitle = split.subtitle();
            }
        }
        if (season == null && StringUtils.hasText(processing) && !processing.matches("^\\d+$")) {
            Matcher trailing = TRAILING_NUM_PATTERN.matcher(processing);
            if (trailing.find()) {
                String potentialTitle = trailing.group(1).trim();
                String number = trailing.group(2);
                Integer parsed = parsePositiveSeason(number);
                if (parsed != null
                        && !number.startsWith("0")
                        && potentialTitle.length() >= 2
                        && !endsWithPartOrVolumeMarker(potentialTitle)) {
                    processing = potentialTitle;
                    season = parsed;
                }
            }
        }
        if (StringUtils.hasText(subtitle)) {
            subtitle = cleanSubtitle(subtitle, year);
            if (!StringUtils.hasText(subtitle) || isV1NoiseContent(subtitle)) {
                subtitle = null;
            }
        }
        processing = removeSeasonPatterns(processing);
        if (StringUtils.hasText(subtitle) && processing.endsWith(subtitle)) {
            processing = processing.substring(0, processing.length() - subtitle.length()).trim();
        }
        String mainTitle = cleanTitleEdges(processing);
        return new TitleStructure(StringUtils.hasText(mainTitle) ? mainTitle : cleanedTitle, season, subtitle);
    }

    private SeasonMatch findSeasonMatch(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = SEASON_PATTERN.matcher(value);
        if (matcher.find()) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                Integer season = parseSeasonToken(matcher.group(i));
                if (season != null) {
                    return new SeasonMatch(season, matcher.start(), matcher.end());
                }
            }
        }
        matcher = UNICODE_ROMAN_PATTERN.matcher(value);
        if (matcher.find()) {
            Integer season = parseUnicodeRoman(matcher.group());
            if (season != null) {
                return new SeasonMatch(season, matcher.start(), matcher.end());
            }
        }
        return null;
    }

    private Integer parseSeason(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = normalizeChineseOrdinals(normalizeTitleSymbols(standardize(value)));
        String compact = SPACE_PATTERN.matcher(normalized).replaceAll(" ").trim();
        if (compact.matches("[0-9]{1,3}")) {
            return parsePositiveSeason(compact);
        }
        Matcher matcher = SEASON_PATTERN.matcher(compact);
        if (!matcher.find()) {
            return null;
        }
        for (int i = 1; i <= matcher.groupCount(); i++) {
            Integer season = parseSeasonToken(matcher.group(i));
            if (season != null) {
                return season;
            }
        }
        return null;
    }

    private Integer parseSeasonToken(String rawNumber) {
        if (!StringUtils.hasText(rawNumber)) {
            return null;
        }
        String normalized = rawNumber.trim();
        if (normalized.matches("[0-9]{1,3}")) {
            return parsePositiveSeason(normalized);
        }
        return parseRomanNumeral(normalized);
    }

    private Integer parsePositiveSeason(String rawNumber) {
        try {
            int parsed = Integer.parseInt(rawNumber);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer parseRomanNumeral(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        int total = 0;
        int previous = 0;
        String upper = raw.toUpperCase(java.util.Locale.ROOT);
        for (int i = upper.length() - 1; i >= 0; i--) {
            int value = switch (upper.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                case 'D' -> 500;
                case 'M' -> 1000;
                default -> -1;
            };
            if (value < 0) {
                return null;
            }
            if (value < previous) {
                total -= value;
            } else {
                total += value;
                previous = value;
            }
        }
        return total > 0 && total <= 100 ? total : null;
    }

    private Integer parseUnicodeRoman(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return switch (raw.charAt(0)) {
            case '\u2160' -> 1;
            case '\u2161' -> 2;
            case '\u2162' -> 3;
            case '\u2163' -> 4;
            case '\u2164' -> 5;
            case '\u2165' -> 6;
            case '\u2166' -> 7;
            case '\u2167' -> 8;
            case '\u2168' -> 9;
            case '\u2169' -> 10;
            case '\u216A' -> 11;
            case '\u216B' -> 12;
            default -> null;
        };
    }

    private TitleSplit splitV1TitleStructure(String title, String year) {
        TitleSplit split = splitByPattern(title, COLON_TITLE_SPLIT, year);
        if (split != null) {
            return split;
        }
        split = splitByPattern(title, DASH_TITLE_SPLIT, year);
        if (split != null) {
            return split;
        }
        split = splitByPattern(title, BRACKET_SUBTITLE_SPLIT, year);
        if (split != null) {
            return split;
        }
        if (containsHan(title)) {
            return splitHanSpaceTitle(title, year);
        }
        return null;
    }

    private TitleSplit splitHanSpaceTitle(String title, String year) {
        Matcher matcher = HAN_SPACE_TITLE_SPLIT.matcher(title);
        if (!matcher.find()) {
            return null;
        }
        String left = cleanTitleEdges(matcher.group(1).trim());
        String right = cleanSubtitle(matcher.group(2), year);
        if (left.length() < 2 || !containsHan(left) || !StringUtils.hasText(right)) {
            return null;
        }
        if (SEASON_ONLY_PATTERN.matcher(right).matches()) {
            return null;
        }
        return new TitleSplit(left, right);
    }

    private TitleSplit splitByPattern(String title, Pattern pattern, String year) {
        Matcher matcher = pattern.matcher(title);
        if (!matcher.find()) {
            return null;
        }
        String left = cleanTitleEdges(matcher.group(1).trim());
        String right = cleanSubtitle(matcher.group(2), year);
        if (left.length() < 2 || !StringUtils.hasText(right)) {
            return null;
        }
        if (SEASON_ONLY_PATTERN.matcher(right).matches()) {
            return null;
        }
        return new TitleSplit(left, right);
    }

    private String extractTrailingSeasonSubtitle(String cleanedTitle, String year) {
        Matcher matcher = SEASON_PATTERN.matcher(cleanedTitle);
        String trailingSubtitle = null;
        while (matcher.find()) {
            String post = cleanedTitle.substring(matcher.end()).trim();
            if (StringUtils.hasText(post)) {
                trailingSubtitle = cleanSubtitle(post, year);
            }
        }
        return trailingSubtitle;
    }

    private String removeSeasonPatterns(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return UNICODE_ROMAN_PATTERN.matcher(SEASON_PATTERN.matcher(value).replaceAll(" "))
                .replaceAll(" ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private boolean endsWithPartOrVolumeMarker(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return value.trim().toLowerCase(java.util.Locale.ROOT).matches(".*\\b(?:part|vol|volume)\\.?$");
    }

    private String cleanSubtitle(String value, String year) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String subtitle = standardize(value);
        subtitle = normalizeTitleSymbols(subtitle);
        subtitle = repairBrackets(subtitle);
        subtitle = LEADING_RELEASE_GROUP_PATTERN.matcher(subtitle).replaceAll("");
        subtitle = decodeBasicHtmlEntities(subtitle);
        subtitle = URL_PATTERN.matcher(subtitle).replaceAll("");
        subtitle = FILE_EXTENSION_PATTERN.matcher(subtitle).replaceAll("");
        subtitle = BRACKET_YEAR_PATTERN.matcher(subtitle).replaceAll(" ");
        subtitle = normalizeChineseOrdinals(subtitle);
        subtitle = RELEASE_NOISE.matcher(subtitle).replaceAll("");
        subtitle = processV1BracketContents(subtitle);
        subtitle = FILE_EXTENSION_PATTERN.matcher(subtitle.trim()).replaceAll("");
        subtitle = cleanTitleEdges(subtitle);
        subtitle = SPACE_PATTERN.matcher(subtitle).replaceAll(" ").trim();
        String normalizedYear = normalizeYear(year);
        if (StringUtils.hasText(normalizedYear) && subtitle.endsWith(normalizedYear)) {
            subtitle = subtitle.substring(0, subtitle.length() - normalizedYear.length()).trim();
            subtitle = cleanTitleEdges(subtitle);
        }
        return StringUtils.hasText(subtitle) ? subtitle : null;
    }

    private String cleanDescription(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Document document = Jsoup.parseBodyFragment(Parser.unescapeEntities(standardize(value), false));
        document.select("script, style, noscript").remove();
        String description = document.text();
        description = SPACE_PATTERN.matcher(description).replaceAll(" ").trim();
        return StringUtils.hasText(description) ? trimToLength(description, 4096) : null;
    }

    private String normalizeTitleSymbols(String input) {
        return input.replace('【', '[')
                .replace('】', ']')
                .replace('（', '(')
                .replace('）', ')')
                .replace('《', '(')
                .replace('》', ')')
                .replace('<', '(')
                .replace('>', ')')
                .replace('：', ':');
    }

    private String cleanTitleEdges(String input) {
        if (!StringUtils.hasText(input)) {
            return input;
        }
        String cleaned = TITLE_LEADING_EDGE_SYMBOLS.matcher(input).replaceAll("");
        return TITLE_TRAILING_EDGE_SYMBOLS.matcher(cleaned).replaceAll("");
    }

    private String repairBrackets(String input) {
        int parens = 0;
        int brackets = 0;
        for (char c : input.toCharArray()) {
            if (c == '(') {
                parens++;
            } else if (c == ')') {
                parens--;
            } else if (c == '[') {
                brackets++;
            } else if (c == ']') {
                brackets--;
            }
        }
        StringBuilder repaired = new StringBuilder(input);
        while (parens > 0) {
            repaired.append(')');
            parens--;
        }
        while (brackets > 0) {
            repaired.append(']');
            brackets--;
        }
        return repaired.toString();
    }

    private String decodeBasicHtmlEntities(String input) {
        return input.replace("&amp;", "&")
                .replace("&nbsp;", " ")
                .replace("&lt;", "(")
                .replace("&gt;", ")")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private String processV1BracketContents(String input) {
        String withoutParenNoise = processBracketPattern(input, PARENTHESES_CONTENT);
        return processBracketPattern(withoutParenNoise, BRACKET_CONTENT);
    }

    private String processBracketPattern(String input, Pattern pattern) {
        if (!StringUtils.hasText(input)) {
            return input;
        }
        StringBuilder result = new StringBuilder();
        Matcher matcher = pattern.matcher(input);
        int lastAppendPosition = 0;
        while (matcher.find()) {
            result.append(input, lastAppendPosition, matcher.start());
            String content = matcher.group(1) == null ? "" : matcher.group(1).trim();
            if (!isV1NoiseContent(content)) {
                if (!result.isEmpty() && result.charAt(result.length() - 1) != ' ') {
                    result.append(' ');
                }
                result.append('(').append(content).append(')');
            }
            lastAppendPosition = matcher.end();
        }
        if (lastAppendPosition < input.length()) {
            result.append(input.substring(lastAppendPosition));
        }
        return result.toString();
    }

    private boolean isV1NoiseContent(String content) {
        if (!StringUtils.hasText(content)) {
            return true;
        }
        String trimmed = content.trim();
        if (trimmed.matches("^[\\d\\p{Punct}]+$") && trimmed.length() > 4) {
            return true;
        }
        return PURE_BRACKET_NOISE.matcher(trimmed).matches();
    }

    private String normalizeChineseOrdinals(String input) {
        Matcher matcher = CHINESE_ORDINAL.matcher(input);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Integer number = parseChineseNumber(matcher.group(1));
            if (number != null) {
                matcher.appendReplacement(result, "第" + number + matcher.group(2));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Integer parseChineseNumber(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        if ("十".equals(raw)) {
            return 10;
        }
        int result = 0;
        int current = 0;
        for (char c : raw.toCharArray()) {
            int digit = switch (c) {
                case '零' -> 0;
                case '一' -> 1;
                case '二', '两' -> 2;
                case '三' -> 3;
                case '四' -> 4;
                case '五' -> 5;
                case '六' -> 6;
                case '七' -> 7;
                case '八' -> 8;
                case '九' -> 9;
                default -> -1;
            };
            if (digit >= 0) {
                current = digit;
                continue;
            }
            if (c == '十' || c == '百') {
                int unit = c == '十' ? 10 : 100;
                if (current == 0 && result == 0 && unit == 10) {
                    current = 1;
                }
                result += current * unit;
                current = 0;
                continue;
            }
            return null;
        }
        result += current;
        return result > 0 ? result : null;
    }

    private String removeRedundantBilingualTitle(String input) {
        Matcher repeatedNumber = BILINGUAL_NUMBER_REPEAT.matcher(input);
        if (repeatedNumber.matches() && containsHan(repeatedNumber.group(1))) {
            return repeatedNumber.group(1).trim() + repeatedNumber.group(2);
        }
        if (!containsHan(input)) {
            return input;
        }
        Matcher boundary = SCRIPT_BOUNDARY.matcher(input);
        if (!boundary.find()) {
            return input;
        }
        String left = input.substring(0, boundary.start()).trim();
        String right = input.substring(boundary.start()).trim();
        if (left.equalsIgnoreCase(right)) {
            return left;
        }
        boolean leftHasHan = containsHan(left);
        boolean rightHasHan = containsHan(right);
        if (leftHasHan && rightHasHan) {
            return input;
        }
        if (leftHasHan && !rightHasHan) {
            return left;
        }
        if (!leftHasHan && rightHasHan) {
            return right;
        }
        return input;
    }

    private String removeSelfRepetition(String input) {
        if (input == null || input.length() < 2) {
            return input;
        }
        Matcher matcher = MONOLINGUAL_REPEAT.matcher(input);
        if (matcher.matches() && matcher.group(1).trim().length() > 1) {
            return matcher.group(1).trim();
        }
        return input;
    }

    private boolean containsHan(String input) {
        return input != null && input.codePoints()
                .anyMatch(codepoint -> Character.UnicodeScript.of(codepoint) == Character.UnicodeScript.HAN);
    }

    private String cleanAliasTitle(String value, String title, Integer season, String subtitle) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        Set<String> titleKeys = aliasComparisonKeys(title, season, subtitle);
        Set<String> aliases = new LinkedHashSet<>();
        for (String part : RELATION_SPLIT_PATTERN.split(standardize(value).replace('\\', '/'))) {
            String alias = SPACE_PATTERN.matcher(part).replaceAll(" ").trim();
            alias = EDGE_SYMBOLS.matcher(alias).replaceAll("");
            if (!StringUtils.hasText(alias)) {
                continue;
            }
            if (titleKeys.contains(aliasComparisonKey(alias))) {
                continue;
            }
            aliases.add(alias);
        }
        return aliases.isEmpty() ? null : String.join(", ", aliases);
    }

    private Set<String> aliasComparisonKeys(String title, Integer season, String subtitle) {
        Set<String> keys = new LinkedHashSet<>();
        addAliasComparisonKey(keys, title);
        if (StringUtils.hasText(title) && season != null && season > 0) {
            addAliasComparisonKey(keys, title + season);
            addAliasComparisonKey(keys, title + " " + season);
            addAliasComparisonKey(keys, title + " 第" + season + "季");
            addAliasComparisonKey(keys, title + "第" + season + "季");
        }
        if (StringUtils.hasText(title) && StringUtils.hasText(subtitle)) {
            addAliasComparisonKey(keys, title + " " + subtitle);
            addAliasComparisonKey(keys, title + subtitle);
            addAliasComparisonKey(keys, title + ": " + subtitle);
            addAliasComparisonKey(keys, title + " - " + subtitle);
        }
        if (StringUtils.hasText(title) && season != null && season > 0 && StringUtils.hasText(subtitle)) {
            addAliasComparisonKey(keys, title + " 第" + season + "季 " + subtitle);
            addAliasComparisonKey(keys, title + "第" + season + "季" + subtitle);
        }
        return keys;
    }

    private void addAliasComparisonKey(Set<String> keys, String value) {
        String normalized = aliasComparisonKey(value);
        if (!StringUtils.hasText(normalized)) {
            return;
        }
        keys.add(normalized);
        int colon = normalized.indexOf(':');
        if (colon > 0) {
            String mainTitle = normalized.substring(0, colon).trim();
            if (StringUtils.hasText(mainTitle)) {
                keys.add(mainTitle);
            }
        }
    }

    private String aliasComparisonKey(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = normalizeTitleSymbols(standardize(value));
        normalized = normalizeChineseOrdinals(normalized);
        return SPACE_PATTERN.matcher(normalized).replaceAll(" ").trim();
    }

    private String normalizeYear(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        Matcher matcher = YEAR_PATTERN.matcher(value);
        return matcher.find() ? matcher.group() : null;
    }

    private BigDecimal parseScore(JsonNode metadata, BigDecimal fallback) {
        JsonNode value = metadata.get("score");
        if (value == null || value.isNull()) {
            return fallback;
        }
        try {
            BigDecimal score = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText().trim());
            if (score.compareTo(BigDecimal.ZERO) < 0) {
                return fallback;
            }
            if (score.compareTo(new BigDecimal("10.0")) > 0) {
                return new BigDecimal("10.0");
            }
            return score;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Set<String> relationValues(JsonNode metadata, String... names) {
        return relationValues(metadata, 100, names);
    }

    private Set<String> relationValues(JsonNode metadata, int maxLength, String... names) {
        Set<String> values = new LinkedHashSet<>();
        for (String name : names) {
            JsonNode value = metadata.get(name);
            if (value != null && !value.isNull()) {
                collectRelationValues(value, values, maxLength);
            }
        }
        return values;
    }

    private void collectRelationValues(JsonNode node, Set<String> values, int maxLength) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectRelationValues(item, values, maxLength));
            return;
        }
        String text = node.isTextual() ? node.asText() : node.asText(node.toString());
        if (!StringUtils.hasText(text)) {
            return;
        }
        for (String part : RELATION_SPLIT_PATTERN.split(text)) {
            String value = trimToLength(standardize(part), maxLength);
            if (StringUtils.hasText(value)) {
                values.add(value);
            }
        }
    }

    private Set<String> trimRelationValues(Set<String> values, int maxLength) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return normalized;
        }
        values.forEach(value -> {
            String trimmed = trimToLength(standardize(value), maxLength);
            if (StringUtils.hasText(trimmed)) {
                normalized.add(trimmed);
            }
        });
        return normalized;
    }

    private String normalizeId(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength);
        }
        return normalized;
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private String standardize(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String normalized = CharTable.convert(value);
        normalized = HanLP.convertToSimplifiedChinese(normalized);
        if (StringUtils.hasText(normalized)) {
            normalized = ZhJpConverterUtil.toSimple(normalized);
        }
        return restoreAsciiLetterCase(normalized.trim(), value);
    }

    private String restoreAsciiLetterCase(String normalized, String original) {
        if (!StringUtils.hasText(normalized) || !StringUtils.hasText(original)) {
            return normalized;
        }
        String reference = Normalizer.normalize(original, Normalizer.Form.NFKC);
        StringBuilder restored = new StringBuilder(normalized.length());
        int referenceIndex = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (!isAsciiLetter(current)) {
                restored.append(current);
                continue;
            }
            int match = nextCaseReference(reference, referenceIndex, current);
            if (match >= 0) {
                char referenceChar = reference.charAt(match);
                restored.append(Character.isUpperCase(referenceChar)
                        ? Character.toUpperCase(current)
                        : Character.toLowerCase(current));
                referenceIndex = match + 1;
            } else {
                restored.append(current);
            }
        }
        return restored.toString();
    }

    private int nextCaseReference(String reference, int start, char target) {
        for (int i = start; i < reference.length(); i++) {
            char candidate = reference.charAt(i);
            if (isAsciiLetter(candidate)
                    && Character.toLowerCase(candidate) == Character.toLowerCase(target)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isAsciiLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    private record TitleSplit(String mainTitle, String subtitle) {
    }

    private record SeasonMatch(Integer season, int start, int end) {
    }

    private record TitleStructure(String mainTitle, Integer season, String subtitle) {
    }
}
