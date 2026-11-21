package com.traceback.api.controller;

import com.traceback.api.dto.DevTokenRequest;
import com.traceback.api.dto.DevTokenResponse;
import com.traceback.api.security.dev.DevTokenService;
import com.traceback.core.security.RemediationAuthorizationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.util.Objects;

/**
 * Controller exposing development token issuance for predefined personas.
 * Available only in dev and local profiles when explicitly enabled.
 * Enforces loopback-only client address restriction and rejects all proxied headers.
 *
 * <p>SECURITY NOTICE:
 * Header rejection (Forwarded, X-Forwarded-For, X-Real-IP) is defense in depth,
 * not a substitute for loopback network interface binding. Exposing the development
 * authentication endpoint through a tunnel (e.g. ngrok, cloudflared) or reverse proxy
 * remains strictly prohibited.
 */
@RestController
@RequestMapping("/api/dev/auth")
@Profile({"dev", "local"})
@ConditionalOnProperty(prefix = "traceback.security.dev-auth", name = "enabled", havingValue = "true")
public class DevAuthController {

    private static final Logger log = LoggerFactory.getLogger(DevAuthController.class);

    private final DevTokenService tokenService;

    public DevAuthController(DevTokenService tokenService) {
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService cannot be null");
    }

    @PostMapping("/token")
    public ResponseEntity<DevTokenResponse> issueToken(
            @Valid @RequestBody DevTokenRequest request,
            HttpServletRequest servletRequest
    ) {
        assertLoopbackRequest(servletRequest);
        DevTokenResponse response = tokenService.issueToken(request);
        return ResponseEntity.ok(response);
    }

    private void assertLoopbackRequest(HttpServletRequest request) {
        // Defense-in-depth: Reject proxied/forwarded requests immediately.
        // Forwarded headers are never trusted to establish client identity.
        if (request.getHeader("Forwarded") != null
                || request.getHeader("X-Forwarded-For") != null
                || request.getHeader("X-Real-IP") != null) {
            log.warn("Dev auth rejected: proxied/forwarded request detected (Forwarded: {}, X-Forwarded-For: {}, X-Real-IP: {})",
                    request.getHeader("Forwarded"), request.getHeader("X-Forwarded-For"), request.getHeader("X-Real-IP"));
            throw new RemediationAuthorizationException(
                    "Development token issuance is restricted to direct loopback connections; proxied requests are forbidden"
            );
        }

        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isBlank()) {
            log.warn("Dev auth rejected: remote address is missing");
            throw new RemediationAuthorizationException("Development token issuance is restricted to loopback connections: remote address is missing");
        }
        try {
            InetAddress addr = InetAddress.getByName(remoteAddr.trim());
            if (!addr.isLoopbackAddress()) {
                log.warn("Dev auth rejected: non-loopback access attempt from {}", remoteAddr);
                throw new RemediationAuthorizationException("Development token issuance is restricted to loopback connections (attempted: " + remoteAddr + ")");
            }
        } catch (Exception e) {
            log.warn("Dev auth rejected: invalid remote address '{}'", remoteAddr);
            throw new RemediationAuthorizationException("Development token issuance is restricted to loopback connections: invalid remote address");
        }
    }
}
