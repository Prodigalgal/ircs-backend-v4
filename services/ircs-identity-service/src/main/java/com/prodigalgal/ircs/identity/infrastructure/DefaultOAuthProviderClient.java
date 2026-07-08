package com.prodigalgal.ircs.identity.infrastructure;






import com.prodigalgal.ircs.identity.domain.OAuthUserProfile;
import com.prodigalgal.ircs.identity.domain.OAuthAccessToken;
import com.prodigalgal.ircs.identity.domain.OAuthProviderRegistry;
import com.prodigalgal.ircs.identity.domain.OAuthProviderConfig;
import com.prodigalgal.ircs.identity.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.DefaultOutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.JdkOutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class DefaultOAuthProviderClient implements OAuthProviderClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final OutboundHttpClient httpClient;
    private final ObjectMapper objectMapper;

    DefaultOAuthProviderClient(
            ObjectProvider<OutboundHttpClient> httpClientProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.httpClient = httpClient(httpClientProvider);
        this.objectMapper = objectMapper(objectMapperProvider);
    }

    @Override
    public OAuthAccessToken exchangeCode(OAuthProviderConfig config, String code, String codeVerifier) {
        return switch (config.definition().tokenTransport()) {
            case FORM_POST -> exchangeByPost(config, code, codeVerifier);
            case QUERY_GET -> exchangeByGet(config, code);
        };
    }

    @Override
    public OAuthUserProfile fetchUserProfile(OAuthProviderConfig config, OAuthAccessToken token) {
        return switch (config.definition().kind()) {
            case GOOGLE -> googleProfile(config, token);
            case X -> xProfile(config, token);
            case GITHUB -> githubProfile(config, token);
            case GITEE -> giteeProfile(config, token);
            case WECHAT -> wechatProfile(config, token);
            case QQ -> qqProfile(config, token);
        };
    }

    private OAuthAccessToken exchangeByPost(OAuthProviderConfig config, String code, String codeVerifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", config.clientId());
        if (!config.definition().basicAuthToken()) {
            form.put("client_secret", config.clientSecret());
        }
        form.put("code", code);
        form.put("redirect_uri", config.redirectUri().toString());
        if (StringUtils.hasText(codeVerifier)) {
            form.put("code_verifier", codeVerifier);
        }

        OutboundHttpRequest request = OutboundHttpRequest
                .post(URI.create(config.definition().tokenUri()), policy())
                .withHeader("Accept", "application/json")
                .withHeader("Content-Type", "application/x-www-form-urlencoded")
                .withBody(formBody(form));
        if (config.definition().basicAuthToken()) {
            request = request.withHeader("Authorization", basicAuth(config.clientId(), config.clientSecret()));
        }
        JsonNode body = json(request, "oauth.token.exchange");
        return tokenFromJson(config, body);
    }

    private OAuthAccessToken exchangeByGet(OAuthProviderConfig config, String code) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(config.definition().tokenUri())
                .queryParam("grant_type", "authorization_code")
                .queryParam("code", code)
                .queryParam("redirect_uri", config.redirectUri().toString());
        if (config.definition().kind() == OAuthProviderRegistry.Kind.WECHAT) {
            builder.queryParam("appid", config.clientId())
                    .queryParam("secret", config.clientSecret());
        } else {
            builder.queryParam("client_id", config.clientId())
                    .queryParam("client_secret", config.clientSecret());
        }
        OutboundHttpResponse response = execute(OutboundHttpRequest
                .get(builder.build().encode().toUri(), policy())
                .withHeader("Accept", "application/json,text/plain,*/*"), "oauth.token.exchange");
        String text = response.bodyAsUtf8();
        if (looksLikeJson(text)) {
            return tokenFromJson(config, parseJson(text, "oauth.token.exchange"));
        }
        Map<String, String> values = parseForm(text);
        return new OAuthAccessToken(required(values.get("access_token"), "oauth.token.missing"),
                values.getOrDefault("token_type", "Bearer"),
                values.get("openid"));
    }

    private OAuthUserProfile googleProfile(OAuthProviderConfig config, OAuthAccessToken token) {
        JsonNode body = json(bearerGet(config.definition().userInfoUri(), token.accessToken()), "oauth.userinfo.google");
        return new OAuthUserProfile(
                config.code(),
                required(text(body, "sub"), "oauth.subject.missing"),
                normalizeEmail(text(body, "email")),
                body.path("email_verified").asBoolean(false),
                firstText(body, "name", "given_name", "email"),
                text(body, "picture"));
    }

    private OAuthUserProfile xProfile(OAuthProviderConfig config, OAuthAccessToken token) {
        JsonNode data = json(bearerGet(config.definition().userInfoUri(), token.accessToken()), "oauth.userinfo.x")
                .path("data");
        return new OAuthUserProfile(
                config.code(),
                required(text(data, "id"), "oauth.subject.missing"),
                null,
                false,
                firstText(data, "name", "username"),
                text(data, "profile_image_url"));
    }

    private OAuthUserProfile githubProfile(OAuthProviderConfig config, OAuthAccessToken token) {
        JsonNode user = json(bearerGet(config.definition().userInfoUri(), token.accessToken()), "oauth.userinfo.github");
        EmailClaim email = githubEmail(config, token.accessToken(), text(user, "email"));
        return new OAuthUserProfile(
                config.code(),
                required(text(user, "id"), "oauth.subject.missing"),
                normalizeEmail(email.email()),
                email.verified(),
                firstText(user, "name", "login", "email"),
                text(user, "avatar_url"));
    }

    private EmailClaim githubEmail(OAuthProviderConfig config, String accessToken, String fallbackEmail) {
        JsonNode emails = json(bearerGet(config.definition().emailsUri(), accessToken), "oauth.email.github");
        if (emails.isArray()) {
            EmailClaim firstVerified = null;
            for (JsonNode item : emails) {
                String email = normalizeEmail(text(item, "email"));
                boolean verified = item.path("verified").asBoolean(false);
                if (!StringUtils.hasText(email)) {
                    continue;
                }
                EmailClaim claim = new EmailClaim(email, verified);
                if (item.path("primary").asBoolean(false)) {
                    return claim;
                }
                if (verified && firstVerified == null) {
                    firstVerified = claim;
                }
            }
            if (firstVerified != null) {
                return firstVerified;
            }
        }
        return new EmailClaim(normalizeEmail(fallbackEmail), false);
    }

    private OAuthUserProfile giteeProfile(OAuthProviderConfig config, OAuthAccessToken token) {
        JsonNode user = json(getWithAccessToken(config.definition().userInfoUri(), token.accessToken()), "oauth.userinfo.gitee");
        EmailClaim email = giteeEmail(config, token.accessToken(), text(user, "email"));
        return new OAuthUserProfile(
                config.code(),
                required(text(user, "id"), "oauth.subject.missing"),
                normalizeEmail(email.email()),
                email.verified(),
                firstText(user, "name", "login", "email"),
                text(user, "avatar_url"));
    }

    private EmailClaim giteeEmail(OAuthProviderConfig config, String accessToken, String fallbackEmail) {
        if (!StringUtils.hasText(config.definition().emailsUri())) {
            return new EmailClaim(normalizeEmail(fallbackEmail), false);
        }
        JsonNode emails = json(getWithAccessToken(config.definition().emailsUri(), accessToken), "oauth.email.gitee");
        if (emails.isArray()) {
            for (JsonNode item : emails) {
                String email = normalizeEmail(text(item, "email"));
                if (!StringUtils.hasText(email)) {
                    continue;
                }
                boolean verified = item.path("verified").asBoolean(false)
                        || item.path("confirmed").asBoolean(false)
                        || "confirmed".equalsIgnoreCase(text(item, "state"));
                if (verified || item.path("primary").asBoolean(false)) {
                    return new EmailClaim(email, verified);
                }
            }
        }
        return new EmailClaim(normalizeEmail(fallbackEmail), false);
    }

    private OAuthUserProfile wechatProfile(OAuthProviderConfig config, OAuthAccessToken token) {
        String subject = required(token.openId(), "oauth.subject.missing");
        JsonNode body = json(UriComponentsBuilder.fromUriString(config.definition().userInfoUri())
                .queryParam("access_token", token.accessToken())
                .queryParam("openid", subject)
                .queryParam("lang", "zh_CN")
                .build()
                .encode()
                .toUri(), "oauth.userinfo.wechat");
        return new OAuthUserProfile(
                config.code(),
                firstText(body, "unionid", "openid"),
                null,
                false,
                text(body, "nickname"),
                text(body, "headimgurl"));
    }

    private OAuthUserProfile qqProfile(OAuthProviderConfig config, OAuthAccessToken token) {
        String openId = token.openId();
        if (!StringUtils.hasText(openId)) {
            JsonNode me = json(UriComponentsBuilder.fromUriString(config.definition().emailsUri())
                    .queryParam("access_token", token.accessToken())
                    .build()
                    .encode()
                    .toUri(), "oauth.openid.qq");
            openId = required(text(me, "openid"), "oauth.subject.missing");
        }
        JsonNode body = json(UriComponentsBuilder.fromUriString(config.definition().userInfoUri())
                .queryParam("access_token", token.accessToken())
                .queryParam("oauth_consumer_key", config.clientId())
                .queryParam("openid", openId)
                .build()
                .encode()
                .toUri(), "oauth.userinfo.qq");
        return new OAuthUserProfile(
                config.code(),
                openId,
                null,
                false,
                text(body, "nickname"),
                firstText(body, "figureurl_qq_2", "figureurl_qq_1", "figureurl_2", "figureurl_1"));
    }

    private OutboundHttpRequest bearerGet(String uri, String accessToken) {
        return OutboundHttpRequest.get(uri, policy())
                .withHeader("Accept", "application/json")
                .withHeader("Authorization", "Bearer " + accessToken);
    }

    private OutboundHttpRequest getWithAccessToken(String uri, String accessToken) {
        URI target = UriComponentsBuilder.fromUriString(uri)
                .queryParam("access_token", accessToken)
                .build()
                .encode()
                .toUri();
        return OutboundHttpRequest.get(target, policy()).withHeader("Accept", "application/json");
    }

    private JsonNode json(OutboundHttpRequest request, String errorKey) {
        OutboundHttpResponse response = execute(request, errorKey);
        return parseJson(response.bodyAsUtf8(), errorKey);
    }

    private JsonNode json(URI uri, String errorKey) {
        return json(OutboundHttpRequest.get(uri, policy()).withHeader("Accept", "application/json,text/plain,*/*"), errorKey);
    }

    private OutboundHttpResponse execute(OutboundHttpRequest request, String errorKey) {
        try {
            OutboundHttpResponse response = httpClient.execute(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OAuth provider request failed", "oauth", errorKey);
            }
            return response;
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OAuth provider request interrupted", "oauth", errorKey);
        } catch (IOException | RuntimeException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OAuth provider request failed", "oauth", errorKey);
        }
    }

    private JsonNode parseJson(String raw, String errorKey) {
        try {
            String body = stripJsonp(raw);
            JsonNode json = objectMapper.readTree(body);
            if (StringUtils.hasText(text(json, "error"))) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OAuth provider returned error", "oauth", errorKey);
            }
            return json;
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OAuth provider returned invalid JSON", "oauth", errorKey);
        }
    }

    private OAuthAccessToken tokenFromJson(OAuthProviderConfig config, JsonNode body) {
        String openId = firstText(body, "openid", "unionid");
        if (config.definition().kind() == OAuthProviderRegistry.Kind.QQ && !StringUtils.hasText(openId)) {
            JsonNode me = json(UriComponentsBuilder.fromUriString(config.definition().emailsUri())
                    .queryParam("access_token", required(text(body, "access_token"), "oauth.token.missing"))
                    .build()
                    .encode()
                    .toUri(), "oauth.openid.qq");
            openId = text(me, "openid");
        }
        return new OAuthAccessToken(
                required(text(body, "access_token"), "oauth.token.missing"),
                StringUtils.hasText(text(body, "token_type")) ? text(body, "token_type") : "Bearer",
                openId);
    }

    private static OutboundHttpPolicy policy() {
        return OutboundHttpPolicy.publicFetch(REQUEST_TIMEOUT)
                .withMaxRetries(0)
                .withCallerCircuitBreakerKey("identity-oauth-provider");
    }

    private static byte[] formBody(Map<String, String> values) {
        List<String> pairs = new ArrayList<>();
        values.forEach((key, value) -> {
            if (StringUtils.hasText(value)) {
                pairs.add(urlEncode(key) + "=" + urlEncode(value));
            }
        });
        return String.join("&", pairs).getBytes(StandardCharsets.UTF_8);
    }

    private static String basicAuth(String clientId, String clientSecret) {
        String raw = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static String normalizeEmail(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(java.util.Locale.ROOT) : null;
    }

    private static String required(String value, String errorKey) {
        if (!StringUtils.hasText(value)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OAuth provider response is missing required data", "oauth", errorKey);
        }
        return value;
    }

    private static Map<String, String> parseForm(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!StringUtils.hasText(raw)) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int index = pair.indexOf('=');
            if (index > 0) {
                values.put(pair.substring(0, index), java.net.URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static String stripJsonp(String raw) {
        String value = raw == null ? "" : raw.trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start > 0 && end > start) {
            return value.substring(start, end + 1);
        }
        return value;
    }

    private static boolean looksLikeJson(String raw) {
        String value = raw == null ? "" : raw.trim();
        return value.startsWith("{") || value.startsWith("[");
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static OutboundHttpClient httpClient(ObjectProvider<OutboundHttpClient> provider) {
        if (provider != null) {
            OutboundHttpClient provided = provider.getIfUnique();
            if (provided != null) {
                return provided;
            }
        }
        return new OutboundHttpClient(
                new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                new JdkOutboundTransport(REQUEST_TIMEOUT));
    }

    private static ObjectMapper objectMapper(ObjectProvider<ObjectMapper> provider) {
        if (provider != null) {
            ObjectMapper provided = provider.getIfUnique();
            if (provided != null) {
                return provided;
            }
        }
        return new ObjectMapper();
    }

    private record EmailClaim(String email, boolean verified) {
    }
}
