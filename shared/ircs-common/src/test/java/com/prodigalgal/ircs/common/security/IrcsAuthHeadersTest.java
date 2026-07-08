package com.prodigalgal.ircs.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class IrcsAuthHeadersTest {

    @Test
    void stripsSpoofableTrustedIdentityHeadersCaseInsensitively() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer token");
        headers.put("X-Authenticated-User", "spoof");
        headers.put("x-ircs-auth-role", "ROLE_ADMIN");
        headers.put("X-Trace-Id", "trace-1");

        IrcsAuthHeaders.removeTrustedIdentityHeaders(headers);

        assertThat(headers)
                .containsEntry("Authorization", "Bearer token")
                .containsEntry("X-Trace-Id", "trace-1")
                .doesNotContainKeys("X-Authenticated-User", "x-ircs-auth-role");
    }

    @Test
    void writesDeterministicTrustedPrincipalHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        IrcsRequestPrincipal principal = new IrcsRequestPrincipal(
                "admin",
                "ADMIN",
                Set.of("content.write", "*"),
                Set.of("data:*", "admin:*"),
                Set.of("*"),
                Set.of("*"),
                Set.of("*"),
                Set.of("DRAFT", "PUBLIC"));

        IrcsAuthHeaders.writePrincipal(headers, principal);

        assertThat(headers)
                .containsEntry(IrcsAuthHeaders.AUTHENTICATED_USER, "admin")
                .containsEntry(IrcsAuthHeaders.AUTH_ROLE, "ROLE_ADMIN")
                .containsEntry(IrcsAuthHeaders.AUTH_PERMISSIONS, "*,content.write")
                .containsEntry(IrcsAuthHeaders.AUTH_SCOPES, "admin:*,data:*")
                .containsEntry(IrcsAuthHeaders.CONTENT_VISIBILITY, "DRAFT,PUBLIC");
    }

    @Test
    void readsTrustedPrincipalHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(IrcsAuthHeaders.AUTH_SUBJECT, "member-1");
        request.addHeader(IrcsAuthHeaders.AUTH_ROLE, IrcsPermissions.ROLE_MEMBER);
        request.addHeader(IrcsAuthHeaders.AUTH_PERMISSIONS, "portal.read,portal.profile.write");
        request.addHeader(IrcsAuthHeaders.AUTH_SCOPES, "portal:read profile:write");
        request.addHeader(IrcsAuthHeaders.DATA_CATEGORIES, "movies,电影");
        request.addHeader(IrcsAuthHeaders.DATA_GENRES, "剧情");
        request.addHeader(IrcsAuthHeaders.DATA_TAGS, "*");
        request.addHeader(IrcsAuthHeaders.CONTENT_VISIBILITY, "PUBLIC,MEMBER");

        IrcsRequestPrincipal principal = IrcsAuthHeaders.fromTrustedHeaders(request).orElseThrow();

        assertThat(principal.subject()).isEqualTo("member-1");
        assertThat(principal.allowsCategory("movies")).isTrue();
        assertThat(principal.allowsCategory("series")).isFalse();
        assertThat(principal.allowsGenre("剧情")).isTrue();
        assertThat(principal.allowsContentVisibility("MEMBER")).isTrue();
        assertThat(principal.allowsContentVisibility("ADMIN")).isFalse();
    }

    @Test
    void fallsBackToPublicPrincipalWithoutTrustedHeaders() {
        HttpServletRequest request = new MockHttpServletRequest();

        IrcsRequestPrincipal principal = IrcsAuthHeaders.principalOrPublic(request);

        assertThat(principal.isAnonymous()).isTrue();
        assertThat(principal.hasPermission(IrcsPermissions.PORTAL_READ)).isTrue();
        assertThat(principal.hasPermission(IrcsPermissions.PORTAL_PROFILE_WRITE)).isFalse();
        assertThat(principal.allowsCategory("movie")).isTrue();
        assertThat(principal.allowsCategory("adult")).isFalse();
        assertThat(principal.allowsContentVisibility(IrcsPermissions.VISIBILITY_PUBLIC)).isTrue();
        assertThat(principal.allowsContentVisibility(IrcsPermissions.VISIBILITY_MEMBER)).isFalse();
    }
}
