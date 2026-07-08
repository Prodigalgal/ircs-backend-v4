package com.prodigalgal.ircs.metadata.provider.tmdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.metadata.EnrichedMetadataDTO;
import com.prodigalgal.ircs.contracts.metadata.MetadataSearchContext;
import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProvider;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderRetryableException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@Slf4j
@RequiredArgsConstructor
public class TmdbMetadataProvider implements MetadataProvider {

    private static final int MAX_ACTORS = 15;
    private static final Set<String> MOVIE_SLUGS = Set.of("movie");
    private static final Set<String> TV_SLUGS = Set.of("series", "tv", "variety", "short-drama");

    private final TmdbCredentialRepository credentialRepository;
    private final TmdbRateLimiter rateLimiter;
    private final TmdbHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TmdbProviderProperties properties;
    private final AtomicLong credentialCursor = new AtomicLong();

    @Override
    public ProviderType getType() {
        return ProviderType.TMDB;
    }

    @Override
    public boolean supports(MetadataSearchContext context) {
        return context != null && StringUtils.hasText(context.getFullTitle());
    }

    @Override
    public Optional<EnrichedMetadataDTO> enrich(MetadataSearchContext context) {
        List<TmdbCredential> credentials = credentialRepository.findEnabled();
        if (credentials.isEmpty()) {
            throw new MetadataProviderRetryableException("POOL_EXHAUSTED", "No enabled TMDB credentials");
        }

        MetadataProviderRetryableException lastRetryable = null;
        int start = Math.floorMod(credentialCursor.getAndIncrement(), credentials.size());
        for (int attempt = 0; attempt < credentials.size(); attempt++) {
            TmdbCredential credential = credentials.get((start + attempt) % credentials.size());
            try {
                return searchWithCredential(context, credential);
            } catch (MetadataProviderRetryableException ex) {
                lastRetryable = ex;
            }
        }

        throw lastRetryable == null
                ? new MetadataProviderRetryableException("POOL_EXHAUSTED", "No usable TMDB credentials")
                : lastRetryable;
    }

