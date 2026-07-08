package com.prodigalgal.ircs.identity.domain;

public enum IdentityConfigKey {
    ADMIN_USERNAME("security.admin.username", "app.identity.admin.username", "admin"),
    ADMIN_PASSWORD("security.admin.password", "app.identity.admin.password-hash", ""),
    JWT_SECRET("security.jwt.secret", "app.identity.jwt.secret", ""),
    JWT_IAT_FLOOR("security.jwt.iat-floor", "app.identity.jwt.iat-floor", "0"),
    MEMBER_CODE_VALIDITY_SECONDS("member.auth.code.validity-seconds", "app.identity.code.validity", "900"),
    MEMBER_RATE_LIMIT_SECONDS("member.auth.code.rate-limit-seconds", "app.identity.code.rate-limit", "120"),
    MEMBER_REGISTER_EMAIL_VERIFY_ENABLED(
            "member.register.email-verify.enabled", "app.identity.register.email-verify-enabled", "false"),
    MEMBER_REGISTER_TIMEZONE("member.register.timezone", "app.identity.register.timezone", "Asia/Shanghai"),
    MAIL_ENABLED("app.mail.enabled", "app.identity.mail.enabled", "false"),
    FRONTEND_URL("app.frontend.url", "app.identity.frontend-url", "");

    private final String dbKey;
    private final String propertyKey;
    private final String fallbackValue;

    IdentityConfigKey(String dbKey, String propertyKey, String fallbackValue) {
        this.dbKey = dbKey;
        this.propertyKey = propertyKey;
        this.fallbackValue = fallbackValue;
    }

    public String dbKey() {
        return dbKey;
    }

    public String propertyKey() {
        return propertyKey;
    }

    public String fallbackValue() {
        return fallbackValue;
    }
}
