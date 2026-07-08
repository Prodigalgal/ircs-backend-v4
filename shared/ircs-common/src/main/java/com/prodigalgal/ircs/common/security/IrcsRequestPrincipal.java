package com.prodigalgal.ircs.common.security;

import com.prodigalgal.ircs.common.normalization.StandardContentCategoryClassifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public record IrcsRequestPrincipal(
        String subject,
        String role,
        Set<String> permissions,
        Set<String> scopes,
        Set<String> dataCategories,
        Set<String> dataGenres,
        Set<String> dataTags,
        Set<String> contentVisibility) {

    public static final String ANONYMOUS_SUBJECT = "anonymous";

    public IrcsRequestPrincipal {
        role = IrcsPermissions.normalizeRole(role);
        permissions = normalizeSet(permissions);
        scopes = normalizeSet(scopes);
        dataCategories = normalizeSet(dataCategories);
        dataGenres = normalizeSet(dataGenres);
        dataTags = normalizeSet(dataTags);
        contentVisibility = normalizeSet(contentVisibility);
    }

    public boolean isAdmin() {
        return IrcsPermissions.ROLE_ADMIN.equals(role);
    }

    public boolean isAnonymous() {
        return IrcsPermissions.ROLE_ANONYMOUS.equals(role) || ANONYMOUS_SUBJECT.equals(subject);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(IrcsPermissions.ALL) || permissions.contains(permission);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(IrcsPermissions.ALL) || scopes.contains(scope);
    }

    public boolean allowsCategory(String... candidates) {
        return allows(dataCategories, candidates);
    }

    public boolean allowsGenre(String... candidates) {
        return allows(dataGenres, candidates);
    }

    public boolean allowsTag(String... candidates) {
        return allows(dataTags, candidates);
    }

    public boolean allowsContentVisibility(String visibility) {
        String effective = visibility == null || visibility.isBlank()
                ? IrcsPermissions.VISIBILITY_PUBLIC
                : visibility.trim();
        return allows(contentVisibility, effective);
    }

    public boolean allowsAdultRestrictedContent() {
        return allowsCategory(StandardContentCategoryClassifier.ADULT);
    }

    public boolean hasUnrestrictedCategories() {
        return isUnrestricted(dataCategories);
    }

    public boolean hasUnrestrictedGenres() {
        return isUnrestricted(dataGenres);
    }

    public boolean hasUnrestrictedTags() {
        return isUnrestricted(dataTags);
    }

    public boolean hasUnrestrictedVisibility() {
        return isUnrestricted(contentVisibility);
    }

    public String permissionsCsv() {
        return csv(permissions);
    }

    public String scopesCsv() {
        return csv(scopes);
    }

    public String dataCategoriesCsv() {
        return csv(dataCategories);
    }

    public String dataGenresCsv() {
        return csv(dataGenres);
    }

    public String dataTagsCsv() {
        return csv(dataTags);
    }

    public String contentVisibilityCsv() {
        return csv(contentVisibility);
    }

    public static IrcsRequestPrincipal publicPrincipal() {
        return new IrcsRequestPrincipal(
                ANONYMOUS_SUBJECT,
                IrcsPermissions.ROLE_ANONYMOUS,
                IrcsPermissions.defaultPermissions(IrcsPermissions.ROLE_ANONYMOUS),
                IrcsPermissions.defaultScopes(IrcsPermissions.ROLE_ANONYMOUS),
                IrcsPermissions.defaultCategoryScope(IrcsPermissions.ROLE_ANONYMOUS),
                IrcsPermissions.defaultDataScope(IrcsPermissions.ROLE_ANONYMOUS),
                IrcsPermissions.defaultDataScope(IrcsPermissions.ROLE_ANONYMOUS),
                IrcsPermissions.defaultContentVisibility(IrcsPermissions.ROLE_ANONYMOUS));
    }

    static Set<String> parseCsv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return normalizeSet(Arrays.stream(value.split("[,\\s]+")).toList());
    }

    static Set<String> normalizeSet(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        TreeSet<String> normalized = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(TreeSet::new));
        return normalized.isEmpty() ? Set.of() : Collections.unmodifiableSet(normalized);
    }

    private static String csv(Set<String> values) {
        return String.join(",", values);
    }

    private static boolean isUnrestricted(Set<String> values) {
        return values.contains(IrcsPermissions.ALL);
    }

    private static boolean allows(Set<String> allowed, String... candidates) {
        if (isUnrestricted(allowed)) {
            return true;
        }
        if (allowed.isEmpty() || candidates == null || candidates.length == 0) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && allowed.contains(candidate.trim())) {
                return true;
            }
        }
        return false;
    }
}
