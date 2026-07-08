package com.prodigalgal.ircs.storage.image;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@Slf4j
public class StorageMediaWebConfig implements WebMvcConfigurer {

    private static final String DEFAULT_PUBLIC_PATH = "/media";

    private final String basePath;
    private final String publicPath;

    public StorageMediaWebConfig(
            @Value("${app.storage.base-path:./storage}") String basePath,
            @Value("${app.storage.public-path:}") String publicPath) {
        this.basePath = basePath;
        this.publicPath = publicPath;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String pattern = mediaPattern(publicPath);
        String location = fileLocation(basePath);
        log.info("Mapping storage media URL pattern '{}' to physical location '{}'", pattern, location);
        registry.addResourceHandler(pattern)
                .addResourceLocations(location)
                .setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic());
    }

    static String mediaPattern(String configuredPublicPath) {
        String base = StringUtils.hasText(configuredPublicPath) ? configuredPublicPath.trim() : DEFAULT_PUBLIC_PATH;
        if (!base.startsWith("/")) {
            base = "/" + base;
        }
        return base.endsWith("/") ? base + "**" : base + "/**";
    }

    static String fileLocation(String configuredBasePath) {
        String base = StringUtils.hasText(configuredBasePath) ? configuredBasePath.trim() : "./storage";
        return Path.of(base).normalize().toAbsolutePath().toUri().toString();
    }
}
