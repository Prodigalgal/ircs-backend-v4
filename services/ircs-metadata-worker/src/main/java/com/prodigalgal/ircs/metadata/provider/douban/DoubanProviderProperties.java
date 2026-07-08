package com.prodigalgal.ircs.metadata.provider.douban;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.metadata.douban")
@Data
public class DoubanProviderProperties {

    private String suggestUrl = "https://movie.douban.com/j/subject_suggest";
    private Duration requestTimeout = Duration.ofSeconds(10);
    private String defaultUserAgent = "IRCS-Metadata-Douban/0.1";
}
