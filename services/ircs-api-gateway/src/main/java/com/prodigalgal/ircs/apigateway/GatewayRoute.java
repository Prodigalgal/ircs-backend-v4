package com.prodigalgal.ircs.apigateway;

record GatewayRoute(String requestPrefix, String targetBaseUrl, String targetPrefix) {

    boolean matches(String path) {
        return path.equals(requestPrefix) || path.startsWith(requestPrefix + "/");
    }

    ResolvedRoute resolve(String path) {
        String suffix = path.substring(requestPrefix.length());
        String targetPath = targetPrefix + suffix;
        return new ResolvedRoute(trimTrailingSlash(targetBaseUrl), targetPath.isBlank() ? "/" : targetPath);
    }

    private static String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
