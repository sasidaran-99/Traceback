package com.traceback.api.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;

/**
 * Authentication converter enforcing server-derived actor identity from verified 'sub' claim
 * and delegating role conversion to StrictJwtRoleConverter.
 */
public class StrictJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final StrictJwtRoleConverter roleConverter = new StrictJwtRoleConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.trim().isEmpty()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token", "Mandatory 'sub' claim is missing or empty", null)
            );
        }

        Collection<GrantedAuthority> authorities = roleConverter.convert(jwt);
        return new JwtAuthenticationToken(jwt, authorities, subject.trim());
    }
}
