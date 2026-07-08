package com.prodigalgal.ircs.portal;

import com.prodigalgal.ircs.common.normalization.StandardContentCategoryClassifier;
import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortalQueryService {

    private static final int MOVIE_RAIL_SIZE = 20;
    private static final int MOVIE_TREND_SIZE = 50;
    private static final Map<String, String> SECTION_TITLES = Map.ofEntries(
            Map.entry("movie", "电影精选"),
            Map.entry("series", "热门剧集"),
            Map.entry("short-drama", "爽剧/微短剧"),
            Map.entry("anime", "动漫番剧"),
            Map.entry("variety", "综艺合集"),
            Map.entry("documentary", "纪录片场"),
            Map.entry("sports", "体育赛事"),
            Map.entry("news", "新闻资讯"),
            Map.entry("education", "教育知识"),
            Map.entry("music", "音乐现场"),
            Map.entry("adult", "成人专区"),
            Map.entry("other", "其他内容"));

    private final JdbcPortalRepository portalRepository;
    private final PortalReadModelCache readModelCache;
    public PortalQueryService(JdbcPortalRepository portalRepository, PortalReadModelCache readModelCache) {
        this.portalRepository = portalRepository;
        this.readModelCache = readModelCache;
    }

    @Transactional(readOnly = true)
    public PortalMetadataResponse getMetadata(IrcsRequestPrincipal principal) {
        return readModelCache.metadata(principal, () -> getMetadataUncached(principal));
    }

    private PortalMetadataResponse getMetadataUncached(IrcsRequestPrincipal principal) {
        return new PortalMetadataResponse(
                stableCategories(principal),
                safeList(portalRepository.findActiveGenres(principal)),
                safeList(portalRepository.findActiveAreas(principal)),
                safeList(portalRepository.findActiveLanguages(principal)),
                safeList(portalRepository.findActiveYears(principal)));
    }

    @Transactional(readOnly = true)
    public PortalHomeResponse getHome(IrcsRequestPrincipal principal) {
        return readModelCache.home(principal, () -> getHomeUncached(principal));
    }

    private PortalHomeResponse getHomeUncached(IrcsRequestPrincipal principal) {
        List<PortalMovieCard> spotlight = safeList(portalRepository.findSpotlight(principal, MOVIE_RAIL_SIZE));
        List<PortalMovieCard> trending = safeList(portalRepository.findTrending(principal, MOVIE_TREND_SIZE));
        List<CategoryItem> categories = stableCategories(principal);
        Map<String, List<PortalMovieCard>> moviesByCategory =
                portalRepository.findCategorySections(
                        principal,
                        categories.stream().map(CategoryItem::slug).toList(),
                        MOVIE_RAIL_SIZE);
        List<PortalCategorySection> sections = new ArrayList<>();

        for (CategoryItem category : categories) {
            List<PortalMovieCard> movies = moviesByCategory.get(category.slug());
            sections.add(new PortalCategorySection(category.slug(), sectionTitle(category), safeList(movies)));
        }

        return new PortalHomeResponse(spotlight, trending, sections);
    }

    @Transactional(readOnly = true)
    public PageResponse<PortalMovieCard> explore(
            IrcsRequestPrincipal principal,
            int page,
            int size,
            String keyword,
            String type,
            String genre,
            String area,
            String year,
            String language,
            String sort) {
        return readModelCache.explore(
                principal,
                page,
                size,
                keyword,
                type,
                genre,
                area,
                year,
                language,
                sort,
                () -> portalRepository.findExplore(principal, page, size, keyword, type, genre, area, year, language, sort));
    }

    @Transactional(readOnly = true)
    public PageResponse<PortalSitemapMovie> sitemapMovies(int page, int size) {
        return portalRepository.findSitemapMovies(IrcsRequestPrincipal.publicPrincipal(), page, size);
    }

    @Transactional(readOnly = true)
    public Optional<PortalMovieDetailResponse> getMovieDetail(IrcsRequestPrincipal principal, UUID id) {
        return readModelCache.detail(principal, id, () -> portalRepository.findMovieDetail(principal, id));
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null || values.isEmpty() ? Collections.emptyList() : values;
    }

    private List<CategoryItem> stableCategories(IrcsRequestPrincipal principal) {
        IrcsRequestPrincipal effective = principal == null ? IrcsRequestPrincipal.publicPrincipal() : principal;
        return StandardContentCategoryClassifier.stableCategories().stream()
                .map(category -> new CategoryItem(category.name(), category.code()))
                .filter(category -> StandardContentCategoryClassifier.isAllowedCode(category.slug()))
                .filter(category -> effective.allowsCategory(category.slug(), category.name()))
                .toList();
    }

    private String sectionTitle(CategoryItem category) {
        String title = SECTION_TITLES.get(category.slug());
        return title == null || title.isBlank() ? category.name() : title;
    }
}
