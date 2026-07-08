package com.prodigalgal.ircs.common.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class IrcsAuthHeaders {

    public static final String AUTHENTICATED_USER = "X-Authenticated-User";
    public static final String AUTH_SUBJECT = "X-IRCS-Auth-Subject";
    public static final String AUTH_ROLE = "X-IRCS-Auth-Role";
    public static final String AUTH_PERMISSIONS = "X-IRCS-Auth-Permissions";
    public static final String AUTH_SCOPES = "X-IRCS-Auth-Scopes";
    public static final String DATA_CATEGORIES = "X-IRCS-Data-Categories";
    public static final String DATA_GENRES = "X-IRCS-Data-Genres";
    public static final String DATA_TAGS = "X-IRCS-Data-Tags";
    public static final String CONTENT_VISIBILITY = "X-IRCS-Content-Visibility";
    public static final String PRINCIPAL_ATTRIBUTE = IrcsAuthHeaders.class.getName() + ".principal";

    private static final Set<String> TRUSTED_IDENTITY_HEADERS = Set.of(
            lower(AUTHENTICATED_USER),
            lower("X-User"),
            lower("X-Username"),
            lower("X-User-Email"),
            lower(AUTH_SUBJECT),
            lower(AUTH_ROLE),
            lower(AUTH_PERMISSIONS),
            lower(AUTH_SCOPES),
            lower(DATA_CATEGORIES),
            lower(DATA_GENRES),
            lower(DATA_TAGS),
            lower(CONTENT_VISIBILITY));

    private IrcsAuthHeaders() {
    }

    public static boolean isTrustedIdentityHeader(String name) {
        return name != null && TRUSTED_IDENTITY_HEADERS.contains(lower(name));
    }

    public static void removeTrustedIdentityHeaders(Map<String, String> headers) {
        headers.keySet().removeIf(IrcsAuthHeaders::isTrustedIdentityHeader);
    }

    public static void writePrincipal(Map<String, String> headers, IrcsRequestPrincipal principal) {
        if (principal == null) {
            return;
        }
        headers.put(AUTHENTICATED_USER, principal.subject());
        headers.put(AUTH_SUBJECT, principal.subject());
        headers.put(AUTH_ROLE, principal.role());
        headers.put(AUTH_PERMISSIONS, principal.permissionsCsv());
        headers.put(AUTH_SCOPES, principal.scopesCsv());
        headers.put(DATA_CATEGORIES, principal.dataCategoriesCsv());
        headers.put(DATA_GENRES, principal.dataGenresCsv());
        headers.put(DATA_TAGS, principal.dataTagsCsv());
        headers.put(CONTENT_VISIBILITY, principal.contentVisibilityCsv());
    }

    public static Optional<IrcsRequestPrincipal> fromRequestAttribute(HttpServletRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        Object value = request.getAttribute(PRINCIPAL_ATTRIBUTE);
        return value instanceof IrcsRequestPrincipal principal ? Optional.of(principal) : Optional.empty();
    }

    public static Optional<IrcsRequestPrincipal> fromTrustedHeaders(HttpServletRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        String subject = firstText(request.getHeader(AUTH_SUBJECT), request.getHeader(AUTHENTICATED_USER));
        String role = firstText(request.getHeader(AUTH_ROLE));
        if (subject == null && role == null) {
            return Optional.empty();
        }
        if (subject == null || role == null) {
            return Optional.empty();
        }
        Set<String> permissions = IrcsRequestPrincipal.parseCsv(request.getHeader(AUTH_PERMISSIONS));
        Set<String> scopes = IrcsRequestPrincipal.parseCsv(request.getHeader(AUTH_SCOPES));
        Set<String> categories = IrcsRequestPrincipal.parseCsv(request.getHeader(DATA_CATEGORIES));
        Set<String> genres = IrcsRequestPrincipal.parseCsv(request.getHeader(DATA_GENRES));
        Set<String> tags = IrcsRequestPrincipal.parseCsv(request.getHeader(DATA_TAGS));
        Set<String> visibility = IrcsRequestPrincipal.parseCsv(request.getHeader(CONTENT_VISIBILITY));
        if (permissions.isEmpty() || scopes.isEmpty() || categories.isEmpty()
                || genres.isEmpty() || tags.isEmpty() || visibility.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new IrcsRequestPrincipal(
                subject,
                role,
                permissions,
                scopes,
                categories,
                genres,
                tags,
                visibility));
    }

    public static IrcsRequestPrincipal principalOrPublic(HttpServletRequest request) {
        return fromRequestAttribute(request)
                .or(() -> fromTrustedHeaders(request))
                .orElseGet(IrcsRequestPrincipal::publicPrincipal);
    }

    public static void setRequestAttribute(HttpServletRequest request, IrcsRequestPrincipal principal) {
        if (request != null && principal != null) {
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        }
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
