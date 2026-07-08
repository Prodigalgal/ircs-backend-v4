package com.prodigalgal.ircs.search.document;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.CompletionField;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.MultiField;
import org.springframework.data.elasticsearch.annotations.Setting;
import org.springframework.data.elasticsearch.core.suggest.Completion;

@Data
@Document(indexName = UnifiedVideoSearchDocument.INDEX_NAME)
@Setting(settingPath = "elasticsearch/es-settings.json")
public class UnifiedVideoSearchDocument {

    public static final String INDEX_NAME = "ircs_unified_video";

    @Id
    @Field(type = FieldType.Keyword)
    private UUID id;

    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart"),
            otherFields = {
                    @InnerField(suffix = "keyword", type = FieldType.Keyword, ignoreAbove = 256),
                    @InnerField(suffix = "pinyin", type = FieldType.Text, analyzer = "pinyin")
            })
    private String title;

    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword, ignoreAbove = 256))
    private String aliasTitle;

    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword, ignoreAbove = 256))
    private String normalizedTitle;

    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword, ignoreAbove = 256))
    private String normalizedAliasTitle;

    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword, ignoreAbove = 256))
    private List<String> titleVariants;

    @Field(type = FieldType.Keyword)
    private List<String> externalIds;

    @Field(type = FieldType.Keyword, index = false)
    private String coverImageUrl;

    @Field(type = FieldType.Text, analyzer = "ik_smart")
    private String description;

    @Field(type = FieldType.Double)
    private Double score;

    @Field(type = FieldType.Integer)
    private Integer year;

    @Field(type = FieldType.Keyword)
    private String remarks;

    @Field(type = FieldType.Keyword)
    private String totalEpisodes;

    @Field(type = FieldType.Keyword)
    private String duration;

    @Field(type = FieldType.Integer)
    private Integer season;

    @Field(type = FieldType.Keyword)
    private String subtitle;

    @Field(type = FieldType.Keyword)
    private String categorySlug;

    @Field(type = FieldType.Keyword)
    private String categoryName;

    @Field(type = FieldType.Keyword)
    private UUID categoryId;

    @Field(type = FieldType.Keyword)
    private String contentVisibility;

    @Field(type = FieldType.Keyword)
    private String metadataStatus;

    @Field(type = FieldType.Boolean)
    private boolean adultRestricted;

    @Field(type = FieldType.Boolean)
    private boolean adultAssessmentRestricted;

    @Field(type = FieldType.Keyword)
    private String adultAssessmentLevel;

    @Field(type = FieldType.Date)
    private Instant adultCheckedAt;

    @Field(type = FieldType.Keyword)
    private List<String> genres;

    @Field(type = FieldType.Keyword)
    private List<String> genreCodes;

    @Field(type = FieldType.Keyword)
    private List<String> tags;

    @Field(type = FieldType.Keyword)
    private List<String> areas;

    @Field(type = FieldType.Keyword)
    private List<String> areaCodes;

    @Field(type = FieldType.Keyword)
    private List<String> languages;

    @Field(type = FieldType.Keyword)
    private List<String> languageCodes;

    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword))
    private List<String> actors;

    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword))
    private List<String> directors;

    @Field(type = FieldType.Boolean)
    private boolean hasDouban;

    @Field(type = FieldType.Boolean)
    private boolean hasTmdb;

    @Field(type = FieldType.Boolean)
    private boolean hasImdb;

    @Field(type = FieldType.Integer)
    private int sourceCount;

    @Field(type = FieldType.Date)
    private Instant lastTrendAt;

    @Field(type = FieldType.Date)
    private Instant publishedAt;

    @Field(type = FieldType.Date)
    private Instant createdAt;

    @Field(type = FieldType.Date)
    private Instant updatedAt;

    @CompletionField(maxInputLength = 100, preserveSeparators = true, preservePositionIncrements = true)
    @JsonIgnore
    private Completion suggestion;
}
