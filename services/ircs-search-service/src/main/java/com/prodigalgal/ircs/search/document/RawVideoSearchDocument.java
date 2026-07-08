package com.prodigalgal.ircs.search.document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.MultiField;
import org.springframework.data.elasticsearch.annotations.Setting;

@Data
@Document(indexName = RawVideoSearchDocument.INDEX_NAME)
@Setting(settingPath = "elasticsearch/es-settings.json")
public class RawVideoSearchDocument {

    public static final String INDEX_NAME = "ircs_raw_video";

    @Id
    @Field(type = FieldType.Keyword)
    private UUID id;

    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword, ignoreAbove = 256))
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

    @Field(type = FieldType.Keyword)
    private String sourceVid;

    @Field(type = FieldType.Keyword)
    private String year;

    @Field(type = FieldType.Double)
    private Double score;

    @Field(type = FieldType.Keyword)
    private String totalEpisodes;

    @Field(type = FieldType.Keyword)
    private String duration;

    @Field(type = FieldType.Integer)
    private Integer season;

    @Field(type = FieldType.Keyword)
    private String subtitle;

    @Field(type = FieldType.Keyword)
    private String dataSourceName;

    @Field(type = FieldType.Keyword)
    private UUID dataSourceId;

    @Field(type = FieldType.Keyword)
    private String categoryName;

    @Field(type = FieldType.Keyword)
    private UUID categoryId;

    @Field(type = FieldType.Keyword)
    private String categoryCode;

    @Field(type = FieldType.Keyword)
    private String sourceCategoryName;

    @Field(type = FieldType.Keyword)
    private String sourceCategoryCode;

    @Field(type = FieldType.Keyword)
    private List<String> rawGenres;

    @Field(type = FieldType.Keyword)
    private List<String> rawAreas;

    @Field(type = FieldType.Keyword)
    private List<String> rawLanguages;

    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword, ignoreAbove = 256))
    private List<String> actors;

    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword, ignoreAbove = 256))
    private List<String> directors;

    @Field(type = FieldType.Keyword)
    private String enrichmentStatus;

    @Field(type = FieldType.Keyword)
    private String normalizationStatus;

    @Field(type = FieldType.Keyword)
    private String aggregationStatus;

    @Field(type = FieldType.Boolean)
    private boolean hasDoubanId;

    @Field(type = FieldType.Boolean)
    private boolean hasTmdbId;

    @Field(type = FieldType.Boolean)
    private boolean isMissingSlug;

    @Field(type = FieldType.Keyword, index = false)
    private String coverImageUrl;

    @Field(type = FieldType.Keyword)
    private String coverStorageType;

    @Field(type = FieldType.Keyword)
    private String coverStatus;

    @Field(type = FieldType.Date)
    private Instant createdAt;

    @Field(type = FieldType.Date)
    private Instant updatedAt;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String metadata;

    @Transient
    private String rawLanguagesAsCsv;

    @Transient
    private String descriptionForMetadata;
}