    private Optional<EnrichedMetadataDTO> searchWithCredential(MetadataSearchContext context, TmdbCredential credential) {
        String searchType = determineSearchType(context);
        int maxPages = "multi".equals(searchType) ? properties.getMaxMultiPages() : 1;
        for (int page = 1; page <= maxPages; page++) {
            Optional<String> response = getJson(credential, buildSearchUri(searchType, credential.apiKey(), context, page));
            if (response.isEmpty()) {
                return Optional.empty();
            }
            Optional<EnrichedMetadataDTO> match = findMatch(context, credential, searchType, page, response.get());
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    private Optional<EnrichedMetadataDTO> findMatch(
            MetadataSearchContext context,
            TmdbCredential credential,
            String searchType,
            int page,
            String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");
            int totalPages = root.path("total_pages").asInt(1);
            if (results.isArray()) {
                for (JsonNode candidate : results) {
                    String mediaType = extractMediaType(candidate, searchType);
                    if ("person".equals(mediaType)) {
                        continue;
                    }
                    if (isMatch(context, candidate, mediaType)) {
                        String tmdbId = String.valueOf(candidate.path("id").asLong());
                        return fetchDetails(credential, tmdbId, mediaType);
                    }
                }
            }
            if (page >= totalPages) {
                return Optional.empty();
            }
            return Optional.empty();
        } catch (MetadataProviderRetryableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MetadataProviderRetryableException("PROVIDER_PARSE_ERROR", "Unable to parse TMDB search response", ex);
        }
    }

    private Optional<EnrichedMetadataDTO> fetchDetails(TmdbCredential credential, String tmdbId, String mediaType) {
        try {
            String endpoint = "movie".equals(mediaType) ? "/movie" : "/tv";
            Optional<String> response = getJson(credential, UriComponentsBuilder
                    .fromUriString(properties.getBaseUrl() + endpoint + "/" + tmdbId)
                    .queryParam("api_key", credential.apiKey())
                    .queryParam("language", properties.getLanguage())
                    .queryParam("append_to_response", "credits,external_ids")
                    .build()
                    .encode()
                    .toUri());
            if (response.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(mapDetails(tmdbId, response.get()));
        } catch (MetadataProviderRetryableException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Failed to fetch or parse TMDB details for id={}", tmdbId, ex);
            return Optional.empty();
        }
    }

    private Optional<String> getJson(TmdbCredential credential, URI uri) {
        rateLimiter.acquire(credential);
        return httpClient.getJson(uri);
    }

    private EnrichedMetadataDTO mapDetails(String tmdbId, String response) throws java.io.IOException {
        JsonNode root = objectMapper.readTree(response);
        EnrichedMetadataDTO metadata = new EnrichedMetadataDTO();
        metadata.setTmdbId(tmdbId);

        JsonNode externalIds = root.path("external_ids");
        if (StringUtils.hasText(externalIds.path("imdb_id").asText(null))) {
            metadata.setImdbId(externalIds.path("imdb_id").asText());
        } else if (StringUtils.hasText(root.path("imdb_id").asText(null))) {
            metadata.setImdbId(root.path("imdb_id").asText());
        }

        metadata.setTitle(root.path("title").asText(root.path("name").asText(null)));
        metadata.setOriginalTitle(root.path("original_title").asText(root.path("original_name").asText(null)));
        metadata.setDescription(root.path("overview").asText(null));

        String posterPath = root.path("poster_path").asText(null);
        if (StringUtils.hasText(posterPath) && !"null".equals(posterPath)) {
            metadata.setPosterUrl(properties.getImageBaseUrl() + posterPath);
            metadata.setImageSource(ProviderType.TMDB);
        }
        String backdropPath = root.path("backdrop_path").asText(null);
        if (StringUtils.hasText(backdropPath) && !"null".equals(backdropPath)) {
            metadata.setBackdropUrl(properties.getBackdropBaseUrl() + backdropPath);
        }

        double voteAverage = root.path("vote_average").asDouble();
        if (voteAverage > 0) {
            metadata.setScore(BigDecimal.valueOf(voteAverage));
        }
        applyReleaseDate(metadata, root.path("release_date").asText(root.path("first_air_date").asText(null)));

        JsonNode productionCountries = root.path("production_countries");
        if (productionCountries.isArray() && !productionCountries.isEmpty()) {
            metadata.setArea(productionCountries.get(0).path("name").asText());
        } else {
            JsonNode originCountry = root.path("origin_country");
            if (originCountry.isArray() && !originCountry.isEmpty()) {
                metadata.setArea(originCountry.get(0).asText());
            }
        }

        JsonNode genres = root.path("genres");
        if (genres.isArray()) {
            for (JsonNode genre : genres) {
                metadata.addGenre(genre.path("name").asText());
            }
        }
        applyCredits(metadata, root);
        return metadata;
    }

    private void applyReleaseDate(EnrichedMetadataDTO metadata, String releaseDate) {
        if (!StringUtils.hasText(releaseDate)) {
            return;
        }
        try {
            metadata.setPublishedAt(LocalDate.parse(releaseDate));
            metadata.setYear(String.valueOf(metadata.getPublishedAt().getYear()));
        } catch (DateTimeParseException ignored) {
            if (releaseDate.length() >= 4) {
                metadata.setYear(releaseDate.substring(0, 4));
            }
        }
    }

    private void applyCredits(EnrichedMetadataDTO metadata, JsonNode root) {
        JsonNode cast = root.path("credits").path("cast");
        if (cast.isArray()) {
            Iterator<JsonNode> iterator = cast.elements();
            int count = 0;
            while (iterator.hasNext() && count < MAX_ACTORS) {
                metadata.addActor(iterator.next().path("name").asText());
                count++;
            }
        }
        JsonNode crew = root.path("credits").path("crew");
        if (crew.isArray()) {
            for (JsonNode member : crew) {
                if ("Director".equalsIgnoreCase(member.path("job").asText())) {
                    metadata.addDirector(member.path("name").asText());
                }
            }
        }
        JsonNode createdBy = root.path("created_by");
        if (createdBy.isArray()) {
            for (JsonNode creator : createdBy) {
                metadata.addDirector(creator.path("name").asText());
            }
        }
    }

    private URI buildSearchUri(String searchType, String apiKey, MetadataSearchContext context, int page) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.getBaseUrl() + "/search/" + searchType)
                .queryParam("api_key", apiKey)
                .queryParam("query", context.getFullTitle())
                .queryParam("language", properties.getLanguage())
                .queryParam("include_adult", properties.isIncludeAdult())
                .queryParam("page", page);

        String year = cleanYear(context.getYear());
        if (StringUtils.hasText(year)) {
            builder.queryParam("year", year);
        }
        return builder.build().encode().toUri();
    }

