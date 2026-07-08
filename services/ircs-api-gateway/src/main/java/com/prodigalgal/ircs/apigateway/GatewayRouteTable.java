package com.prodigalgal.ircs.apigateway;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

class GatewayRouteTable {

    private final List<GatewayRoute> routes;

    GatewayRouteTable(List<GatewayRoute> routes) {
        this.routes = routes.stream()
                .sorted(Comparator.comparingInt((GatewayRoute route) -> route.requestPrefix().length()).reversed())
                .toList();
    }

    Optional<ResolvedRoute> resolve(String path) {
        return routes.stream()
                .filter(route -> route.matches(path))
                .findFirst()
                .map(route -> route.resolve(path));
    }
}
