package com.prodigalgal.ircs.normalization;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.common.Term;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
public class DescriptionMetadataExtractor {

    private static final Pattern DIRECTOR_KEYWORDS = Pattern.compile("导演|执导|监督");
    private static final Pattern ACTOR_KEYWORDS = Pattern.compile("主演|饰演|出演|演员");

    public ExtractedMetadata extract(String description) {
        if (!StringUtils.hasText(description)) {
            return ExtractedMetadata.empty();
        }

        Set<String> actors = new LinkedHashSet<>();
        Set<String> directors = new LinkedHashSet<>();
        try {
            List<Term> terms = segment(description);
            for (int i = 0; i < terms.size(); i++) {
                String word = terms.get(i).word;
                if (DIRECTOR_KEYWORDS.matcher(word).find()) {
                    directors.addAll(findPersonNamesAround(terms, i, 3));
                } else if (ACTOR_KEYWORDS.matcher(word).find()) {
                    actors.addAll(findPersonNamesAround(terms, i, 5));
                }
            }
        } catch (RuntimeException ex) {
            log.warn("HanLP description metadata extraction failed: {}", ex.getMessage());
        }
        return new ExtractedMetadata(actors, directors);
    }

    List<Term> segment(String description) {
        return HanLP.segment(description);
    }

    private Set<String> findPersonNamesAround(List<Term> terms, int centerIndex, int range) {
        Set<String> names = new LinkedHashSet<>();
        int start = Math.max(0, centerIndex - range);
        int end = Math.min(terms.size() - 1, centerIndex + range);

        for (int i = start; i <= end; i++) {
            if (i == centerIndex) {
                continue;
            }
            Term term = terms.get(i);
            if (term.nature != null
                    && term.nature.toString().startsWith("nr")
                    && term.word != null
                    && term.word.length() > 1) {
                names.add(term.word);
            }
        }
        return names;
    }

    public record ExtractedMetadata(Set<String> actors, Set<String> directors) {

        static ExtractedMetadata empty() {
            return new ExtractedMetadata(Set.of(), Set.of());
        }
    }
}
