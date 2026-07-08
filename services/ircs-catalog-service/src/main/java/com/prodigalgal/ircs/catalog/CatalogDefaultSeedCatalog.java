package com.prodigalgal.ircs.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.normalization.StandardContentCategoryClassifier;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class CatalogDefaultSeedCatalog {

    private static final String COUNTRIES_PRESET = "presets/countries.json";
    private static final String LANGUAGES_PRESET = "presets/languages.json";
    private static final ObjectMapper PRESET_OBJECT_MAPPER = new ObjectMapper();
    private static final Map<String, String> CODE_TO_REGION = buildCodeToRegion();

    List<CategorySeed> categories() {
        return StandardContentCategoryClassifier.stableCategories().stream()
                .map(category -> new CategorySeed(category.name(), category.code()))
                .toList();
    }

    List<GenreSeed> genres() {
        return List.of(
                new GenreSeed("剧情"), new GenreSeed("动作"), new GenreSeed("喜剧"), new GenreSeed("爱情"),
                new GenreSeed("科幻"), new GenreSeed("恐怖"), new GenreSeed("惊悚"), new GenreSeed("犯罪"),
                new GenreSeed("战争"), new GenreSeed("奇幻"), new GenreSeed("冒险"), new GenreSeed("灾难"),
                new GenreSeed("悬疑"), new GenreSeed("动画"), new GenreSeed("纪录"), new GenreSeed("综艺"),
                new GenreSeed("伦理"), new GenreSeed("情色"), new GenreSeed("同性"), new GenreSeed("短剧"),
                new GenreSeed("家庭"), new GenreSeed("儿童"), new GenreSeed("音乐"), new GenreSeed("歌舞"),
                new GenreSeed("体育"), new GenreSeed("历史"), new GenreSeed("传记"), new GenreSeed("西部"),
                new GenreSeed("武侠"), new GenreSeed("古装"), new GenreSeed("女频"), new GenreSeed("男频"),
                new GenreSeed("逆袭"), new GenreSeed("霸总"), new GenreSeed("赘婿"), new GenreSeed("战神"),
                new GenreSeed("神医"), new GenreSeed("龙王"), new GenreSeed("兵王"), new GenreSeed("萌宝"),
                new GenreSeed("甜宠"), new GenreSeed("虐恋"), new GenreSeed("复仇"), new GenreSeed("爽文"),
                new GenreSeed("打脸"), new GenreSeed("快穿"), new GenreSeed("系统"), new GenreSeed("穿越"),
                new GenreSeed("重生"), new GenreSeed("空间"), new GenreSeed("种田"), new GenreSeed("宫斗"),
                new GenreSeed("宅斗"), new GenreSeed("权谋"), new GenreSeed("豪门"), new GenreSeed("总裁"),
                new GenreSeed("脑洞"), new GenreSeed("沙雕"), new GenreSeed("鉴宝"), new GenreSeed("透视"),
                new GenreSeed("神豪"), new GenreSeed("异能"), new GenreSeed("超能力"), new GenreSeed("后宫"),
                new GenreSeed("丧尸"), new GenreSeed("吸血鬼"), new GenreSeed("怪兽"), new GenreSeed("机甲"),
                new GenreSeed("赛博朋克"), new GenreSeed("末日"), new GenreSeed("废土"), new GenreSeed("盗墓"),
                new GenreSeed("探险"), new GenreSeed("求生"), new GenreSeed("荒岛"), new GenreSeed("越狱"),
                new GenreSeed("卧底"), new GenreSeed("间谍"), new GenreSeed("特工"), new GenreSeed("警匪"),
                new GenreSeed("黑帮"), new GenreSeed("刑侦"), new GenreSeed("探案"), new GenreSeed("律政"),
                new GenreSeed("医疗"), new GenreSeed("商战"), new GenreSeed("职场"), new GenreSeed("校园"),
                new GenreSeed("青春"), new GenreSeed("军旅"), new GenreSeed("抗战"), new GenreSeed("谍战"),
                new GenreSeed("民国"), new GenreSeed("农村"), new GenreSeed("乡村"), new GenreSeed("玄幻"),
                new GenreSeed("修真"), new GenreSeed("修仙"), new GenreSeed("仙侠"), new GenreSeed("神话"),
                new GenreSeed("魔幻"), new GenreSeed("异界"), new GenreSeed("鬼怪"), new GenreSeed("灵异"),
                new GenreSeed("篮球"), new GenreSeed("足球"), new GenreSeed("网球"), new GenreSeed("赛车"),
                new GenreSeed("斯诺克"), new GenreSeed("电竞"), new GenreSeed("拳击"), new GenreSeed("武术"),
                new GenreSeed("真人秀"), new GenreSeed("脱口秀"), new GenreSeed("访谈"), new GenreSeed("选秀"),
                new GenreSeed("相声"), new GenreSeed("小品"), new GenreSeed("美食"), new GenreSeed("旅游"),
                new GenreSeed("生活"), new GenreSeed("时尚"), new GenreSeed("文化"), new GenreSeed("百科"),
                new GenreSeed("少儿"), new GenreSeed("晚会"), new GenreSeed("微电影"), new GenreSeed("短片"),
                new GenreSeed("韩剧"), new GenreSeed("美剧"), new GenreSeed("日剧"), new GenreSeed("港剧"),
                new GenreSeed("台剧"), new GenreSeed("泰剧"), new GenreSeed("英剧"), new GenreSeed("内地剧"),
                new GenreSeed("国产剧"));
    }

    List<AreaSeed> areas() {
        List<AreaSeed> presetAreas = loadCountryPresets();
        if (!presetAreas.isEmpty()) {
            return presetAreas;
        }
        return fallbackAreas();
    }

    List<LanguageSeed> languages() {
        List<LanguageSeed> presetLanguages = loadLanguagePresets();
        if (!presetLanguages.isEmpty()) {
            return presetLanguages;
        }
        return fallbackLanguages();
    }

    private List<AreaSeed> loadCountryPresets() {
        ClassPathResource resource = new ClassPathResource(COUNTRIES_PRESET);
        if (!resource.exists()) {
            return List.of();
        }
        try (InputStream input = resource.getInputStream()) {
            List<CountryPreset> presets = PRESET_OBJECT_MAPPER.readValue(input, new TypeReference<>() {});
            Map<String, AreaSeed> byCode = new LinkedHashMap<>();
            for (CountryPreset preset : presets) {
                if (!StringUtils.hasText(preset.isoCode())) {
                    continue;
                }
                String code = preset.isoCode().trim().toUpperCase();
                String name = StringUtils.hasText(preset.nativeName()) ? preset.nativeName().trim() : trim(preset.englishName());
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                byCode.putIfAbsent(code, new AreaSeed(code, name, CODE_TO_REGION.getOrDefault(code, "Unknown")));
            }
            return new ArrayList<>(byCode.values());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load catalog country presets from " + COUNTRIES_PRESET, ex);
        }
    }

    private List<LanguageSeed> loadLanguagePresets() {
        ClassPathResource resource = new ClassPathResource(LANGUAGES_PRESET);
        if (!resource.exists()) {
            return List.of();
        }
        try (InputStream input = resource.getInputStream()) {
            List<LanguagePreset> presets = PRESET_OBJECT_MAPPER.readValue(input, new TypeReference<>() {});
            Map<String, LanguageSeed> byCode = new LinkedHashMap<>();
            for (LanguagePreset preset : presets) {
                if (!StringUtils.hasText(preset.code()) || !StringUtils.hasText(preset.cnName())) {
                    continue;
                }
                String code = preset.code().trim();
                byCode.putIfAbsent(code, new LanguageSeed(
                        code,
                        preset.cnName().trim(),
                        trim(preset.englishName()),
                        trim(preset.nativeName())));
            }
            return new ArrayList<>(byCode.values());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load catalog language presets from " + LANGUAGES_PRESET, ex);
        }
    }

    private List<AreaSeed> fallbackAreas() {
        return List.of(
                new AreaSeed("CN", "中国", "Asia"),
                new AreaSeed("HK", "中国香港特别行政区", "Asia"),
                new AreaSeed("TW", "台湾", "Asia"),
                new AreaSeed("MO", "中国澳门特别行政区", "Asia"),
                new AreaSeed("JP", "日本", "Asia"),
                new AreaSeed("KR", "韩国", "Asia"),
                new AreaSeed("US", "美国", "Americas"),
                new AreaSeed("CA", "加拿大", "Americas"),
                new AreaSeed("GB", "英国", "Europe"),
                new AreaSeed("AU", "澳大利亚", "Oceania"),
                new AreaSeed("SG", "新加坡", "Asia"),
                new AreaSeed("MY", "马来西亚", "Asia"),
                new AreaSeed("ID", "印度尼西亚", "Asia"),
                new AreaSeed("TH", "泰国", "Asia"),
                new AreaSeed("VN", "越南", "Asia"),
                new AreaSeed("FR", "法国", "Europe"),
                new AreaSeed("DE", "德国", "Europe"),
                new AreaSeed("ES", "西班牙", "Europe"),
                new AreaSeed("IT", "意大利", "Europe"),
                new AreaSeed("RU", "俄罗斯", "Europe"),
                new AreaSeed("IN", "印度", "Asia"));
    }

    private List<LanguageSeed> fallbackLanguages() {
        return List.of(
                new LanguageSeed("zh", "普通话", "Mandarin", "普通话"),
                new LanguageSeed("cn", "粤语", "Cantonese", "广州话 / 廣州話"),
                new LanguageSeed("en", "英语", "English", "English"),
                new LanguageSeed("ja", "日语", "Japanese", "日本語"),
                new LanguageSeed("ko", "韩语", "Korean", "한국어/조선말"),
                new LanguageSeed("th", "泰语", "Thai", "ภาษาไทย"),
                new LanguageSeed("vi", "越南语", "Vietnamese", "Tiếng Việt"),
                new LanguageSeed("ru", "俄语", "Russian", "Pусский"),
                new LanguageSeed("fr", "法语", "French", "Français"),
                new LanguageSeed("de", "德语", "German", "Deutsch"),
                new LanguageSeed("es", "西班牙语", "Spanish", "Español"),
                new LanguageSeed("it", "意大利语", "Italian", "Italiano"),
                new LanguageSeed("pt", "葡萄牙语", "Portuguese", "Português"),
                new LanguageSeed("id", "印尼语", "Indonesian", "Bahasa indonesia"),
                new LanguageSeed("ms", "马来语", "Malay", "Bahasa melayu"),
                new LanguageSeed("hi", "印地语", "Hindi", "हिन्दी"),
                new LanguageSeed("xx", "无语言", "No Language", "No Language"));
    }

    Map<String, List<String>> languageAliases() {
        return Map.ofEntries(
                Map.entry("zh", List.of("汉语", "国语", "普通话", "华语", "中文", "Chinese", "Mandarin", "Putonghua",
                        "四川话", "河南方言", "东北话", "中", "国")),
                Map.entry("cn", List.of("粤语", "广东话", "白话", "Cantonese", "Can", "粤", "港")),
                Map.entry("en", List.of("English", "Eng", "美语", "英文", "英", "美")),
                Map.entry("ja", List.of("Japanese", "Jap", "日文", "日本", "日")),
                Map.entry("ko", List.of("Korean", "Kor", "韩文", "朝鲜语", "韩")),
                Map.entry("th", List.of("Thai", "Tha", "泰文", "泰")),
                Map.entry("vi", List.of("Vietnamese", "Viet", "越语", "越")),
                Map.entry("ru", List.of("Russian", "Rus", "俄文", "俄")),
                Map.entry("fr", List.of("French", "Fre", "法文", "法")),
                Map.entry("de", List.of("German", "Ger", "德文", "德")),
                Map.entry("es", List.of("Spanish", "Spa", "西语", "西班牙文", "西班", "西")),
                Map.entry("it", List.of("Italian", "Ita", "意语", "意大", "意")),
                Map.entry("pt", List.of("Portuguese", "Por", "葡语", "葡萄", "葡")),
                Map.entry("id", List.of("印度尼西亚语", "印度尼西", "印尼")),
                Map.entry("ms", List.of("马来西亚语", "马来")),
                Map.entry("hi", List.of("Hindi", "印地", "印")),
                Map.entry("xx", List.of("其它", "其他", "unknown", "暂无")));
    }

    Map<String, List<String>> areaAliases() {
        return Map.ofEntries(
                Map.entry("CN", List.of("中国", "大陆", "内地", "中国大陆", "国产", "华语")),
                Map.entry("HK", List.of("香港", "中国香港", "港", "港剧")),
                Map.entry("TW", List.of("台湾", "中国台湾", "台", "台剧")),
                Map.entry("MO", List.of("澳门", "中国澳门")),
                Map.entry("JP", List.of("日本", "日", "日剧")),
                Map.entry("KR", List.of("韩国", "韩", "韩剧")),
                Map.entry("US", List.of("美国", "欧美", "美", "美剧")),
                Map.entry("CA", List.of("加拿大", "加")),
                Map.entry("GB", List.of("英国", "英", "英剧")),
                Map.entry("AU", List.of("澳大利亚", "澳洲", "澳")),
                Map.entry("SG", List.of("新加坡", "新")),
                Map.entry("MY", List.of("马来西亚", "马来")),
                Map.entry("ID", List.of("印度尼西亚", "印尼")),
                Map.entry("TH", List.of("泰国", "泰", "泰剧")),
                Map.entry("VN", List.of("越南", "越")),
                Map.entry("FR", List.of("法国", "法")),
                Map.entry("DE", List.of("德国", "德")),
                Map.entry("ES", List.of("西班牙", "西")),
                Map.entry("IT", List.of("意大利", "意")),
                Map.entry("RU", List.of("俄罗斯", "俄")),
                Map.entry("IN", List.of("印度", "印")));
    }

    String defaultListParams() {
        return """
                { "ac": "list", "pg": "{page}" }
                """;
    }

    String defaultDetailParams() {
        return """
                { "ac": "detail", "ids": "{ids}" }
                """;
    }

    String defaultFieldMapping() {
        return """
                {
                  "list_mapping": {
                    "items_path": "$.list",
                    "pagination": {
                      "total_items_path": "$.total",
                      "total_pages_path": "$.pagecount"
                    },
                    "title_path": "$.list[0].vod_name",
                    "primary_id_path": "$.list[0].vod_id",
                    "update_time_path": "$.list[0].vod_time"
                  },
                  "detail_mapping": {
                    "source_vid": { "path": "$.list[0].vod_id" },
                    "title": { "path": "$.list[0].vod_name" },
                    "aliasTitle": { "path": "$.list[0].vod_sub" },
                    "coverImageUrl": { "path": "$.list[0].vod_pic" },
                    "description": { "path": "$.list[0].vod_content" },
                    "year": { "path": "$.list[0].vod_year" },
                    "area": { "path": "$.list[0].vod_area" },
                    "language": { "path": "$.list[0].vod_lang" },
                    "remarks": { "path": "$.list[0].vod_remarks" },
                    "score": { "path": "$.list[0].vod_score" },
                    "totalEpisodes": { "path": "$.list[0].vod_total" },
                    "duration": { "path": "$.list[0].vod_duration" },
                    "publishedAt": { "path": "$.list[0].vod_pubdate" },
                    "actors": { "path": "$.list[0].vod_actor" },
                    "directors": { "path": "$.list[0].vod_director" },
                    "genres": { "path": "$.list[0].vod_class" },
                    "categoryName": { "path": "$.list[0].type_name" },
                    "typeId": { "path": "$.list[0].type_id" },
                    "playlist_from": { "path": "$.list[0].vod_play_from" },
                    "playlist_url": { "path": "$.list[0].vod_play_url" },
                    "doubanId": { "path": "$.list[0].vod_douban_id" }
                  }
                }
                """;
    }

    List<DataSourceSeed> dataSources() {
        return List.of(
                new DataSourceSeed("光速资源", "https://api.guangsuapi.com", "/api.php/provide/vod/josn"),
                new DataSourceSeed("速播资源", "https://subocj.com", "/api.php/provide/vod/at/json"),
                new DataSourceSeed("新浪资源", "https://api.xinlangapi.com", "/xinlangapi.php/provide/vod/josn"),
                new DataSourceSeed("优质资源站", "https://api.yzzy-api.com", "/inc/apijson.php"),
                new DataSourceSeed("淘片资源", "https://taopianapi.com", "/cjapi/mc10/vod/json.html"),
                new DataSourceSeed("无尽资源", "https://api.wujinapi.me", "/api.php/provide/vod/from/wjm3u8/at/json"),
                new DataSourceSeed("牛牛资源", "https://api.niuniuzy.me", "/api.php/provide/vod/from/nnm3u8/at/json"),
                new DataSourceSeed("iKun资源", "https://ikunzyapi.com", "/api.php/provide/vod"),
                new DataSourceSeed("魔都资源", "https://www.mdzyapi.com", "/api.php/provide/vod"),
                new DataSourceSeed("金鹰资源", "https://jyzyapi.com", "/provide/vod"),
                new DataSourceSeed("奥斯卡资源", "https://aosikazy.com", "/api.php/provide/vod"),
                new DataSourceSeed("暴风资源", "https://bfzyapi.com"),
                new DataSourceSeed("茅台资源", "https://mtzy0.com"),
                new DataSourceSeed("豆瓣资源", "https://dbzy.tv"),
                new DataSourceSeed("虎牙资源", "https://huyazy.com"),
                new DataSourceSeed("豪华资源", "https://haohuazy.com"),
                new DataSourceSeed("爱奇艺影视", "https://iqiyizy.com"),
                new DataSourceSeed("猫眼资源", "https://www.maoyanzy.com"),
                new DataSourceSeed("如意资源", "https://www.ryzy.tv"),
                new DataSourceSeed("旺旺资源", "https://tyyszy.com"),
                new DataSourceSeed("电影天堂资源", "http://dyttzy.tv"),
                new DataSourceSeed("卧龙资源", "https://wolongzyw.com"),
                new DataSourceSeed("索尼资源", "https://suonizy.com"),
                new DataSourceSeed("极速资源", "https://jszyapi.com"),
                new DataSourceSeed("非凡资源", "http://api.ffzyapi.com"));
    }

    private static Map<String, String> buildCodeToRegion() {
        Map<String, String> regions = new LinkedHashMap<>();
        registerRegion(regions, "Asia", "CN", "HK", "TW", "MO", "JP", "KR", "KP", "IN", "TH", "VN", "PH",
                "SG", "MY", "ID", "IR", "IL", "TR", "PK", "SA", "AE", "KZ", "MN", "KH", "LB", "QA",
                "NP", "BD", "LK", "IQ", "JO", "KG", "AF", "AM", "BT", "LA", "MM", "TJ", "UZ", "YE",
                "SY", "BH", "KW", "PS");
        registerRegion(regions, "Europe", "GB", "FR", "DE", "IT", "ES", "PT", "RU", "NL", "SE", "NO",
                "DK", "FI", "BE", "CH", "PL", "IE", "AT", "GR", "CZ", "HU", "UA", "RO", "BG", "IS",
                "LU", "MC", "MT", "EE", "LV", "LT", "BY", "RS", "HR", "SK", "SI", "BA", "ME", "AL",
                "MK", "LI", "MD", "VA", "GE", "FO", "GI", "CY", "SU", "CS", "YU", "XG", "XC");
        registerRegion(regions, "Americas", "US", "CA", "BR", "MX", "AR", "CL", "CO", "PE", "CU", "UY",
                "VE", "PA", "PR", "DO", "CR", "GT", "HN", "JM", "BS", "BO", "EC", "PY", "HT", "KY",
                "KN", "TT", "VG", "NI", "BB", "AW", "GL");
        registerRegion(regions, "Oceania", "AU", "NZ", "FJ", "NC", "PF", "VU", "PG", "WS", "TO", "SB");
        registerRegion(regions, "Africa", "ZA", "EG", "NG", "KE", "MA", "ET", "GH", "CM", "SN", "DZ",
                "TN", "TZ", "LY", "ZM", "CG", "BJ", "BW", "BF", "CI", "ML", "MW", "MG", "TD", "LR",
                "SD", "MZ", "NA", "GN", "MR", "MU", "GQ", "RW", "UG", "AO", "ZW", "SO", "CD");
        return Map.copyOf(regions);
    }

    private static void registerRegion(Map<String, String> regions, String region, String... codes) {
        for (String code : codes) {
            regions.put(code, region);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    record CategorySeed(String name, String slug) {}

    record GenreSeed(String name) {}

    record AreaSeed(String code, String name, String region) {}

    record LanguageSeed(String code, String name, String englishName, String nativeName) {}

    record DataSourceSeed(String name, String baseUrl, String apiPath) {

        DataSourceSeed(String name, String baseUrl) {
            this(name, baseUrl, "/api.php/provide/vod/");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CountryPreset(
            @JsonProperty("iso_3166_1") String isoCode,
            @JsonProperty("english_name") String englishName,
            @JsonProperty("native_name") String nativeName) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LanguagePreset(
            @JsonProperty("iso_639_1") String code,
            @JsonProperty("english_name") String englishName,
            @JsonProperty("native_name") String nativeName,
            @JsonProperty("cn_name") String cnName) {}
}
