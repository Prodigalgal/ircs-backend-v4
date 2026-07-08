package com.prodigalgal.ircs.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CatalogAdminControllerTest {

    private final CatalogService catalogService = org.mockito.Mockito.mock(CatalogService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new CatalogController(catalogService))
            .build();

    @Test
    void routesDataSourceWriteContract() throws Exception {
        UUID id = UUID.randomUUID();
        DataSourceRead read = new DataSourceRead(
                id,
                "codex-source",
                "http://127.0.0.1:18080",
                "/list",
                "{}",
                "/detail",
                null,
                "{}",
                null,
                null);
        when(catalogService.createDataSource(any())).thenReturn(read);
        when(catalogService.updateDataSource(eq(id), any())).thenReturn(read);
        when(catalogService.patchDataSource(eq(id), any())).thenReturn(Optional.of(read));
        when(catalogService.fetchSample(any())).thenReturn(Optional.of("{\"ok\":true}"));

        mockMvc.perform(post("/api/v1/data-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dataSourceBody(null)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/data-sources/" + id));

        mockMvc.perform(put("/api/v1/data-sources/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dataSourceBody(id)))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/data-sources/{id}", id)
                        .contentType("application/merge-patch+json")
                        .content("{\"id\":\"" + id + "\",\"name\":\"codex-source-2\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/data-sources/{id}", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/data-sources/fetch-sample")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestType":"LIST",
                                  "baseUrl":"http://127.0.0.1:18080",
                                  "listPath":"/list"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"ok\":true}"));
    }

    @Test
    void routesStandardDictionaryWriteContract() throws Exception {
        UUID categoryId = UUID.randomUUID();
        UUID genreId = UUID.randomUUID();
        UUID areaId = UUID.randomUUID();
        UUID languageId = UUID.randomUUID();
        when(catalogService.createStandardCategory(any()))
                .thenReturn(new StandardCategoryRead(categoryId, "Movie", "movie", null, null));
        when(catalogService.updateStandardCategory(eq(categoryId), any()))
                .thenReturn(new StandardCategoryRead(categoryId, "Movie 2", "movie-2", null, null));
        when(catalogService.patchStandardCategory(eq(categoryId), any()))
                .thenReturn(Optional.of(new StandardCategoryRead(categoryId, "Movie 3", "movie-3", null, null)));
        when(catalogService.createStandardGenre(any()))
                .thenReturn(new StandardGenreRead(genreId, "Action"));
        when(catalogService.updateStandardGenre(eq(genreId), any()))
                .thenReturn(Optional.of(new StandardGenreRead(genreId, "Action 2")));
        when(catalogService.createStandardArea(any()))
                .thenReturn(new StandardAreaRead(areaId, "China", "CN", "Asia", null, null));
        when(catalogService.createStandardLanguage(any()))
                .thenReturn(new StandardLanguageRead(languageId, "Chinese", "zh", "Chinese", "中文"));

        mockMvc.perform(post("/api/v1/standard-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Movie\",\"slug\":\"movie\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/v1/standard-categories/{id}", categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + categoryId + "\",\"name\":\"Movie 2\",\"slug\":\"movie-2\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/standard-categories/{id}", categoryId)
                        .contentType("application/merge-patch+json")
                        .content("{\"id\":\"" + categoryId + "\",\"name\":\"Movie 3\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/standard-categories/{id}", categoryId))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/standard-genres")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Action\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/v1/standard-genres/{id}", genreId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + genreId + "\",\"name\":\"Action 2\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/standard-genres/{id}", genreId))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/standard-areas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"China\",\"code\":\"CN\",\"region\":\"Asia\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/standard-languages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Chinese\",\"code\":\"zh\",\"englishName\":\"Chinese\",\"nativeName\":\"中文\"}"))
                .andExpect(status().isOk());
    }

    private String dataSourceBody(UUID id) {
        return """
                {
                  "id": %s,
                  "name": "codex-source",
                  "baseUrl": "http://127.0.0.1:18080/",
                  "listPath": "list",
                  "listParams": "{\\"pg\\":\\"{page}\\"}",
                  "detailPath": "/detail",
                  "detailParams": "{\\"ids\\":\\"{id}\\"}",
                  "fieldMapping": "{\\"title\\":\\"vod_name\\"}"
                }
                """.formatted(id == null ? "null" : "\"" + id + "\"");
    }
}
