package com.prodigalgal.ircs.contracts.route;

public enum ServiceRoute {
    IDENTITY("ircs-identity-service", "/api/v1/auth/**", "/api/v1/members/**", "/api/portal/auth/**", "/api/portal/profile/**"),
    CONTENT("ircs-content-service", "/api/v1/raw-videos/**", "/api/v1/unified-videos/**", "/api/v1/playlists/**"),
    CATALOG("ircs-catalog-service", "/api/v1/standard-*/**", "/api/v1/raw-*/**", "/api/v1/data-sources/**"),
    TASK("ircs-task-service", "/api/v1/collection-tasks/**", "/api/v1/media-request-batches/**"),
    PORTAL("ircs-portal-service", "/api/portal/**"),
    SCRAPER("ircs-scraper-service", "/api/v1/scraper/manual/**"),
    MAGNET("ircs-magnet-service", "/api/v1/magnets/**", "/api/v1/magnet-providers/**"),
    SEARCH("ircs-search-service", "/api/portal/search/**"),
    STORAGE("ircs-storage-service", "/api/v1/cover-images/**", "/media/**"),
    INTERACTION("ircs-interaction-service", "/api/v1/messages/**", "/api/portal/interaction/**", "/api/portal/feedback/**"),
    CONFIG("ircs-config-service", "/api/v1/configs/**", "/api/v1/common/**"),
    CREDENTIAL("ircs-credential-service", "/api/v1/credentials/**"),
    OPS("ircs-ops-service", "/api/v1/ops/**", "/api/v1/dashboard/**", "/api/v1/debug/**");

    private final String serviceName;
    private final String[] paths;

    ServiceRoute(String serviceName, String... paths) {
        this.serviceName = serviceName;
        this.paths = paths;
    }

    public String serviceName() {
        return serviceName;
    }

    public String[] paths() {
        return paths.clone();
    }
}
