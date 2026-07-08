package com.prodigalgal.ircs.credential;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CredentialProviderCatalog {

    static final String DEFAULT_OPENAI_BASE_URL = "https://ai.mnnu.eu.org/v1";
    static final String DEFAULT_MAIL_HOST = "smtp.gmail.com";
    static final int DEFAULT_MAIL_PORT = 465;
    static final String DEFAULT_MAIL_PROTOCOL = "smtp";
    static final boolean DEFAULT_MAIL_AUTH = true;
    static final boolean DEFAULT_MAIL_STARTTLS = false;
    static final boolean DEFAULT_MAIL_SSL = true;
    static final int DEFAULT_MAIL_TIMEOUT_MS = 10000;

    public List<CredentialTemplateField> templates(String provider) {
        return switch (normalizeProvider(provider)) {
            case "OPENAI" -> List.of(
                    field("api_key", "OpenAI API Key", "password", true, "sk-...", null,
                            "支持 OpenAI / OneAPI / NewAPI 等兼容 OpenAI API 的密钥。"),
                    field("base_url", "Base URL", "text", true, DEFAULT_OPENAI_BASE_URL, DEFAULT_OPENAI_BASE_URL,
                            "填写网关根路径，例如 https://api.openai.com/v1；不要追加 /chat/completions。"));
            case "TMDB" -> List.of(
                    field("api_key", "TMDB API Key", "text", true, "v3 API Key (32 chars)", null, null));
            case "DOUBAN" -> List.of(
                    field("cookie", "Douban Cookie", "password", false, "可选，用于低频只读查询", null,
                            "为空时 Douban provider 仅使用公开 suggest endpoint。"),
                    field("user_agent", "User-Agent", "text", false, "可选浏览器 UA", null,
                            "为空时使用 metadata-worker 默认 User-Agent。"));
            case "ROTTEN_TOMATOES" -> List.of(
                    field("cookie", "Rotten Tomatoes Cookie", "password", false, "可选，用于低频只读查询", null,
                            "为空时 RT provider 仅使用公开 search 页面。"),
                    field("user_agent", "User-Agent", "text", false, "可选浏览器 UA", null,
                            "为空时使用 metadata-worker 默认 User-Agent。"));
            case "MAIL" -> List.of(
                    field("username", "邮箱账号", "text", true, "e.g. yourname@gmail.com", null,
                            "用于 SMTP 登录的邮箱账号。"),
                    field("password", "邮箱密码/授权码", "password", true, "App Password or Password", null, null),
                    field("smtp_host", "SMTP 服务器", "text", true, DEFAULT_MAIL_HOST, DEFAULT_MAIL_HOST,
                            "此配置与当前邮箱凭证绑定。"),
                    field("smtp_port", "SMTP 端口", "number", true, "465", DEFAULT_MAIL_PORT,
                            "通常为 465(SSL) 或 587(STARTTLS)。"),
                    field("smtp_protocol", "协议", "text", true, DEFAULT_MAIL_PROTOCOL, DEFAULT_MAIL_PROTOCOL,
                            "通常保持 smtp。"),
                    field("smtp_auth", "SMTP 认证", "boolean", false, null, DEFAULT_MAIL_AUTH, null),
                    field("smtp_ssl_enabled", "SSL 加密", "boolean", false, null, DEFAULT_MAIL_SSL,
                            "用于 465 端口。"),
                    field("smtp_starttls_enabled", "STARTTLS", "boolean", false, null, DEFAULT_MAIL_STARTTLS,
                            "用于 587 端口；不能与 SSL 同时开启。"),
                    field("smtp_timeout_ms", "连接超时(ms)", "number", false, "10000", DEFAULT_MAIL_TIMEOUT_MS,
                            "单次 SMTP 连接、读写超时时间。"));
            case "R2" -> List.of(
                    field("account_id", "Cloudflare Account ID", "text", true, null, null, null),
                    field("access_key", "Access Key ID", "text", true, null, null, null),
                    field("secret_key", "Secret Access Key", "password", true, null, null, null));
            default -> throw new IllegalArgumentException("Provider not supported");
        };
    }

    public void validate(CredentialWriteRequest request) {
        Map<String, Object> payload = request.payload();
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
        switch (normalizeProvider(request.provider())) {
            case "OPENAI" -> validateOpenAi(payload);
            case "TMDB" -> requireText(payload, "api_key", "API Key is required");
            case "DOUBAN" -> validateDouban(payload);
            case "ROTTEN_TOMATOES" -> validateRottenTomatoes(payload);
            case "MAIL" -> validateMail(payload);
            case "R2" -> {
                requireText(payload, "account_id", "Account ID required");
                requireText(payload, "access_key", "Access Key required");
                requireText(payload, "secret_key", "Secret Key required");
            }
            default -> throw new IllegalArgumentException("Provider not supported");
        }
        normalizeRateLimitUnit(request.rateLimitUnit());
    }

    public String fingerprintSource(String provider, Map<String, Object> payload) {
        return switch (normalizeProvider(provider)) {
            case "OPENAI" -> resolveOpenAiBaseUrl(payload) + "\n" + requireText(payload, "api_key", "API Key is required");
            case "TMDB" -> requireText(payload, "api_key", "API Key is required");
            case "DOUBAN" -> optionalText(payload, "cookie") + "\n" + optionalText(payload, "user_agent");
            case "ROTTEN_TOMATOES" -> optionalText(payload, "cookie") + "\n" + optionalText(payload, "user_agent");
            case "MAIL" -> requireText(payload, "username", "Username is required") + "|"
                    + requireText(payload, "password", "Password is required");
            case "R2" -> requireText(payload, "account_id", "Account ID required") + "|"
                    + requireText(payload, "access_key", "Access Key required") + "|"
                    + requireText(payload, "secret_key", "Secret Key required");
            default -> throw new IllegalArgumentException("Provider not supported");
        };
    }

    public String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        return provider.trim().toUpperCase(Locale.ROOT);
    }

    public String normalizeRateLimitUnit(String unit) {
        if (unit == null || unit.isBlank()) {
            return null;
        }
        String normalized = unit.trim().toUpperCase(Locale.ROOT);
        if (!"SECOND".equals(normalized) && !"MINUTE".equals(normalized)) {
            throw new IllegalArgumentException("Rate limit unit must be SECOND or MINUTE");
        }
        return normalized;
    }

    private CredentialTemplateField field(
            String key,
            String label,
            String type,
            boolean required,
            String placeholder,
            Object defaultValue,
            String helpText) {
        return new CredentialTemplateField(key, label, type, required, placeholder, defaultValue, helpText, null);
    }

    private void validateOpenAi(Map<String, Object> payload) {
        requireText(payload, "api_key", "API Key is required");
        try {
            URI baseUrl = URI.create(resolveOpenAiBaseUrl(payload));
            String scheme = baseUrl.getScheme();
            if (!StringUtils.hasText(scheme)
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("Base URL is invalid");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Base URL is invalid", e);
        }
    }

    private void validateDouban(Map<String, Object> payload) {
        if (!StringUtils.hasText(optionalText(payload, "cookie"))
                && !StringUtils.hasText(optionalText(payload, "user_agent"))) {
            throw new IllegalArgumentException("Douban cookie or user_agent is required");
        }
    }

    private void validateRottenTomatoes(Map<String, Object> payload) {
        if (!StringUtils.hasText(optionalText(payload, "cookie"))
                && !StringUtils.hasText(optionalText(payload, "user_agent"))) {
            throw new IllegalArgumentException("Rotten Tomatoes cookie or user_agent is required");
        }
    }

    private void validateMail(Map<String, Object> payload) {
        requireText(payload, "username", "Username is required");
        requireText(payload, "password", "Password is required");
        if (!hasAnyMailSmtpKey(payload)) {
            return;
        }
        requireText(payload, "smtp_host", "SMTP host is required");
        requireText(payload, "smtp_protocol", "SMTP protocol is required");
        int port = intValue(payload, "smtp_port", DEFAULT_MAIL_PORT);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SMTP port is invalid");
        }
        int timeout = intValue(payload, "smtp_timeout_ms", DEFAULT_MAIL_TIMEOUT_MS);
        if (timeout < 1) {
            throw new IllegalArgumentException("SMTP timeout must be positive");
        }
        boolean ssl = booleanValue(payload, "smtp_ssl_enabled", DEFAULT_MAIL_SSL);
        boolean starttls = booleanValue(payload, "smtp_starttls_enabled", DEFAULT_MAIL_STARTTLS);
        if (ssl && starttls) {
            throw new IllegalArgumentException("SMTP SSL and STARTTLS cannot both be enabled");
        }
    }

    private boolean hasAnyMailSmtpKey(Map<String, Object> payload) {
        return payload.containsKey("smtp_host")
                || payload.containsKey("smtp_port")
                || payload.containsKey("smtp_protocol")
                || payload.containsKey("smtp_auth")
                || payload.containsKey("smtp_ssl_enabled")
                || payload.containsKey("smtp_starttls_enabled")
                || payload.containsKey("smtp_timeout_ms");
    }

    private String resolveOpenAiBaseUrl(Map<String, Object> payload) {
        Object value = payload.get("base_url");
        String normalized = StringUtils.hasText(value == null ? null : String.valueOf(value))
                ? String.valueOf(value).trim()
                : DEFAULT_OPENAI_BASE_URL;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            return normalized.substring(0, normalized.length() - "/chat/completions".length());
        }
        return normalized;
    }

    private String requireText(Map<String, Object> payload, String key, String message) {
        Object value = payload.get(key);
        String text = value == null ? "" : String.valueOf(value).trim();
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    private String optionalText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int intValue(Map<String, Object> payload, String key, int fallback) {
        Object value = payload.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException(key + " must be a number");
        }
    }

    private boolean booleanValue(Map<String, Object> payload, String key, boolean fallback) {
        Object value = payload.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "1", "yes", "y", "on", "enabled" -> true;
            case "false", "0", "no", "n", "off", "disabled" -> false;
            default -> fallback;
        };
    }
}
