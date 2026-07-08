package com.prodigalgal.ircs.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.cache.CacheRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogServiceReadModelCacheTest {

    private final JdbcCatalogRepository repository = org.mockito.Mockito.mock(JdbcCatalogRepository.class);
    private final CatalogService service = new CatalogService(
            repository,
            JsonMapper.builder().findAndAddModules().build(),
            org.mockito.Mockito.mock(CatalogFetchSampleClient.class),
            org.mockito.Mockito.mock(CatalogRemoteCategorySyncService.class),
            cache());

    @Test
    void standardDictionarySummaryListsUseReadModelCache() {
        StandardCategorySummary category = new StandardCategorySummary(UUID.randomUUID(), "Movie", "movie");
        StandardGenreSummary genre = new StandardGenreSummary(UUID.randomUUID(), "Drama");
        StandardAreaSummary area = new StandardAreaSummary(UUID.randomUUID(), "China", "CN", "Asia");
        StandardLanguageSummary language = new StandardLanguageSummary(
                UUID.randomUUID(),
                "Chinese",
                "zh",
                "Chinese",
                "中文");
        when(repository.listStandardCategories()).thenReturn(List.of(category));
        when(repository.listStandardGenres()).thenReturn(List.of(genre));
        when(repository.listStandardAreas()).thenReturn(List.of(area));
        when(repository.listStandardLanguages()).thenReturn(List.of(language));

        assertThat(service.listStandardCategories()).isEqualTo(List.of(category));
        assertThat(service.listStandardCategories()).isEqualTo(List.of(category));
        assertThat(service.listStandardGenres()).isEqualTo(List.of(genre));
        assertThat(service.listStandardGenres()).isEqualTo(List.of(genre));
        assertThat(service.listStandardAreas()).isEqualTo(List.of(area));
        assertThat(service.listStandardAreas()).isEqualTo(List.of(area));
        assertThat(service.listStandardLanguages()).isEqualTo(List.of(language));
        assertThat(service.listStandardLanguages()).isEqualTo(List.of(language));

        verify(repository, times(1)).listStandardCategories();
        verify(repository, times(1)).listStandardGenres();
        verify(repository, times(1)).listStandardAreas();
        verify(repository, times(1)).listStandardLanguages();
    }

    @Test
    void standardCategoryWriteEvictsSummaryAndReadCaches() {
        UUID id = UUID.randomUUID();
        StandardCategorySummary summary = new StandardCategorySummary(id, "Movie", "movie");
        StandardCategoryRead read = new StandardCategoryRead(id, "Movie", "movie", null, null);
        when(repository.listStandardCategories()).thenReturn(List.of(summary));
        when(repository.listStandardCategoryReads()).thenReturn(List.of(read));
        when(repository.updateStandardCategory(id, new StandardCategoryAdminRequest(id, "Movie 2", "movie-2", null, null), false))
                .thenReturn(Optional.of(new StandardCategoryRead(id, "Movie 2", "movie-2", null, null)));

        service.listStandardCategories();
        service.listStandardCategoryReads();
        service.updateStandardCategory(id, new StandardCategoryAdminRequest(id, "Movie 2", "movie-2", null, null));
        service.listStandardCategories();
        service.listStandardCategoryReads();

        verify(repository, times(2)).listStandardCategories();
        verify(repository, times(2)).listStandardCategoryReads();
    }

    private CatalogReadModelCache cache() {
        return new CatalogReadModelCache(
                JsonMapper.builder().findAndAddModules().build(),
                new CacheRegistry(),
                null,
                null,
                true,
                Duration.ofMinutes(10));
    }
}
