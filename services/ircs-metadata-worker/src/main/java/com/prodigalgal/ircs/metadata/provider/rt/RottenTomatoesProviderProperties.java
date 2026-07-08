package com.prodigalgal.ircs.metadata.provider.rt;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.metadata.rt")
public class RottenTomatoesProviderProperties {

    private String searchUrl = "https://www.rottentomatoes.com/search";
    private Duration requestTimeout = Duration.ofSeconds(10);
    private String defaultUserAgent = "IRCS-Metadata-RT/0.1";
}
