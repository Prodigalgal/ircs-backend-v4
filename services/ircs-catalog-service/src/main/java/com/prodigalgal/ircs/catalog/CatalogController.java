package com.prodigalgal.ircs.catalog;

import com.prodigalgal.ircs.common.web.ApiErrorResponses;
import com.prodigalgal.ircs.common.web.PageEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class CatalogController {

    private final CatalogService catalogService;

    @GetMapping("/catalog/standard-categories")
    public List<StandardCategorySummary> listCatalogStandardCategories() {
        return catalogService.listStandardCategories();
    }

    @GetMapping("/catalog/standard-genres")
    public List<StandardGenreSummary> listCatalogStandardGenres() {
        return catalogService.listStandardGenres();
    }

    @GetMapping("/catalog/standard-areas")
    public List<StandardAreaSummary> listCatalogStandardAreas() {
        return catalogService.listStandardAreas();
    }

    @GetMapping("/catalog/standard-languages")
    public List<StandardLanguageSummary> listCatalogStandardLanguages() {
        return catalogService.listStandardLanguages();
    }

    @GetMapping("/catalog/data-sources")
    public List<DataSourceSummary> listCatalogDataSources() {
        return catalogService.listDataSources();
    }

    @GetMapping({"/standard-categories", "/standard-categories/all"})
    public List<StandardCategoryRead> listStandardCategories() {
        return catalogService.listStandardCategoryReads();
    }

    @PostMapping("/standard-categories")
    public ResponseEntity<StandardCategoryRead> createStandardCategory(
            @RequestBody StandardCategoryAdminRequest request) {
        StandardCategoryRead result = catalogService.createStandardCategory(request);
        return created(result.id(), result);
    }

    @PutMapping("/standard-categories/{id}")
    public ResponseEntity<StandardCategoryRead> updateStandardCategory(
            @PathVariable UUID id,
            @RequestBody StandardCategoryAdminRequest request) {
        return ResponseEntity.ok(catalogService.updateStandardCategory(id, request));
    }

    @PatchMapping(
            value = "/standard-categories/{id}",
            consumes = {"application/json", "application/merge-patch+json"})
    public ResponseEntity<StandardCategoryRead> patchStandardCategory(
            @PathVariable UUID id,
            @RequestBody StandardCategoryAdminRequest request) {
        return catalogService.patchStandardCategory(id, request)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Entity not found"));
    }

    @DeleteMapping("/standard-categories/{id}")
    public ResponseEntity<Void> deleteStandardCategory(@PathVariable UUID id) {
        catalogService.deleteStandardCategory(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/standard-categories/page")
    public PageEnvelope<StandardCategoryRead> pageStandardCategories(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String slug) {
        return pageEnvelope(catalogService.pageStandardCategories(CatalogPageRequest.of(page, size, sort), name, slug));
    }

    @GetMapping("/standard-categories/{id}")
    public ResponseEntity<StandardCategoryRead> getStandardCategory(@PathVariable UUID id) {
        return catalogService.findStandardCategory(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/standard-genres")
    public List<StandardGenreRead> listStandardGenres() {
        return catalogService.listStandardGenreReads();
    }

    @PostMapping("/standard-genres")
    public ResponseEntity<StandardGenreRead> createStandardGenre(@RequestBody StandardGenreAdminRequest request) {
        StandardGenreRead result = catalogService.createStandardGenre(request);
        return created(result.id(), result);
    }

    @PutMapping("/standard-genres/{id}")
    public ResponseEntity<StandardGenreRead> updateStandardGenre(
            @PathVariable UUID id,
            @RequestBody StandardGenreAdminRequest request) {
        return catalogService.updateStandardGenre(id, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/standard-genres/{id}")
    public ResponseEntity<Void> deleteStandardGenre(@PathVariable UUID id) {
        catalogService.deleteStandardGenre(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/standard-genres/page")
    public PageEnvelope<StandardGenreRead> pageStandardGenres(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String code) {
        return pageEnvelope(catalogService.pageStandardGenres(CatalogPageRequest.of(page, size, sort), name, code));
    }

    @GetMapping("/standard-genres/{id}")
    public ResponseEntity<StandardGenreRead> getStandardGenre(@PathVariable UUID id) {
        return catalogService.findStandardGenre(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/standard-areas")
    public List<StandardAreaRead> listStandardAreas() {
        return catalogService.listStandardAreaReads();
    }

    @PostMapping("/standard-areas")
    public ResponseEntity<StandardAreaRead> createStandardArea(@RequestBody StandardAreaAdminRequest request) {
        return ResponseEntity.ok(catalogService.createStandardArea(request));
    }

    @GetMapping("/standard-areas/page")
    public PageEnvelope<StandardAreaRead> pageStandardAreas(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort) {
        return pageEnvelope(catalogService.pageStandardAreas(CatalogPageRequest.of(page, size, sort)));
    }

    @GetMapping("/standard-languages")
    public List<StandardLanguageRead> listStandardLanguages() {
        return catalogService.listStandardLanguageReads();
    }

    @PostMapping("/standard-languages")
    public ResponseEntity<StandardLanguageRead> createStandardLanguage(
            @RequestBody StandardLanguageAdminRequest request) {
        return ResponseEntity.ok(catalogService.createStandardLanguage(request));
    }

    @GetMapping("/data-sources")
    public PageEnvelope<DataSourceRead> pageDataSources(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> sort) {
        return pageEnvelope(catalogService.pageDataSources(CatalogPageRequest.of(page, size, sort)));
    }

    @PostMapping("/data-sources")
    public ResponseEntity<DataSourceRead> createDataSource(@Valid @RequestBody DataSourceAdminRequest request) {
        DataSourceRead result = catalogService.createDataSource(request);
        return created(result.id(), result);
    }

    @PutMapping("/data-sources/{id}")
    public ResponseEntity<DataSourceRead> updateDataSource(
            @PathVariable UUID id,
            @Valid @RequestBody DataSourceAdminRequest request) {
        return ResponseEntity.ok(catalogService.updateDataSource(id, request));
    }

    @PatchMapping(
            value = "/data-sources/{id}",
            consumes = {"application/json", "application/merge-patch+json"})
    public ResponseEntity<DataSourceRead> patchDataSource(
            @PathVariable UUID id,
            @Valid @RequestBody DataSourceAdminRequest request) {
        return catalogService.patchDataSource(id, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/data-sources/{id}")
    public ResponseEntity<Void> deleteDataSource(@PathVariable UUID id) {
        catalogService.deleteDataSource(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/data-sources/fetch-sample")
    public ResponseEntity<?> fetchSample(@Valid @RequestBody FetchSampleRequest request, HttpServletRequest httpRequest) {
        return catalogService.fetchSample(request)
                .<ResponseEntity<?>>map(body -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body))
                .orElseGet(() -> ApiErrorResponses.response(
                        HttpStatus.BAD_GATEWAY,
                        "catalog.fetch-sample.failed",
                        "Failed to fetch response from the external API.",
                        "catalog",
                        httpRequest));
    }

    @GetMapping("/data-sources/{id}")
    public ResponseEntity<DataSourceRead> getDataSource(@PathVariable UUID id) {
        return catalogService.findDataSource(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private <T> ResponseEntity<T> created(UUID id, T body) {
        return ResponseEntity.created(ServletUriComponentsBuilder
                        .fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(id)
                        .toUri())
                .body(body);
    }

    private <T> PageEnvelope<T> pageEnvelope(CatalogPage<T> page) {
        return PageEnvelope.of(page.content(), page.number(), page.size(), page.totalElements());
    }
}
