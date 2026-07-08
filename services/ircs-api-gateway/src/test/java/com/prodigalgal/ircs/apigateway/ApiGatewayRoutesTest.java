package com.prodigalgal.ircs.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiGatewayRoutesTest {

    private final ApiGatewayRoutes routes = new ApiGatewayRoutes(
            "http://identity",
            "http://content",
            "http://catalog",
            "http://aggregation",
            "http://task",
            "http://ops",
            "http://ops-alert",
            "http://portal",
            "http://search",
            "http://storage",
            "http://config",
            "http://credential",
            "http://magnet",
            "http://scraper",
            "http://interaction"
    );

    @Test
    void routesDashboardToOpsService() {
        ResolvedRoute route = routes.resolve("/api/v1/dashboard/statistics").orElseThrow();

        assertThat(route.targetBaseUrl()).isEqualTo("http://ops");
        assertThat(route.targetPath()).isEqualTo("/api/v1/dashboard/statistics");
    }

    @Test
    void routesOpsAlertBeforeGenericOpsService() {
        ResolvedRoute route = routes.resolve("/api/v1/ops-alert/incidents").orElseThrow();

        assertThat(route.targetBaseUrl()).isEqualTo("http://ops-alert");
        assertThat(route.targetPath()).isEqualTo("/api/v1/ops-alert/incidents");
    }

    @Test
    void routesRawVideosBeforeRawDictionaryRoutes() {
        ResolvedRoute route = routes.resolve("/api/v1/raw-videos/batch-sync-search").orElseThrow();

        assertThat(route.targetBaseUrl()).isEqualTo("http://content");
        assertThat(route.targetPath()).isEqualTo("/api/v1/raw-videos/batch-sync-search");
    }

    @Test
    void routesContentSupportingResourcesToContentService() {
        assertThat(routes.resolve("/api/v1/source-domains").orElseThrow().targetBaseUrl()).isEqualTo("http://content");
        assertThat(routes.resolve("/api/v1/resolvers/test").orElseThrow().targetBaseUrl()).isEqualTo("http://content");
    }

    @Test
    void routesAggregationActionsToAggregationWorker() {
        ResolvedRoute route = routes.resolve("/api/v1/aggregation/unified-videos/merge").orElseThrow();

        assertThat(route.targetBaseUrl()).isEqualTo("http://aggregation");
        assertThat(route.targetPath()).isEqualTo("/api/v1/aggregation/unified-videos/merge");
    }

    @Test
    void routesDebugMetadataToOpsService() {
        ResolvedRoute route = routes.resolve("/api/v1/debug/metadata").orElseThrow();

        assertThat(route.targetBaseUrl()).isEqualTo("http://ops");
        assertThat(route.targetPath()).isEqualTo("/api/v1/debug/metadata");
    }

    @Test
    void routesMessagesToInteractionService() {
        ResolvedRoute route = routes.resolve("/api/v1/messages/123/reply").orElseThrow();

        assertThat(route.targetBaseUrl()).isEqualTo("http://interaction");
        assertThat(route.targetPath()).isEqualTo("/api/v1/messages/123/reply");
    }

    @Test
    void routesTaskRuntimeToTaskService() {
        ResolvedRoute route = routes.resolve("/api/v1/collection-tasks/123/runtime").orElseThrow();

        assertThat(route.targetBaseUrl()).isEqualTo("http://task");
        assertThat(route.targetPath()).isEqualTo("/api/v1/collection-tasks/123/runtime");
    }

    @Test
    void routesMediaRequestBatchesToTaskService() {
        ResolvedRoute route = routes.resolve("/api/v1/media-request-batches/123/start").orElseThrow();

        assertThat(route.targetBaseUrl()).isEqualTo("http://task");
        assertThat(route.targetPath()).isEqualTo("/api/v1/media-request-batches/123/start");
    }

    @Test
    void routesSearchOpsToSearchService() {
        ResolvedRoute route = routes.resolve("/api/v1/search/ops/search-sync-tasks/stats").orElseThrow();

        assertThat(route.targetBaseUrl()).isEqualTo("http://search");
        assertThat(route.targetPath()).isEqualTo("/api/v1/search/ops/search-sync-tasks/stats");
    }

    @Test
    void routesPortalMediaRequestsToInteractionService() {
        ResolvedRoute route = routes.resolve("/api/portal/media-requests").orElseThrow();

        assertThat(route.targetBaseUrl()).isEqualTo("http://interaction");
        assertThat(route.targetPath()).isEqualTo("/api/portal/media-requests");
    }

    @Test
    void rejectsUnknownApiPrefix() {
        assertThat(routes.resolve("/api/unknown/portal/home")).isEmpty();
    }
}
