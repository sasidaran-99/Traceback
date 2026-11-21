package com.traceback.api.remediation;

import com.traceback.core.security.RemediationActorRole;
import com.traceback.core.security.RemediationPrincipal;
import com.traceback.core.security.RemediationSecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Interceptor that bridges Spring Security's authenticated JWT principal into the
 * thread-local RemediationSecurityContext for downstream core services.
 * Header-based identity and role trust (X-Actor-Id, X-Actor-Role, X-Tenant-Id)
 * are completely decommissioned; identity and authorities are derived exclusively
 * from the cryptographically verified Spring Security context.
 */
@Component
public class RemediationSecurityInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RemediationSecurityInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !auth.getName().isBlank()) {
            RemediationActorRole role = RemediationActorRole.VIEWER;
            for (GrantedAuthority authority : auth.getAuthorities()) {
                if ("ROLE_ADMIN".equals(authority.getAuthority())) {
                    role = RemediationActorRole.ADMIN;
                    break;
                } else if ("ROLE_OPERATOR".equals(authority.getAuthority())) {
                    role = RemediationActorRole.OPERATOR;
                }
            }

            String tenantScope = null;
            if (auth instanceof JwtAuthenticationToken jwtToken) {
                tenantScope = jwtToken.getToken().getClaimAsString("tenant_id");
                if (tenantScope == null) {
                    tenantScope = jwtToken.getToken().getClaimAsString("tenant_scope");
                }
            }

            RemediationPrincipal principal = new RemediationPrincipal(auth.getName().trim(), role, tenantScope);
            RemediationSecurityContext.setPrincipal(principal);
            log.debug("Remediation security context bridged from Spring Security: actor='{}', role='{}'", principal.actorId(), principal.role());
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RemediationSecurityContext.clear();
    }
}
