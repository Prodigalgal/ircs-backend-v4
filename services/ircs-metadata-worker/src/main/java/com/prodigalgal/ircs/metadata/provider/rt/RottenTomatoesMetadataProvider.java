package com.prodigalgal.ircs.metadata.provider.rt;

import com.prodigalgal.ircs.contracts.metadata.EnrichedMetadataDTO;
import com.prodigalgal.ircs.contracts.metadata.MetadataSearchContext;
import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProvider;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderRetryableException;
import java.net.URI;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class RottenTomatoesMetadataProvider implements MetadataProvider {

    private final RottenTomatoesCredentialRepository credentialRepository;
    private final RottenTomatoesHttpClient httpClient;
    private final RottenTomatoesProviderProperties properties;

    @Override
    public ProviderType getType() {
        return ProviderType.ROTTEN_TOMATOES;
    }

    @Override
    public boolean supports(MetadataSearchContext context) {
        return context != null && StringUtils.hasText(context.getTitle());
    }

    @Override
    public Optional<EnrichedMetadataDTO> enrich(MetadataSearchContext context) {
        Optional<RottenTomatoesCredential> credential = credentialRepository.findPreferred();
        Optional<String> response = httpClient.getHtml(buildSearchUri(context), credential);
        if (response.isEmpty()) {
            return Optional.empty();
        }
        return mapFirstMatch(context, response.get());
    }

    private URI buildSearchUri(MetadataSearchContext context) {
        return UriComponentsBuilder.fromUriString(properties.getSearchUrl())
                .queryParam("search", context.getTitle())
                .build()
                .encode()
                .toUri();
    }

    private Optional<EnrichedMetadataDTO> mapFirstMatch(MetadataSearchContext context, String html) {
        try {
            Document document = Jsoup.parse(html);
            Elements links = document.select("search-page-media-row a[slot=title]");
            if (links.isEmpty()) {
                links = document.select("ul[slot=list] li a");
            }
            for (Element link : links) {
                String title = link.text();
                String id = extractIdFromUrl(link.attr("href"));
                if (StringUtils.hasText(id) && isMatch(context, title)) {
                    EnrichedMetadataDTO metadata = new EnrichedMetadataDTO();
                    metadata.setRottenTomatoesId(id);
                    return Optional.of(metadata);
                }
            }
            return Optional.empty();
        } catch (Exception ex) {
            throw new MetadataProviderRetryableException("PROVIDER_PARSE_ERROR", "Unable to parse Rotten Tomatoes search response", ex);
        }
    }

    private boolean isMatch(MetadataSearchContext source, String resultTitle) {
        String sourceTitle = normalize(source.getTitle());
        String sourceFullTitle = normalize(source.getFullTitle());
        String sourceAlias = normalize(source.getAliasTitle());
        String target = normalize(resultTitle);
        return equalsOrContains(target, sourceTitle)
                || equalsOrContains(target, sourceFullTitle)
                || (StringUtils.hasText(sourceAlias) && equalsOrContains(target, sourceAlias));
    }

    private String extractIdFromUrl(String href) {
        if (!StringUtils.hasText(href)) {
            return null;
        }
        try {
            String normalized = href.trim();
            if (!normalized.startsWith("http")) {
                normalized = "https://www.rottentomatoes.com" + (normalized.startsWith("/") ? "" : "/") + normalized;
            }
            String path = URI.create(normalized).getPath();
            if (!StringUtils.hasText(path)) {
                return null;
            }
            String[] segments = path.replaceFirst("/+$", "").split("/");
            return segments.length == 0 ? null : segments[segments.length - 1];
        } catch (Exception ignored) {
            return null;
        }
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
                && (target.equals(source) || target.contains(source) || source.contains(target));
    }
}
