package com.prodigalgal.ircs.apigateway;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ApiGatewayRoutes extends GatewayRouteTable {

    ApiGatewayRoutes(
            @Value("${app.gateway.targets.identity}") String identityBaseUrl,
            @Value("${app.gateway.targets.content}") String contentBaseUrl,
            @Value("${app.gateway.targets.catalog}") String catalogBaseUrl,
            @Value("${app.gateway.targets.aggregation}") String aggregationBaseUrl,
            @Value("${app.gateway.targets.task}") String taskBaseUrl,
            @Value("${app.gateway.targets.ops}") String opsBaseUrl,
            @Value("${app.gateway.targets.ops-alert}") String opsAlertBaseUrl,
            @Value("${app.gateway.targets.portal}") String portalBaseUrl,
            @Value("${app.gateway.targets.search}") String searchBaseUrl,
            @Value("${app.gateway.targets.storage}") String storageBaseUrl,
            @Value("${app.gateway.targets.config}") String configBaseUrl,
            @Value("${app.gateway.targets.credential}") String credentialBaseUrl,
            @Value("${app.gateway.targets.magnet}") String magnetBaseUrl,
            @Value("${app.gateway.targets.scraper}") String scraperBaseUrl,
            @Value("${app.gateway.targets.interaction}") String interactionBaseUrl) {
        super(List.of(
                new GatewayRoute("/api/v1/auth", identityBaseUrl, "/api/v1/auth"),
                new GatewayRoute("/api/v1/members", identityBaseUrl, "/api/v1/members"),
                new GatewayRoute("/api/portal/auth", identityBaseUrl, "/api/portal/auth"),
                new GatewayRoute("/api/portal/profile", identityBaseUrl, "/api/portal/profile"),
                new GatewayRoute("/api/portal/interaction", interactionBaseUrl, "/api/portal/interaction"),
                new GatewayRoute("/api/portal/feedback", interactionBaseUrl, "/api/portal/feedback"),
                new GatewayRoute("/api/portal/media-requests", interactionBaseUrl, "/api/portal/media-requests"),
                new GatewayRoute("/api/portal/search", searchBaseUrl, "/api/portal/search"),
                new GatewayRoute("/api/portal", portalBaseUrl, "/api/portal"),
                new GatewayRoute("/api/v1/dashboard", opsBaseUrl, "/api/v1/dashboard"),
                new GatewayRoute("/api/v1/ops-alert", opsAlertBaseUrl, "/api/v1/ops-alert"),
                new GatewayRoute("/api/v1/ops", opsBaseUrl, "/api/v1/ops"),
                new GatewayRoute("/api/v1/search/ops", searchBaseUrl, "/api/v1/search/ops"),
                new GatewayRoute("/api/v1/raw-videos", contentBaseUrl, "/api/v1/raw-videos"),
                new GatewayRoute("/api/v1/unified-videos", contentBaseUrl, "/api/v1/unified-videos"),
                new GatewayRoute("/api/v1/playlists", contentBaseUrl, "/api/v1/playlists"),
                new GatewayRoute("/api/v1/source-domains", contentBaseUrl, "/api/v1/source-domains"),
                new GatewayRoute("/api/v1/resolvers", contentBaseUrl, "/api/v1/resolvers"),
                new GatewayRoute("/api/v1/cover-images", storageBaseUrl, "/api/v1/cover-images"),
                new GatewayRoute("/api/v1/collection-tasks", taskBaseUrl, "/api/v1/collection-tasks"),
                new GatewayRoute("/api/v1/media-request-batches", taskBaseUrl, "/api/v1/media-request-batches"),
                new GatewayRoute("/api/v1/catalog", catalogBaseUrl, "/api/v1/catalog"),
                new GatewayRoute("/api/v1/standard-categories", catalogBaseUrl, "/api/v1/standard-categories"),
                new GatewayRoute("/api/v1/standard-genres", catalogBaseUrl, "/api/v1/standard-genres"),
                new GatewayRoute("/api/v1/standard-areas", catalogBaseUrl, "/api/v1/standard-areas"),
                new GatewayRoute("/api/v1/standard-languages", catalogBaseUrl, "/api/v1/standard-languages"),
                new GatewayRoute("/api/v1/data-sources", catalogBaseUrl, "/api/v1/data-sources"),
                new GatewayRoute("/api/v1/aggregation", aggregationBaseUrl, "/api/v1/aggregation"),
                new GatewayRoute("/api/v1/configs", configBaseUrl, "/api/v1/configs"),
                new GatewayRoute("/api/v1/common", configBaseUrl, "/api/v1/common"),
                new GatewayRoute("/api/v1/credentials", credentialBaseUrl, "/api/v1/credentials"),
                new GatewayRoute("/api/v1/magnet-providers", magnetBaseUrl, "/api/v1/magnet-providers"),
                new GatewayRoute("/api/v1/magnets", magnetBaseUrl, "/api/v1/magnets"),
                new GatewayRoute("/api/v1/scraper/manual", scraperBaseUrl, "/api/v1/scraper/manual"),
                new GatewayRoute("/api/v1/debug", opsBaseUrl, "/api/v1/debug"),
                new GatewayRoute("/api/v1/messages", interactionBaseUrl, "/api/v1/messages"),
                new GatewayRoute("/media", storageBaseUrl, "/media")
        ));
    }
}
