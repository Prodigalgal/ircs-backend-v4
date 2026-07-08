package com.prodigalgal.ircs.common.security;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

public class IrcsJwtRuntimeConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(IrcsJwtRuntimeConfigResolver.class);
    private static final String JWT_SECRET_DB_KEY = "security.jwt.secret";
    private static final String JWT_IAT_FLOOR_DB_KEY = "security.jwt.iat-floor";
    private static final String JWT_SECRET_FALLBACK = "";
    private static final String IAT_FLOOR_FALLBACK = "0";
    private static final String SELECT_SQL = "select config_value from system_configs where config_key = ?";

    private final Environment environment;
    private final String datasourceUrl;
    private final String username;
    private final String password;

    public IrcsJwtRuntimeConfigResolver(
            Environment environment,
            String datasourceUrl,
            String username,
            String password) {
        this.environment = environment;
        this.datasourceUrl = normalize(datasourceUrl);
        this.username = normalize(username);
        this.password = password == null ? "" : password;
    }

    public String jwtSecret() {
        return value(
                JWT_SECRET_DB_KEY,
                List.of("app.identity.jwt.secret", "APP_IDENTITY_JWT_SECRET", "security.jwt.secret", "SECURITY_JWT_SECRET"),
                JWT_SECRET_FALLBACK);
    }

    public long iatFloorSeconds() {
        String raw = value(
                JWT_IAT_FLOOR_DB_KEY,
                List.of("app.identity.jwt.iat-floor", "APP_IDENTITY_JWT_IAT_FLOOR", "security.jwt.iat-floor", "SECURITY_JWT_IAT_FLOOR"),
                IAT_FLOOR_FALLBACK);
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String value(String dbKey, List<String> injectedKeys, String fallback) {
        for (String key : injectedKeys) {
            String value = environment == null ? null : normalize(environment.getProperty(key));
            if (value != null) {
                return value;
            }
        }
        return findDbValue(dbKey).orElse(fallback);
    }

    private Optional<String> findDbValue(String key) {
        if (!StringUtils.hasText(datasourceUrl)) {
            return Optional.empty();
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(SELECT_SQL)) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return Optional.ofNullable(normalize(result.getString(1)));
                }
            }
        } catch (SQLException ex) {
            log.warn("JWT runtime config DB lookup failed for [{}]: {}", key, ex.getMessage());
        }
        return Optional.empty();
    }

    private Connection connection() throws SQLException {
        if (username == null) {
            return DriverManager.getConnection(datasourceUrl);
        }
        return DriverManager.getConnection(datasourceUrl, username, password);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
