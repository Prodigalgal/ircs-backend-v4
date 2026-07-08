package com.prodigalgal.ircs.identity;

public final class IdentityRedisKeys {

    private IdentityRedisKeys() {
    }

    public static String authStatus(Object id) {
        return "auth:status:" + id;
    }

    public static String authActivate(Object email) {
        return "auth:activate:" + email;
    }

    public static String authResetToken(Object token) {
        return "auth:reset_token:" + token;
    }

    public static String authResetRequest(Object email) {
        return "auth:reset_request:" + email;
    }

    public static String authResetAttempt(Object tokenHash) {
        return "auth:reset_attempt:" + tokenHash;
    }

    public static String authRateLimit(Object bucket) {
        return "auth:rate_limit:" + bucket;
    }

    public static String oauthState(Object state) {
        return "auth:oauth_state:" + state;
    }

    public static String powChallenge(Object id) {
        return "pow:challenge:" + id;
    }
}
