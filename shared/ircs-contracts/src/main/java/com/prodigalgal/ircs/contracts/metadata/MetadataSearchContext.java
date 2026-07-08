package com.prodigalgal.ircs.contracts.metadata;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetadataSearchContext implements Serializable {
    private UUID videoId;
    private String pipelineVersion;
    private String title;
    private String aliasTitle;
    private String subtitle;
    private Integer season;
    private String year;
    private String categorySlug;
    private String doubanId;
    private String tmdbId;
    private String imdbId;
    private String rottenTomatoesId;

    public String getFullTitle() {
        StringBuilder titleBuilder = new StringBuilder(title == null ? "" : title);
        if (subtitle != null && !subtitle.isBlank()) {
            titleBuilder.append(" ").append(subtitle);
        }
        return titleBuilder.toString();
    }
}
