package com.traceback.api.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Strict role converter enforcing allowlist validation:
 * - Allowlisted application roles: ROLE_ADMIN, ROLE_OPERATOR, ROLE_VIEWER
 * - Enforces collection type validation on 'roles' claim
 * - Validates every element in collection
 * - Rejects malformed or unknown roles (fail-closed)
 * - Never defaults missing roles to OPERATOR or ADMIN
 * - Uses Locale.ROOT for case normalization
 */
public class StrictJwtRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    public static final String ROLES_CLAIM = "roles";
    private static final Set<String> ALLOWLISTED_ROLES = Set.of("ADMIN", "OPERATOR", "VIEWER");

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object rawRoles = jwt.getClaims().get(ROLES_CLAIM);
        if (rawRoles == null) {
            return Collections.emptyList();
        }

        if (!(rawRoles instanceof Collection<?> rawCollection)) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token", "Malformed '" + ROLES_CLAIM + "' claim: expected a JSON array", null)
            );
        }

        if (rawCollection.isEmpty()) {
            return Collections.emptyList();
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        for (Object item : rawCollection) {
            if (!(item instanceof String roleStr)) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("invalid_token", "Malformed role element: elements must be strings, found " + (item == null ? "null" : item.getClass().getSimpleName()), null)
                );
            }

            String trimmed = roleStr.trim();
            if (trimmed.isEmpty()) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("invalid_token", "Malformed role element: role name cannot be blank", null)
                );
            }

            String upper = trimmed.toUpperCase(Locale.ROOT);
            String normalizedRole = upper.startsWith("ROLE_") ? upper.substring(5) : upper;

            if (!ALLOWLISTED_ROLES.contains(normalizedRole)) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("invalid_token", "Unknown or unauthorized role: '" + trimmed + "'. Permitted roles are: ADMIN, OPERATOR, VIEWER", null)
                );
            }

            authorities.add(new SimpleGrantedAuthority("ROLE_" + normalizedRole));
        }

        return Collections.unmodifiableList(authorities);
    }
}
