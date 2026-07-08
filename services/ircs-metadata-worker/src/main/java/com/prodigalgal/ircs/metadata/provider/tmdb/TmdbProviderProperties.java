package com.prodigalgal.ircs.metadata.provider.tmdb;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.metadata.tmdb")
@Data
public class TmdbProviderProperties {

    private String baseUrl = "https://api.themoviedb.org/3";
    private String imageBaseUrl = "https://image.tmdb.org/t/p/w500";
    private String backdropBaseUrl = "https://image.tmdb.org/t/p/w1280";
    private String language = "zh-CN";
    private boolean includeAdult = false;
    private int maxMultiPages = 3;
    private Duration maxWait = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private int defaultRateLimit = 30;
    private String defaultRateLimitUnit = "MINUTE";
}
