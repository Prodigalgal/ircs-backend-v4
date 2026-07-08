package com.prodigalgal.ircs.common.security;

import com.prodigalgal.ircs.common.normalization.StandardContentCategoryClassifier;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class IrcsPermissions {

    public static final String ALL = "*";
    public static final String AUTHORITY_PREFIX = "PERMISSION_";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_MEMBER = "ROLE_MEMBER";
    public static final String ROLE_ANONYMOUS = "ROLE_ANONYMOUS";

    public static final String ADMIN_ACCESS = "admin.access";
    public static final String ADMIN_AUTH_CHANGE_PASSWORD = "admin.auth.change-password";
    public static final String ADMIN_MEMBERS_READ = "admin.members.read";
    public static final String ADMIN_MEMBERS_WRITE = "admin.members.write";
    public static final String CATALOG_READ = "catalog.read";
    public static final String CATALOG_WRITE = "catalog.write";
    public static final String CONTENT_READ = "content.read";
    public static final String CONTENT_WRITE = "content.write";
    public static final String TASK_READ = "task.read";
    public static final String TASK_WRITE = "task.write";
    public static final String TASK_RUN = "task.run";
    public static final String OPS_READ = "ops.read";
    public static final String OPS_WRITE = "ops.write";
    public static final String OPS_RUN = "ops.run";
    public static final String CONFIG_READ = "config.read";
    public static final String CONFIG_WRITE = "config.write";
    public static final String CREDENTIAL_READ = "credential.read";
    public static final String CREDENTIAL_WRITE = "credential.write";
    public static final String MAGNET_READ = "magnet.read";
    public static final String MAGNET_WRITE = "magnet.write";
    public static final String MAGNET_RUN = "magnet.run";
    public static final String SCRAPER_RUN = "scraper.run";
    public static final String INTERACTION_READ = "interaction.read";
    public static final String INTERACTION_WRITE = "interaction.write";
    public static final String INTERACTION_MODERATE = "interaction.moderate";
    public static final String STORAGE_READ = "storage.read";
    public static final String STORAGE_WRITE = "storage.write";
    public static final String PORTAL_READ = "portal.read";
    public static final String PORTAL_PROFILE_READ = "portal.profile.read";
    public static final String PORTAL_PROFILE_WRITE = "portal.profile.write";
    public static final String PORTAL_INTERACTION_READ = "portal.interaction.read";
    public static final String PORTAL_INTERACTION_WRITE = "portal.interaction.write";

    public static final String SCOPE_ADMIN_ALL = "admin:*";
    public static final String SCOPE_DATA_ALL = "data:*";
    public static final String SCOPE_PORTAL_READ = "portal:read";
    public static final String SCOPE_PROFILE_WRITE = "profile:write";
    public static final String SCOPE_INTERACTION_WRITE = "interaction:write";

    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    public static final String VISIBILITY_MEMBER = "MEMBER";
    public static final String VISIBILITY_ADMIN = "ADMIN";
    public static final String VISIBILITY_DRAFT = "DRAFT";
    public static final String VISIBILITY_HIDDEN = "HIDDEN";

    private IrcsPermissions() {
    }

    public static Set<String> defaultPermissions(String role) {
        String normalizedRole = normalizeRole(role);
        if (ROLE_ADMIN.equals(normalizedRole)) {
            return Set.of(ALL);
        }
        if (ROLE_ANONYMOUS.equals(normalizedRole)) {
            return Set.of(PORTAL_READ);
        }
        return Set.of(
                PORTAL_READ,
                PORTAL_PROFILE_READ,
                PORTAL_PROFILE_WRITE,
                PORTAL_INTERACTION_READ,
                PORTAL_INTERACTION_WRITE);
    }

    public static Set<String> defaultScopes(String role) {
        String normalizedRole = normalizeRole(role);
        if (ROLE_ADMIN.equals(normalizedRole)) {
            return Set.of(SCOPE_ADMIN_ALL, SCOPE_DATA_ALL);
        }
        if (ROLE_ANONYMOUS.equals(normalizedRole)) {
            return Set.of(SCOPE_PORTAL_READ);
        }
        return Set.of(SCOPE_PORTAL_READ, SCOPE_PROFILE_WRITE, SCOPE_INTERACTION_WRITE);
    }

    public static Set<String> defaultDataScope(String role) {
        return Set.of(ALL);
    }

    public static Set<String> defaultCategoryScope(String role) {
        String normalizedRole = normalizeRole(role);
        if (ROLE_ADMIN.equals(normalizedRole)) {
            return Set.of(ALL);
        }
        return StandardContentCategoryClassifier.stableCategoryCodes().stream()
                .filter(code -> !StandardContentCategoryClassifier.ADULT.equals(code))
                .collect(Collectors.toUnmodifiableSet());
    }

    public static Set<String> memberCategoryScope(boolean adultContentAllowed) {
        return adultContentAllowed
                ? StandardContentCategoryClassifier.stableCategoryCodes().stream()
                        .collect(Collectors.toUnmodifiableSet())
                : defaultCategoryScope(ROLE_MEMBER);
    }

    public static Set<String> defaultContentVisibility(String role) {
        String normalizedRole = normalizeRole(role);
        if (ROLE_ANONYMOUS.equals(normalizedRole)) {
            return Set.of(VISIBILITY_PUBLIC);
        }
        return ROLE_ADMIN.equals(normalizedRole)
                ? Set.of(ALL, VISIBILITY_PUBLIC, VISIBILITY_MEMBER, VISIBILITY_ADMIN, VISIBILITY_DRAFT, VISIBILITY_HIDDEN)
                : Set.of(VISIBILITY_PUBLIC, VISIBILITY_MEMBER);
    }

    public static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return ROLE_MEMBER;
        }
        String trimmed = role.trim().toUpperCase(Locale.ROOT);
        return trimmed.startsWith("ROLE_") ? trimmed : "ROLE_" + trimmed;
    }

    public static String authority(String permission) {
        return AUTHORITY_PREFIX + permission;
    }
}
