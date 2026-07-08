package com.prodigalgal.ircs.contracts.metadata;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import lombok.Data;

@Data
public class EnrichedMetadataDTO implements Serializable {
    private String doubanId;
    private String tmdbId;
    private String imdbId;
    private String rottenTomatoesId;
    private String title;
    private String originalTitle;
    private String description;
    private String posterUrl;
    private String backdropUrl;
    private ProviderType imageSource;
    private String year;
    private LocalDate publishedAt;
    private String area;
    private String language;
    private BigDecimal score;
    private Set<String> actorNames = new HashSet<>();
    private Set<String> directorNames = new HashSet<>();
    private Set<String> genreNames = new HashSet<>();

    public void addActor(String actorName) {
        if (actorName != null && !actorName.isBlank()) {
            actorNames.add(actorName);
        }
    }

    public void addDirector(String directorName) {
        if (directorName != null && !directorName.isBlank()) {
            directorNames.add(directorName);
        }
    }

    public void addGenre(String genreName) {
        if (genreName != null && !genreName.isBlank()) {
            genreNames.add(genreName);
        }
    }
}
