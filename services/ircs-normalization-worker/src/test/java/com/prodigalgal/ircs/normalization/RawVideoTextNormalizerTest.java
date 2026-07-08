package com.prodigalgal.ircs.normalization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RawVideoTextNormalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RawVideoTextNormalizer normalizer = defaultNormalizer();

    @Test
    void respectsLockedTitleAndNormalizesOtherFields() throws Exception {
        RawVideoRecord record = record("[\"title\"]", "Manual Fixed Title", null, null);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "title": "Dirty Title 2026 1080p WEB-DL",
                  "aliasTitle": "Alias / Other",
                  "year": "2026-06",
                  "score": "8.7",
                  "language": "zh-CN"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals("Manual Fixed Title", patch.title());
        assertEquals("Alias, Other", patch.aliasTitle());
        assertEquals("2026", patch.year());
        assertEquals(new BigDecimal("8.7"), patch.score());
        assertEquals("zh-CN", patch.rawLanguageStr());
    }

    @Test
    void extractsRawRelationValuesFromArraysAndDelimitedText() throws Exception {
        UUID dataSourceId = UUID.randomUUID();
        RawVideoRecord record = record("[]", "Title", null, dataSourceId);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "genreNames": ["动作", " 科幻 ", "动作"],
                  "language": "国语/英语",
                  "area": "中国大陆, 美国",
                  "actorNames": ["演员甲", " 演员乙 ", "演员甲"],
                  "directorNames": "导演甲/导演乙",
                  "rawTypeId": "movie",
                  "rawTypeName": "电影"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals(Set.of("动作", "科幻"), patch.rawGenreValues());
        assertEquals(Set.of("国语", "英语"), patch.rawLanguageValues());
        assertEquals(Set.of("中国大陆", "美国"), patch.rawAreaValues());
        assertEquals(Set.of("演员甲", "演员乙"), patch.actorValues());
        assertEquals(Set.of("导演甲", "导演乙"), patch.directorValues());
        assertEquals(dataSourceId, patch.rawCategoryDataSourceId());
        assertEquals("movie", patch.rawCategorySourceCode());
        assertEquals("电影", patch.rawCategorySourceName());
    }

    @Test
    void cleansDescriptionHtmlToPlainText() throws Exception {
        RawVideoRecord record = record("[]", "Title", null, null);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "description": "<p>第一段&nbsp;<strong>重点</strong></p><script>alert(1)</script><br/>第二段&#65281;"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals("第一段 重点 第二段！", patch.description());
    }

    @Test
    void sourceCategoryFeedsSixTopCategoryAndGenreCleaningSeparately() throws Exception {
        UUID dataSourceId = UUID.randomUUID();
        RawVideoRecord record = record("[]", "Title", null, dataSourceId);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "rawTypeId": "11",
                  "rawTypeName": "剧情片"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals("11", patch.rawCategorySourceCode());
        assertEquals("剧情片", patch.rawCategorySourceName());
        assertEquals(Set.of("剧情片", "剧情"), patch.rawGenreValues());
    }

    @Test
    void standardizesFullWidthAndTraditionalText() throws Exception {
        RawVideoRecord record = record("[]", "Title", null, null);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "title": "ＡＢＣ　１２３ 測試繁體轉簡體 2026 1080p",
                  "year": "2026",
                  "language": "國語/英語",
                  "area": "中國香港"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals("ABC 123 测试繁体转简体", patch.title());
        assertEquals(Set.of("国语", "英语"), patch.rawLanguageValues());
        assertEquals(Set.of("中国香港", "香港"), patch.rawAreaValues());
    }

    @Test
    void expandsV1AreaAndLanguageCompositeAliasesButKeepsRawTokens() throws Exception {
        RawVideoRecord record = record("[]", "Title", null, null);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "language": "国粤双语/日语中字",
                  "area": "港台, 日韩, 日本地区, 东京电影节"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals(Set.of("国粤双语", "国语", "粤语", "日语中字", "日语"), patch.rawLanguageValues());
        assertEquals(
                Set.of("港台", "香港", "台湾", "日韩", "日本", "韩国", "日本地区", "东京电影节"),
                patch.rawAreaValues());
    }

    @Test
    void expandsV1GenreSynonymsAndSuffixesButKeepsProtectedTerms() throws Exception {
        RawVideoRecord record = record("[]", "Title", null, null);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "genreNames": "古装剧/都市剧/悬疑片/纪录片/纪录/微短剧/动漫/短剧"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals(
                Set.of(
                        "古装剧", "古装",
                        "都市剧", "都市",
                        "悬疑片", "悬疑",
                        "纪录片", "纪录",
                        "微短剧", "动漫", "动画", "短剧"),
                patch.rawGenreValues());
    }

    @Test
    void expandsAdditionalGenreNlpSynonymsWithoutDroppingRawTokens() throws Exception {
        RawVideoRecord record = record("[]", "Title", null, null);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "genreNames": "推理/惊栗/动画片/卡通/综艺节目/伦理片/情色片"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals(
                Set.of(
                        "推理", "悬疑",
                        "惊栗", "惊悚",
                        "动画片", "动画",
                        "卡通",
                        "综艺节目", "综艺",
                        "伦理片", "伦理",
                        "情色片", "情色"),
                patch.rawGenreValues());
    }

    @Test
    void lockedGenreFieldSuppressesGenreAliasExpansion() throws Exception {
        RawVideoRecord record = record("[\"genres\"]", "Title", null, null);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "genreNames": "古装剧/悬疑片/微短剧"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals(Set.of(), patch.rawGenreValues());
    }

    @Test
    void cleansBilingualAndReleaseNoiseButKeepsNumberSensitiveTitles() throws Exception {
        assertEquals("钢铁侠", normalizedTitle("钢铁侠3 Iron Man 3 2023 1080p WEB-DL", "2023"));
        assertEquals("高达00", normalizedTitle("高达00 2023 1080p", "2023"));
        assertEquals("洛克王国", normalizedTitle("洛克王国4 2023 1080p", "2023"));
        assertEquals("Title", normalizedTitle("Title (2023).mkv 1080p", "2023"));
        assertEquals("庆余年", normalizedTitle("庆余年 第二季 2024 1080p", "2024"));
    }

    @Test
    void removesV1NoiseBracketsButKeepsUsefulBracketContent() throws Exception {
        assertEquals(
                "电影名",
                normalizedTitle("电影名 (国语中字) [导演剪辑版] 2026 1080p WEB-DL", "2026"));
    }

    @Test
    void aliasTitleRemovesMainTitleAndDeduplicatesItems() throws Exception {
        RawVideoRecord record = record("[]", "庆余年 第二季 2024 1080p", "2024", null);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "title": "庆余年 第二季 2024 1080p WEB-DL",
                  "year": "2024",
                  "aliasTitle": "庆余年 第2季 / Joy of Life 2 / Joy of Life 2 / 慶餘年 第二季"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals("庆余年", patch.title());
        assertEquals("Joy of Life 2", patch.aliasTitle());
    }

    @Test
    void lockedAliasTitlePreservesRawValue() throws Exception {
        RawVideoRecord record = recordWithOverrides(
                "[\"aliasTitle\"]",
                "庆余年 第二季 2024 1080p",
                "庆余年 第2季 / Joy of Life 2 / Joy of Life 2",
                null,
                null,
                "2024",
                null,
                null);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "title": "庆余年 第二季 2024 1080p WEB-DL",
                  "year": "2024",
                  "aliasTitle": "庆余年 第2季 / Joy of Life 2"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals("庆余年", patch.title());
        assertEquals("庆余年 第2季 / Joy of Life 2 / Joy of Life 2", patch.aliasTitle());
    }

    @Test
    void extractsChineseSeasonFromTitleWithoutRegressingAliasDedup() throws Exception {
        RawVideoRecord record = record("[]", "庆余年 第二季 2024 1080p", "2024", null);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "title": "庆余年 第二季 2024 1080p WEB-DL",
                  "year": "2024",
                  "aliasTitle": "庆余年 第2季 / Joy of Life 2 / Joy of Life 2"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals("庆余年", patch.title());
        assertEquals(2, patch.season());
        assertNull(patch.subtitle());
        assertEquals("Joy of Life 2", patch.aliasTitle());
    }

    @Test
    void extractsEnglishColonSubtitleAndSeasonWhenExplicitMetadataIsMissing() throws Exception {
        RawVideoRecord record = record("[]", "The Last Kingdom: Seven Kings Must Die Season 2 2026", "2026", null);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "title": "The Last Kingdom: Seven Kings Must Die Season 2 2026 WEB-DL",
                  "year": "2026",
                  "aliasTitle": "The Last Kingdom"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals("The Last Kingdom", patch.title());
        assertEquals(2, patch.season());
        assertEquals("Seven Kings Must Die", patch.subtitle());
        assertNull(patch.aliasTitle());
    }

    @Test
    void extractsStrictSeasonFormatsAndPreservesPartVolumeTitles() throws Exception {
        assertEquals("Spy Family", normalizedTitle("[VCB-Studio] Spy Family S02 2026 1080p", "2026"));

        RawVideoRecord partRecord = record("[]", "Dune Part II 2026", "2026", null);
        JsonNode partMetadata = objectMapper.readTree("""
                {
                  "title": "[Group] Dune Part II 2026 WEB-DL",
                  "year": "2026"
                }
        """);
        RawVideoPatch partPatch = normalizer.normalize(partRecord, partMetadata);
        assertEquals("Dune Part II", partPatch.title());
        assertNull(partPatch.season());

        RawVideoRecord volumeRecord = record("[]", "Monogatari Vol.2 2026", "2026", null);
        JsonNode volumeMetadata = objectMapper.readTree("""
                {
                  "title": "Monogatari Vol.2 2026 WEB-DL",
                  "year": "2026"
                }
                """);
        RawVideoPatch volumePatch = normalizer.normalize(volumeRecord, volumeMetadata);
        assertEquals("Monogatari Vol.2", volumePatch.title());
        assertNull(volumePatch.season());
    }

    @Test
    void completePipelineNormalizesTitleAliasLocationLanguageGenreAndStructure() throws Exception {
        RawVideoRecord record = record("[]", "庆余年 第二季：特别篇 2026 1080p", "2026", null);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "title": "庆余年 第二季：特别篇 2026 1080p WEB-DL",
                  "year": "2026",
                  "aliasTitle": "庆余年 第2季 / Joy of Life 2 / Joy of Life 2 / 慶餘年 第二季",
                  "language": "国粤双语/日语中字",
                  "area": "港台,日本地区",
                  "genreNames": "纪录片/微短剧/短剧/古装剧"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals("庆余年", patch.title());
        assertEquals("Joy of Life 2", patch.aliasTitle());
        assertEquals(2, patch.season());
        assertEquals("特别篇", patch.subtitle());
        assertEquals(Set.of("国粤双语", "国语", "粤语", "日语中字", "日语"), patch.rawLanguageValues());
        assertEquals(Set.of("港台", "香港", "台湾", "日本地区", "日本"), patch.rawAreaValues());
        assertEquals(Set.of("纪录片", "纪录", "微短剧", "短剧", "古装剧", "古装"), patch.rawGenreValues());
    }

    @Test
    void lockedSeasonAndSubtitlePreserveRawRecordValues() throws Exception {
        RawVideoRecord record = recordWithOverrides(
                "[\"season\",\"subtitle\"]",
                "Raw Title",
                null,
                1,
                "人工副标题",
                "2024",
                null,
                null);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "title": "庆余年 第2季：特别篇 2024",
                  "year": "2024",
                  "season": 2,
                  "subtitle": "特别篇"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals(1, patch.season());
        assertEquals("人工副标题", patch.subtitle());
    }

    @Test
    void completePipelineLocksPreserveScalarsAndSuppressRawRelationExpansion() throws Exception {
        RawVideoRecord record = recordWithOverrides(
                "[\"title\",\"aliasTitle\",\"season\",\"subtitle\",\"area\",\"language\",\"genres\"]",
                "人工标题",
                "人工别名",
                9,
                "人工副标题",
                "2024",
                "人工地区",
                "人工语言");
        JsonNode metadata = objectMapper.readTree("""
                {
                  "title": "庆余年 第二季：特别篇 2026 1080p WEB-DL",
                  "year": "2026",
                  "aliasTitle": "庆余年 第2季 / Joy of Life 2",
                  "season": 2,
                  "subtitle": "特别篇",
                  "language": "国粤双语/日语中字",
                  "area": "港台,日本地区",
                  "genreNames": "纪录片/微短剧/古装剧"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals("人工标题", patch.title());
        assertEquals("人工别名", patch.aliasTitle());
        assertEquals(9, patch.season());
        assertEquals("人工副标题", patch.subtitle());
        assertEquals("人工地区", patch.area());
        assertEquals("人工语言", patch.rawLanguageStr());
        assertEquals(Set.of(), patch.rawGenreValues());
        assertEquals(Set.of(), patch.rawLanguageValues());
        assertEquals(Set.of(), patch.rawAreaValues());
    }

    @Test
    void lockedRawRelationFieldsAreNotExtracted() throws Exception {
        UUID dataSourceId = UUID.randomUUID();
        RawVideoRecord record = record(
                "[\"genres\",\"language\",\"area\",\"actors\",\"directors\",\"category\"]",
                "Title",
                null,
                dataSourceId);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "genreNames": ["动作"],
                  "language": "国语/英语",
                  "area": "中国大陆, 美国",
                  "actorNames": ["演员甲"],
                  "directorNames": ["导演甲"],
                  "rawTypeId": "movie",
                  "rawTypeName": "电影"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals(Set.of(), patch.rawGenreValues());
        assertEquals(Set.of(), patch.rawLanguageValues());
        assertEquals(Set.of(), patch.rawAreaValues());
        assertEquals(Set.of(), patch.actorValues());
        assertEquals(Set.of(), patch.directorValues());
        assertNull(patch.rawCategoryDataSourceId());
        assertNull(patch.rawCategorySourceCode());
        assertNull(patch.rawCategorySourceName());
    }

    @Test
    void categoryFallbackUsesGenericCategoryFieldsWhenRawTypeIsMissing() throws Exception {
        UUID dataSourceId = UUID.randomUUID();
        RawVideoRecord record = record("[]", "Title", null, dataSourceId);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "category": "纪录片"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals(dataSourceId, patch.rawCategoryDataSourceId());
        assertEquals("纪录片", patch.rawCategorySourceCode());
        assertEquals("纪录片", patch.rawCategorySourceName());
    }

    @Test
    void expandsAdditionalV1AreaCompositesAliasesAndGenreSynonyms() throws Exception {
        RawVideoRecord record = record("[]", "Title", null, null);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "area": "大陆/新马泰/英美/hk",
                  "genreNames": "武打/正剧/二次元/纪实/烧脑/惊险"
                }
                """);

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertEquals(
                Set.of(
                        "大陆", "中国大陆",
                        "新马泰", "新加坡", "马来西亚", "泰国",
                        "英美", "英国", "美国",
                        "hk", "香港"),
                patch.rawAreaValues());
        assertEquals(
                Set.of(
                        "武打", "动作",
                        "正剧", "剧情",
                        "二次元", "动画",
                        "纪实", "纪录",
                        "烧脑", "悬疑",
                        "惊险", "惊悚"),
                patch.rawGenreValues());
    }

    @Test
    void usesDescriptionNerWhenExplicitPeopleMetadataIsMissing() throws Exception {
        RawVideoTextNormalizer localNormalizer = new RawVideoTextNormalizer(
                objectMapper,
                extractor(Set.of("演员甲", "演员乙"), Set.of("导演甲")),
                new RawRelationAliasPolicy());
        RawVideoRecord record = record("[]", "Title", null, null, "本片由导演甲执导，演员甲、演员乙主演。");
        JsonNode metadata = objectMapper.readTree("{}");

        RawVideoPatch patch = localNormalizer.normalize(record, metadata);

        assertEquals(Set.of("演员甲", "演员乙"), patch.actorValues());
        assertEquals(Set.of("导演甲"), patch.directorValues());
    }

    @Test
    void explicitPeopleMetadataHasPriorityOverDescriptionNer() throws Exception {
        RawVideoTextNormalizer localNormalizer = new RawVideoTextNormalizer(
                objectMapper,
                extractor(Set.of("简介演员"), Set.of("简介导演")),
                new RawRelationAliasPolicy());
        RawVideoRecord record = record("[]", "Title", null, null, "简介里还有人员信息");
        JsonNode metadata = objectMapper.readTree("""
                {
                  "actorNames": ["显式演员"],
                  "directorNames": ["显式导演"]
                }
                """);

        RawVideoPatch patch = localNormalizer.normalize(record, metadata);

        assertEquals(Set.of("显式演员"), patch.actorValues());
        assertEquals(Set.of("显式导演"), patch.directorValues());
    }

    @Test
    void lockedPeopleFieldsSuppressDescriptionNerFallback() throws Exception {
        RawVideoTextNormalizer localNormalizer = new RawVideoTextNormalizer(
                objectMapper,
                extractor(Set.of("简介演员"), Set.of("简介导演")),
                new RawRelationAliasPolicy());
        RawVideoRecord record = record(
                "[\"actors\",\"directors\"]",
                "Title",
                null,
                null,
                "简介里还有人员信息");
        JsonNode metadata = objectMapper.readTree("{}");

        RawVideoPatch patch = localNormalizer.normalize(record, metadata);

        assertEquals(Set.of(), patch.actorValues());
        assertEquals(Set.of(), patch.directorValues());
    }

    @Test
    void invalidYearBecomesNullWhenUnlocked() throws Exception {
        RawVideoRecord record = record("[]", "Title", "2024", null);
        JsonNode metadata = objectMapper.readTree("{\"year\":\"unknown\"}");

        RawVideoPatch patch = normalizer.normalize(record, metadata);

        assertNull(patch.year());
    }

    private RawVideoRecord record(String lockedFields, String title, String year, UUID dataSourceId) {
        return record(lockedFields, title, year, dataSourceId, null);
    }

    private RawVideoRecord record(
            String lockedFields,
            String title,
            String year,
            UUID dataSourceId,
            String description) {
        return new RawVideoRecord(
                UUID.randomUUID(),
                "PENDING",
                0,
                "{}",
                lockedFields,
                title,
                null,
                null,
                null,
                description,
                year,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                dataSourceId,
                "hash-v1");
    }

    private RawVideoRecord recordWithOverrides(
            String lockedFields,
            String title,
            String aliasTitle,
            Integer season,
            String subtitle,
            String year,
            String area,
            String language) {
        return new RawVideoRecord(
                UUID.randomUUID(),
                "PENDING",
                0,
                "{}",
                lockedFields,
                title,
                aliasTitle,
                season,
                subtitle,
                null,
                year,
                area,
                language,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "hash-v1");
    }

    private DescriptionMetadataExtractor extractor(Set<String> actors, Set<String> directors) {
        return new DescriptionMetadataExtractor() {
            @Override
            public ExtractedMetadata extract(String description) {
                return new ExtractedMetadata(actors, directors);
            }
        };
    }

    private RawVideoTextNormalizer defaultNormalizer() {
        return new RawVideoTextNormalizer(
                objectMapper,
                new DescriptionMetadataExtractor(),
                new RawRelationAliasPolicy());
    }

    private String normalizedTitle(String title, String year) throws Exception {
        RawVideoRecord record = record("[]", title, year, null);
        JsonNode metadata = objectMapper.readTree("""
                {
                  "title": %s,
                  "year": %s
                }
                """.formatted(objectMapper.writeValueAsString(title), objectMapper.writeValueAsString(year)));
        return normalizer.normalize(record, metadata).title();
    }
}
