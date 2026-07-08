package com.prodigalgal.ircs.normalization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hankcs.hanlp.corpus.tag.Nature;
import com.hankcs.hanlp.seg.common.Term;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DescriptionMetadataExtractorTest {

    @Test
    void extractsDirectorAndActorNamesAroundV1KeywordWindows() {
        DescriptionMetadataExtractor extractor = new StubExtractor(List.of(
                new Term("王家卫", Nature.nr),
                new Term("执导", Nature.v),
                new Term("，", Nature.w),
                new Term("梁朝伟", Nature.nr),
                new Term("、", Nature.w),
                new Term("张曼玉", Nature.nr),
                new Term("主演", Nature.v)));

        DescriptionMetadataExtractor.ExtractedMetadata result = extractor.extract("fixture");

        assertEquals(Set.of("梁朝伟", "张曼玉"), result.actors());
        assertEquals(Set.of("王家卫", "梁朝伟"), result.directors());
    }

    @Test
    void ignoresNonPersonTermsAndSingleCharacterNames() {
        DescriptionMetadataExtractor extractor = new StubExtractor(List.of(
                new Term("张", Nature.nr),
                new Term("导演", Nature.n),
                new Term("电影", Nature.n),
                new Term("李四", Nature.nr)));

        DescriptionMetadataExtractor.ExtractedMetadata result = extractor.extract("fixture");

        assertEquals(Set.of(), result.actors());
        assertEquals(Set.of("李四"), result.directors());
    }

    private static class StubExtractor extends DescriptionMetadataExtractor {

        private final List<Term> terms;

        StubExtractor(List<Term> terms) {
            this.terms = terms;
        }

        @Override
        List<Term> segment(String description) {
            return terms;
        }
    }
}