    private String determineSearchType(MetadataSearchContext context) {
        String slug = context.getCategorySlug();
        if (slug == null) {
            return "multi";
        }
        if (MOVIE_SLUGS.contains(slug)) {
            return "movie";
        }
        if (TV_SLUGS.contains(slug) || (context.getSeason() != null && context.getSeason() > 0)) {
            return "tv";
        }
        return "multi";
    }

    private String extractMediaType(JsonNode candidate, String searchType) {
        if (candidate.has("media_type")) {
            return candidate.path("media_type").asText();
        }
        if ("movie".equals(searchType)) {
            return "movie";
        }
        if ("tv".equals(searchType)) {
            return "tv";
        }
        return "unknown";
    }

    private boolean isMatch(MetadataSearchContext source, JsonNode candidate, String mediaType) {
        if (source.getSeason() != null && source.getSeason() > 0 && "movie".equals(mediaType)) {
            return false;
        }

        String candidateTitle = "movie".equals(mediaType)
                ? candidate.path("title").asText()
                : candidate.path("name").asText();
        String candidateOriginalTitle = "movie".equals(mediaType)
                ? candidate.path("original_title").asText()
                : candidate.path("original_name").asText();
        String candidateDate = "movie".equals(mediaType)
                ? candidate.path("release_date").asText()
                : candidate.path("first_air_date").asText();

        if (!yearMatches(source.getYear(), candidateDate)) {
            return false;
        }

        String sourceFullTitle = normalize(source.getFullTitle());
        String sourceTitle = normalize(source.getTitle());
        String sourceAlias = normalize(source.getAliasTitle());
        String targetTitle = normalize(candidateTitle);
        String targetOriginal = normalize(candidateOriginalTitle);

        return equalsOrContains(targetTitle, sourceFullTitle)
                || equalsOrContains(targetOriginal, sourceFullTitle)
                || equalsOrContains(targetTitle, sourceTitle)
                || equalsOrContains(targetOriginal, sourceTitle)
                || (StringUtils.hasText(sourceAlias)
                && (targetTitle.equals(sourceAlias) || targetOriginal.equals(sourceAlias)));
    }

    private boolean yearMatches(String sourceYear, String candidateDate) {
        String cleanYear = cleanYear(sourceYear);
        if (!StringUtils.hasText(cleanYear) || !StringUtils.hasText(candidateDate) || candidateDate.length() < 4) {
            return true;
        }
        try {
            int source = Integer.parseInt(cleanYear);
            int target = Integer.parseInt(candidateDate.substring(0, 4));
            return Math.abs(source - target) <= 1;
        } catch (NumberFormatException ignored) {
            return true;
        }
    }

    private String cleanYear(String year) {
        if (!StringUtils.hasText(year)) {
            return null;
        }
        String cleanYear = year.replaceAll("\\D", "");
        return cleanYear.length() == 4 ? cleanYear : null;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase()
                .replaceAll("[\\p{Punct}\\s]+", "")
                .trim();
    }

    private boolean equalsOrContains(String target, String source) {
        return StringUtils.hasText(target)
                && StringUtils.hasText(source)
                && (target.equals(source) || target.contains(source));
    }
}
