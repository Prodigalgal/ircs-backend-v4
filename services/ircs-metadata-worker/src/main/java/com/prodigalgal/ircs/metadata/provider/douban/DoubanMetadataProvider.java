package com.prodigalgal.ircs.metadata.provider.douban;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.metadata.EnrichedMetadataDTO;
import com.prodigalgal.ircs.contracts.metadata.MetadataSearchContext;
import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProvider;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderRetryableException;
import java.net.URI;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class DoubanMetadataProvider implements MetadataProvider {

    private final DoubanCredentialRepository credentialRepository;
    private final DoubanHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DoubanProviderProperties properties;

    @Override
    public ProviderType getType() {
        return ProviderType.DOUBAN;
    }

    @Override
    public boolean supports(MetadataSearchContext context) {
        return context != null && StringUtils.hasText(context.getFullTitle());
    }

    @Override
    public Optional<EnrichedMetadataDTO> enrich(MetadataSearchContext context) {
        Optional<DoubanCredential> credential = credentialRepository.findPreferred();
        Optional<String> response = httpClient.getJson(buildSuggestUri(context), credential);
        if (response.isEmpty()) {
            return Optional.empty();
        }
        return mapFirstMatch(context, response.get());
    }

    private URI buildSuggestUri(MetadataSearchContext context) {
        return UriComponentsBuilder.fromUriString(properties.getSuggestUrl())
                .queryParam("q", context.getFullTitle())
                .build()
                .encode()
                .toUri();
    }

    private Optional<EnrichedMetadataDTO> mapFirstMatch(MetadataSearchContext context, String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            if (!root.isArray()) {
                return Optional.empty();
            }
            for (JsonNode candidate : root) {
                if (isMatch(context, candidate)) {
                    return Optional.of(mapCandidate(candidate));
                }
            }
            return Optional.empty();
        } catch (Exception ex) {
            throw new MetadataProviderRetryableException("PROVIDER_PARSE_ERROR", "Unable to parse Douban suggest response", ex);
        }
    }

    private EnrichedMetadataDTO mapCandidate(JsonNode candidate) {
        EnrichedMetadataDTO metadata = new EnrichedMetadataDTO();
        metadata.setDoubanId(text(candidate, "id"));
        metadata.setTitle(text(candidate, "title"));
        metadata.setOriginalTitle(cleanOriginalTitle(text(candidate, "sub_title")));
        metadata.setYear(cleanYear(text(candidate, "year")));
        String posterUrl = text(candidate, "img");
        if (StringUtils.hasText(posterUrl)) {
            metadata.setPosterUrl(posterUrl);
            metadata.setImageSource(ProviderType.DOUBAN);
        }
        return metadata;
    }

    private boolean isMatch(MetadataSearchContext source, JsonNode candidate) {
        if (!yearMatches(source.getYear(), text(candidate, "year"))) {
            return false;
        }
        String sourceFullTitle = normalize(source.getFullTitle());
        String sourceTitle = normalize(source.getTitle());
        String sourceAlias = normalize(source.getAliasTitle());
        String targetTitle = normalize(text(candidate, "title"));
        String targetOriginal = normalize(cleanOriginalTitle(text(candidate, "sub_title")));
        return equalsOrContains(targetTitle, sourceFullTitle)
                || equalsOrContains(targetOriginal, sourceFullTitle)
                || equalsOrContains(targetTitle, sourceTitle)
                || equalsOrContains(targetOriginal, sourceTitle)
                || (StringUtils.hasText(sourceAlias)
                && (targetTitle.equals(sourceAlias) || targetOriginal.equals(sourceAlias)));
    }

    private boolean yearMatches(String sourceYear, String candidateYear) {
        String source = cleanYear(sourceYear);
        String target = cleanYear(candidateYear);
        if (!StringUtils.hasText(source) || !StringUtils.hasText(target)) {
            return true;
        }
        try {
            return Math.abs(Integer.parseInt(source) - Integer.parseInt(target)) <= 1;
        } catch (NumberFormatException ignored) {
            return true;
        }
    }

    private String cleanYear(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{4})").matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String cleanOriginalTitle(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.replaceFirst("^\\s*原名[:：]\\s*", "").trim();
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

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return StringUtils.hasText(value) && !"null".equals(value) ? value.trim() : null;
    }
}
