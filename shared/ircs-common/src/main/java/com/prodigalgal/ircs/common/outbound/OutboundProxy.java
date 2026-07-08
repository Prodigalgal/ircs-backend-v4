package com.prodigalgal.ircs.common.outbound;

public record OutboundProxy(
        boolean enabled,
        String host,
        Integer port,
        String username,
        String password) {

    public static OutboundProxy disabled() {
        return new OutboundProxy(false, null, null, null, null);
    }

    public static OutboundProxy http(String host, Integer port, String username, String password) {
        return new OutboundProxy(true, host, port, username, password);
    }

    public boolean hasCredentials() {
        return hasText(username);
    }

    void validate() throws OutboundHttpException {
        if (!enabled) {
            return;
        }
        if (!hasText(host)) {
            throw new OutboundHttpException("Outbound proxy host is required");
        }
        if (port == null || port < 1 || port > 65535) {
            throw new OutboundHttpException("Outbound proxy port is invalid");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
