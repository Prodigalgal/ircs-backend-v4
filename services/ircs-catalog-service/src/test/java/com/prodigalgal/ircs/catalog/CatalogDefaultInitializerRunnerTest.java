package com.prodigalgal.ircs.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class CatalogDefaultInitializerRunnerTest {

    private final JdbcCatalogRepository repository = org.mockito.Mockito.mock(JdbcCatalogRepository.class);
    private final CatalogDefaultSeedCatalog defaults = new CatalogDefaultSeedCatalog();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CatalogDefaultInitializerRunner runner =
            CatalogDefaultInitializerRunner.forTest(repository, defaults, objectMapper, true);

    @Test
    void seedsV1CoreCatalogDefaultsWithoutLegacyRawAliases() throws Exception {
        UUID stableId = UUID.randomUUID();
        stubSeedDefaults(stableId);
        when(repository.listDataSourceSeedMappings()).thenReturn(List.of());

        runner.run(new DefaultApplicationArguments());

        verify(repository).seedStandardCategory("电影", "movie");
        verify(repository).seedStandardCategory("剧集", "series");
        verify(repository).seedStandardCategory("纪录片", "documentary");
        verify(repository).seedStandardCategory("体育赛事", "sports");
        verify(repository).seedStandardCategory("成人", "adult");
        verify(repository).seedStandardCategory("其他", "other");
        verify(repository).seedStandardGenre("剧情");
        verify(repository).seedStandardGenre("国产剧");
        verify(repository).seedStandardArea("CN", "中国", "Asia");
        verify(repository).seedStandardArea("US", "美国", "Americas");
        verify(repository).seedStandardLanguage("zh", "普通话", "Mandarin", "普通话");
        verify(repository).seedStandardLanguage("cn", "粤语", "Cantonese", "广州话 / 廣州話");
        verify(repository).seedDataSource(
                "光速资源",
                "https://api.guangsuapi.com",
                "/api.php/provide/vod/josn",
                defaults.defaultListParams(),
                defaults.defaultDetailParams(),
                defaults.defaultFieldMapping());
        verify(repository).seedDataSource(
                "非凡资源",
                "http://api.ffzyapi.com",
                "/api.php/provide/vod/",
                defaults.defaultListParams(),
                defaults.defaultDetailParams(),
                defaults.defaultFieldMapping());
    }

    @Test
    void patchesExistingDataSourceMappingLikeV1Initializer() throws Exception {
        UUID stableId = UUID.randomUUID();
        stubSeedDefaults(stableId);
        String legacyMapping = """
                {
                  "detail_mapping": {
                    "title": { "path": "$.list[0].vod_name" },
                    "subTitle": { "path": "$.list[0].vod_sub_legacy" }
                  }
                }
                """;
        when(repository.listDataSourceSeedMappings())
                .thenReturn(List.of(new JdbcCatalogRepository.DataSourceSeedMappingRow(
                        stableId,
                        "legacy",
                        legacyMapping)));
        when(repository.updateDataSourceFieldMapping(eq(stableId), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> new JdbcCatalogRepository.SeedResult(stableId, false, true));

        runner.run(new DefaultApplicationArguments());

        org.mockito.ArgumentCaptor<String> mapping = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository).updateDataSourceFieldMapping(eq(stableId), mapping.capture());
        assertThat(mapping.getValue()).contains("aliasTitle");
        assertThat(mapping.getValue()).contains("vod_sub_legacy");
        assertThat(mapping.getValue()).contains("totalEpisodes");
        assertThat(mapping.getValue()).contains("duration");
        assertThat(mapping.getValue()).doesNotContain("subTitle");
    }

    @Test
    void seedCatalogDoesNotInventV1MissingNanLanguage() {
        assertThat(defaults.languages()).noneMatch(language -> "nan".equals(language.code()));
    }

    @Test
    void loadsFullV1CountryAndLanguagePresetsFromResources() {
        assertThat(defaults.areas()).hasSize(251);
        assertThat(defaults.languages()).hasSize(187);
        assertThat(defaults.areas())
                .anySatisfy(area -> {
                    assertThat(area.code()).isEqualTo("AE");
                    assertThat(area.name()).isEqualTo("阿拉伯联合酋长国");
                    assertThat(area.region()).isEqualTo("Asia");
                })
                .anySatisfy(area -> {
                    assertThat(area.code()).isEqualTo("GL");
                    assertThat(area.region()).isEqualTo("Americas");
                });
        assertThat(defaults.languages())
                .anySatisfy(language -> {
                    assertThat(language.code()).isEqualTo("kw");
                    assertThat(language.name()).isEqualTo("康沃尔语");
                })
                .anySatisfy(language -> {
                    assertThat(language.code()).isEqualTo("zh");
                    assertThat(language.name()).isEqualTo("普通话");
                });
    }

    @Test
    void skipsRepositoryWorkWhenDefaultSeedGateIsDisabled() throws Exception {
        CatalogDefaultInitializerRunner disabledRunner =
                CatalogDefaultInitializerRunner.forTest(repository, defaults, objectMapper, false);

        disabledRunner.run(new DefaultApplicationArguments());

        verifyNoInteractions(repository);
    }

    private void stubSeedDefaults(UUID stableId) {
        when(repository.seedStandardCategory(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new JdbcCatalogRepository.SeedResult(stableId, true, false));
        when(repository.seedStandardGenre(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new JdbcCatalogRepository.SeedResult(stableId, true, false));
        when(repository.seedStandardArea(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new JdbcCatalogRepository.SeedResult(stableId, true, false));
        when(repository.seedStandardLanguage(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new JdbcCatalogRepository.SeedResult(stableId, true, false));
        when(repository.seedDataSource(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new JdbcCatalogRepository.SeedResult(stableId, true, false));
    }
}
