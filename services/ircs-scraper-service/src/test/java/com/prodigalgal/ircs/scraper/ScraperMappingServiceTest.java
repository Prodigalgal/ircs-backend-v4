package com.prodigalgal.ircs.scraper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.ingestion.IngestionItem;
import com.prodigalgal.ircs.scraper.ScraperDtos.DataSourceRecord;
import com.prodigalgal.ircs.scraper.ScraperDtos.DirectScrapeItem;
import com.prodigalgal.ircs.scraper.ScraperDtos.ScrapedVideoDraft;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScraperMappingServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScraperMappingService service = new ScraperMappingService(objectMapper);

    @Test
    void mapsDetailJsonToCanonicalRawMetadata() throws Exception {
        UUID dataSourceId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        DataSourceRecord source = new DataSourceRecord(
                dataSourceId,
                "Smoke",
                "https://video.example.invalid",
                "/list",
                null,
                "/detail",
                null,
                """
                        {
                          "detail_mapping": {
                            "source_vid": {"path": "$.id"},
                            "title": {"path": "$.name"},
                            "coverImageUrl": {"path": "$.cover"},
                            "year": {"path": "$.year"},
                            "area": {"path": "$.region"},
                            "language": {"path": "$.lang"},
                            "rawTypeId": {"path": "$.type.id"},
                            "rawTypeName": {"path": "$.type.name"},
                            "genreNames": {"path": "$.genres"},
                            "actorNames": {"path": "$.people.actors"},
                            "directorNames": {"path": "$.people.directors"},
                            "playlist_from": {"path": "$.from"},
                            "playlist_url": {"path": "$.urls"}
                          }
                        }
                        """);

        ScrapedVideoDraft draft = service.mapDetail("""
                {
                  "id": "abc-1",
                  "name": "Codex Smoke",
                  "cover": "/cover.jpg",
                  "year": "2026",
                  "region": "中国大陆, 美国",
                  "lang": "国语/英语",
                  "type": {"id": "movie", "name": "电影"},
                  "genres": ["动作", "科幻"],
                  "people": {
                    "actors": ["演员甲", "演员乙"],
                    "directors": "导演甲/导演乙"
                  },
                  "from": "main",
                  "urls": "EP1$https://cdn.example.invalid/1.m3u8"
                }
                """, source);
        IngestionItem item = service.toItem(draft, true);
        JsonNode raw = objectMapper.readTree(item.video().rawMetadata());

        assertEquals("abc-1", item.video().sourceVid());
        assertEquals("Codex Smoke", item.video().title());
        assertEquals("https://video.example.invalid/cover.jpg", item.video().coverImageUrl());
        assertEquals(dataSourceId, item.video().dataSourceId());
        assertEquals(1, item.video().playlists().size());
        assertNotNull(item.video().sourceHash());
        assertNotNull(item.video().dataHash());
        assertEquals(dataSourceId.toString(), raw.path("dataSourceId").asText());
        assertEquals("abc-1", raw.path("sourceVid").asText());
        assertEquals("Codex Smoke", raw.path("title").asText());
        assertEquals("https://video.example.invalid/cover.jpg", raw.path("coverImageUrl").asText());
        assertEquals("2026", raw.path("year").asText());
        assertEquals("中国大陆, 美国", raw.path("area").asText());
        assertEquals("国语/英语", raw.path("language").asText());
        assertEquals("movie", raw.path("rawTypeId").asText());
        assertEquals("电影", raw.path("rawTypeName").asText());
        assertEquals("动作", raw.path("genreNames").get(0).asText());
        assertEquals("演员甲", raw.path("actorNames").get(0).asText());
        assertEquals("导演甲", raw.path("directorNames").get(0).asText());
        assertEquals("abc-1", raw.path("sourcePayload").path("id").asText());
        assertEquals("电影", raw.path("sourcePayload").path("type").path("name").asText());
    }

    @Test
    void categoryNameMappingFeedsCanonicalRawTypeName() throws Exception {
        UUID dataSourceId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        DataSourceRecord source = new DataSourceRecord(
                dataSourceId,
                "Cms",
                "https://cms.example.invalid",
                "/list",
                null,
                "/detail",
                null,
                """
                        {
                          "detail_mapping": {
                            "source_vid": {"path": "$.list[0].vod_id"},
                            "title": {"path": "$.list[0].vod_name"},
                            "categoryName": {"path": "$.list[0].type_name"},
                            "typeId": {"path": "$.list[0].type_id"}
                          }
                        }
                        """);

        ScrapedVideoDraft draft = service.mapDetail("""
                {
                  "list": [
                    {
                      "vod_id": "cms-1",
                      "vod_name": "CMS Title",
                      "type_id": "2",
                      "type_name": "电视剧"
                    }
                  ]
                }
                """, source);
        IngestionItem item = service.toItem(draft, false);
        JsonNode raw = objectMapper.readTree(item.video().rawMetadata());

        assertEquals("2", raw.path("rawTypeId").asText());
        assertEquals("电视剧", raw.path("rawTypeName").asText());
    }

    @Test
    void directItemMergesUserRawMetadataWithCanonicalFields() throws Exception {
        UUID fallbackSourceId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        DataSourceRecord fallbackSource = new DataSourceRecord(
                fallbackSourceId,
                "Direct",
                "https://direct.example.invalid",
                null,
                null,
                null,
                null,
                "{}");
        DirectScrapeItem directItem = new DirectScrapeItem(
                null,
                "direct-1",
                "Direct Title",
                "Direct Alias",
                "Direct Description",
                "https://cdn.example.invalid/direct.jpg",
                "2025",
                "香港",
                "粤语",
                "完结",
                new BigDecimal("8.6"),
                LocalDate.parse("2025-05-01"),
                "12",
                "45分钟",
                "35200000",
                "991",
                "tt991",
                "rt991",
                """
                        {
                          "customProviderField": "keep-me",
                          "rawTypeId": "series",
                          "rawTypeName": "剧集",
                          "genreNames": "悬疑/犯罪",
                          "actorNames": ["演员丙"],
                          "directorNames": "导演丙"
                        }
                        """,
                List.of());

        IngestionItem item = service.directItem(directItem, fallbackSource, false);
        JsonNode raw = objectMapper.readTree(item.video().rawMetadata());

        assertEquals(fallbackSourceId, item.video().dataSourceId());
        assertEquals("direct-1", raw.path("sourceVid").asText());
        assertEquals("Direct Title", raw.path("title").asText());
        assertEquals("Direct Alias", raw.path("aliasTitle").asText());
        assertEquals("Direct Description", raw.path("description").asText());
        assertEquals("https://cdn.example.invalid/direct.jpg", raw.path("coverImageUrl").asText());
        assertEquals("2025", raw.path("year").asText());
        assertEquals("香港", raw.path("area").asText());
        assertEquals("粤语", raw.path("language").asText());
        assertEquals("series", raw.path("rawTypeId").asText());
        assertEquals("剧集", raw.path("rawTypeName").asText());
        assertEquals("悬疑", raw.path("genreNames").get(0).asText());
        assertEquals("犯罪", raw.path("genreNames").get(1).asText());
        assertEquals("演员丙", raw.path("actorNames").get(0).asText());
        assertEquals("导演丙", raw.path("directorNames").get(0).asText());
        assertEquals("keep-me", raw.path("customProviderField").asText());
        assertTrue(raw.path("sourcePayload").isMissingNode());
    }
}
