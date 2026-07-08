package com.prodigalgal.ircs.identity.domain;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class OAuthProviderRegistry {

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition(
                    "google",
                    "Google",
                    "member.oauth.google.client-id",
                    "member.oauth.google.client-secret",
                    "member.oauth.google.scope",
                    "openid email profile",
                    "member.oauth.google.redirect-uri",
                    "/api/portal/auth/oauth/google/callback",
                    "https://accounts.google.com/o/oauth2/v2/auth",
                    "https://oauth2.googleapis.com/token",
                    "https://openidconnect.googleapis.com/v1/userinfo",
                    null,
                    Kind.GOOGLE,
                    false,
                    false,
                    TokenTransport.FORM_POST),
            new Definition(
                    "x",
                    "X",
                    "member.oauth.x.client-id",
                    "member.oauth.x.client-secret",
                    "member.oauth.x.scope",
                    "users.read tweet.read offline.access",
                    "member.oauth.x.redirect-uri",
                    "/api/portal/auth/oauth/x/callback",
                    "https://twitter.com/i/oauth2/authorize",
                    "https://api.twitter.com/2/oauth2/token",
                    "https://api.twitter.com/2/users/me?user.fields=profile_image_url,verified",
                    null,
                    Kind.X,
                    true,
                    true,
                    TokenTransport.FORM_POST),
            new Definition(
                    "github",
                    "GitHub",
                    "member.oauth.github.client-id",
                    "member.oauth.github.client-secret",
                    "member.oauth.github.scope",
                    "read:user user:email",
                    "member.oauth.github.redirect-uri",
                    "/api/portal/auth/oauth/github/callback",
                    "https://github.com/login/oauth/authorize",
                    "https://github.com/login/oauth/access_token",
                    "https://api.github.com/user",
                    "https://api.github.com/user/emails",
                    Kind.GITHUB,
                    true,
                    false,
                    TokenTransport.FORM_POST),
            new Definition(
                    "gitee",
                    "Gitee",
                    "member.oauth.gitee.client-id",
                    "member.oauth.gitee.client-secret",
                    "member.oauth.gitee.scope",
                    "user_info emails",
                    "member.oauth.gitee.redirect-uri",
                    "/api/portal/auth/oauth/gitee/callback",
                    "https://gitee.com/oauth/authorize",
                    "https://gitee.com/oauth/token",
                    "https://gitee.com/api/v5/user",
                    "https://gitee.com/api/v5/emails",
                    Kind.GITEE,
                    false,
                    false,
                    TokenTransport.FORM_POST),
            new Definition(
                    "wechat",
                    "微信",
                    "member.oauth.wechat.app-id",
                    "member.oauth.wechat.app-secret",
                    "member.oauth.wechat.scope",
                    "snsapi_login",
                    "member.oauth.wechat.redirect-uri",
                    "/api/portal/auth/oauth/wechat/callback",
                    "https://open.weixin.qq.com/connect/qrconnect",
                    "https://api.weixin.qq.com/sns/oauth2/access_token",
                    "https://api.weixin.qq.com/sns/userinfo",
                    null,
                    Kind.WECHAT,
                    false,
                    false,
                    TokenTransport.QUERY_GET),
            new Definition(
                    "qq",
                    "QQ",
                    "member.oauth.qq.app-id",
                    "member.oauth.qq.app-key",
                    "member.oauth.qq.scope",
                    "get_user_info",
                    "member.oauth.qq.redirect-uri",
                    "/api/portal/auth/oauth/qq/callback",
                    "https://graph.qq.com/oauth2.0/authorize",
                    "https://graph.qq.com/oauth2.0/token",
                    "https://graph.qq.com/user/get_user_info",
                    "https://graph.qq.com/oauth2.0/me",
                    Kind.QQ,
                    false,
                    false,
                    TokenTransport.QUERY_GET));

    private OAuthProviderRegistry() {
    }

    public static List<Definition> definitions() {
        return DEFINITIONS;
    }

    public static Optional<Definition> find(String code) {
        String normalized = normalize(code);
        return DEFINITIONS.stream()
                .filter(definition -> definition.code().equals(normalized))
                .findFirst();
    }

    public static String normalize(String code) {
        return code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
    }

    public enum Kind {
        GOOGLE,
        X,
        GITHUB,
        GITEE,
        WECHAT,
        QQ
    }

    public enum TokenTransport {
        FORM_POST,
        QUERY_GET
    }

    public record Definition(
            String code,
            String label,
            String clientIdKey,
            String clientSecretKey,
            String scopeKey,
            String defaultScope,
            String redirectUriKey,
            String defaultRedirectPath,
            String authorizationUri,
            String tokenUri,
            String userInfoUri,
            String emailsUri,
            Kind kind,
            boolean pkceRequired,
            boolean basicAuthToken,
            TokenTransport tokenTransport) {
    }
}
