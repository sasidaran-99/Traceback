package com.traceback.api.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrictJwtRoleConverterTest {

    private StrictJwtRoleConverter converter;

    @BeforeEach
    void setUp() {
        converter = new StrictJwtRoleConverter();
    }

    private Jwt createJwtWithRoles(Object rolesClaim) {
        Jwt.Builder builder = Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .subject("test-user")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));

        if (rolesClaim != null) {
            builder.claim("roles", rolesClaim);
        }
        return builder.build();
    }

    @Test
    @DisplayName("Valid allowlisted roles are normalized and converted to GrantedAuthority")
    void validRolesConverted() {
        Jwt jwt = createJwtWithRoles(List.of("ADMIN", "operator", "ROLE_VIEWER"));
        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertEquals(3, authorities.size());
        List<String> authNames = authorities.stream().map(GrantedAuthority::getAuthority).toList();
        assertTrue(authNames.contains("ROLE_ADMIN"));
        assertTrue(authNames.contains("ROLE_OPERATOR"));
        assertTrue(authNames.contains("ROLE_VIEWER"));
    }

    @Test
    @DisplayName("Missing roles claim returns empty collection and never defaults to privileged roles")
    void missingRolesClaimReturnsEmpty() {
        Jwt jwt = createJwtWithRoles(null);
        Collection<GrantedAuthority> authorities = converter.convert(jwt);
        assertTrue(authorities.isEmpty(), "Missing roles claim must return empty authorities");
    }

    @Test
    @DisplayName("Empty roles list returns empty collection")
    void emptyRolesClaimReturnsEmpty() {
        Jwt jwt = createJwtWithRoles(List.of());
        Collection<GrantedAuthority> authorities = converter.convert(jwt);
        assertTrue(authorities.isEmpty());
    }

    @Test
    @DisplayName("Non-collection roles claim throws OAuth2AuthenticationException")
    void nonCollectionRolesThrows() {
        Jwt jwt = createJwtWithRoles("ROLE_ADMIN"); // String instead of array
        assertThrows(OAuth2AuthenticationException.class, () -> converter.convert(jwt));

        Jwt jwt2 = createJwtWithRoles(12345);
        assertThrows(OAuth2AuthenticationException.class, () -> converter.convert(jwt2));
    }

    @Test
    @DisplayName("Non-string collection elements throw OAuth2AuthenticationException")
    void nonStringElementThrows() {
        Jwt jwt = createJwtWithRoles(List.of("OPERATOR", 42));
        assertThrows(OAuth2AuthenticationException.class, () -> converter.convert(jwt));
    }

    @Test
    @DisplayName("Blank role strings throw OAuth2AuthenticationException")
    void blankRoleStringThrows() {
        Jwt jwt = createJwtWithRoles(List.of("OPERATOR", "   "));
        assertThrows(OAuth2AuthenticationException.class, () -> converter.convert(jwt));
    }

    @Test
    @DisplayName("Unknown or unallowlisted role throws OAuth2AuthenticationException (fail-closed)")
    void unknownRoleThrows() {
        Jwt jwt = createJwtWithRoles(List.of("ADMIN", "SUPERUSER"));
        assertThrows(OAuth2AuthenticationException.class, () -> converter.convert(jwt));

        Jwt jwt2 = createJwtWithRoles(List.of("ROOT"));
        assertThrows(OAuth2AuthenticationException.class, () -> converter.convert(jwt2));
    }
}
