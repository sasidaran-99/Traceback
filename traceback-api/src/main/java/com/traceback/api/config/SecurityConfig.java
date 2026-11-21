package com.traceback.api.config;

import com.traceback.api.security.AudienceValidator;
import com.traceback.api.security.JwtSecurityProperties;
import com.traceback.api.security.MandatoryClaimsValidator;
import com.traceback.api.security.StrictJwtAuthenticationConverter;
import com.traceback.api.security.dev.DevKeyManager;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

/**
 * Spring Security configuration establishing OAuth2 Resource Server with asymmetric JWT verification.
 * Enforces deny-by-default endpoint authorization, stateless sessions, and strict validation.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtSecurityProperties properties;
    private final Environment environment;
    private final ObjectProvider<DevKeyManager> devKeyManagerProvider;
    private static volatile RSAPublicKey devPublicKey;

    public SecurityConfig(JwtSecurityProperties properties, Environment environment) {
        this(properties, environment, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SecurityConfig(
            JwtSecurityProperties properties,
            Environment environment,
            ObjectProvider<DevKeyManager> devKeyManagerProvider
    ) {
        this.properties = properties;
        this.environment = environment;
        this.devKeyManagerProvider = devKeyManagerProvider;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder
    ) throws Exception {

        AuthenticationEntryPoint authenticationEntryPoint = (request, response, authException) -> {
            log.warn("Unauthorized access attempt to {}: {}", request.getRequestURI(), authException.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            String msg = authException.getMessage() != null ? authException.getMessage() : "Authentication required";
            String json = String.format(
                    "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"%s\",\"path\":\"%s\",\"fieldErrors\":[]}",
                    escapeJson(msg), escapeJson(request.getRequestURI()));
            response.getWriter().write(json);
        };

        AccessDeniedHandler accessDeniedHandler = (request, response, accessDeniedException) -> {
            log.warn("Access denied for request to {}: {}", request.getRequestURI(), accessDeniedException.getMessage());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            String msg = accessDeniedException.getMessage() != null ? accessDeniedException.getMessage() : "Access denied";
            String json = String.format(
                    "{\"status\":403,\"error\":\"Forbidden\",\"message\":\"%s\",\"path\":\"%s\",\"fieldErrors\":[]}",
                    escapeJson(msg), escapeJson(request.getRequestURI()));
            response.getWriter().write(json);
        };

        boolean devAuthPermitted = isDevAuthPermitted();

        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/health", "/api/health/**", "/actuator/health", "/actuator/info").permitAll();
                if (devAuthPermitted) {
                    auth.requestMatchers(HttpMethod.POST, "/api/dev/auth/token", "/api/dev/auth/**").permitAll();
                }
                auth.requestMatchers("/api/remediations/refs/**").hasRole("ADMIN")
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/remediations/*/break-glass", "/api/remediations/*/break-glass/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/api/remediations/*/break-glass", "/api/remediations/*/break-glass/**").hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
                    .requestMatchers(HttpMethod.GET, "/api/remediations", "/api/remediations/**").hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
                    .requestMatchers(HttpMethod.POST, "/api/remediations", "/api/remediations/**").hasAnyRole("ADMIN", "OPERATOR")
                    .requestMatchers(HttpMethod.PUT, "/api/remediations/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/api/remediations/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PATCH, "/api/remediations/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/api/investigations/**").hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
                    .requestMatchers(HttpMethod.POST, "/api/investigations/**").hasAnyRole("ADMIN", "OPERATOR")
                    .requestMatchers(HttpMethod.GET, "/api/events/**").hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
                    .requestMatchers(HttpMethod.POST, "/api/events/**").hasAnyRole("ADMIN", "OPERATOR")
                    .anyRequest().denyAll();
            })
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(strictJwtAuthenticationConverter())
                )
            );

        return http.build();
    }

    @Bean
    public StrictJwtAuthenticationConverter strictJwtAuthenticationConverter() {
        return new StrictJwtAuthenticationConverter();
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder() {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        String issuer = properties.getIssuer();
        String audience = properties.getAudience();
        String jwkSetUri = properties.getJwkSetUri();
        String publicKeyLocation = properties.getPublicKeyLocation();

        if (isProd) {
            boolean devAuthEnabled = Boolean.parseBoolean(
                    environment.getProperty("traceback.security.dev-auth.enabled", "false")
            );
            if (devAuthEnabled) {
                throw new IllegalStateException("CRITICAL SECURITY ERROR: Development authentication bridge cannot be enabled in 'prod' profile");
            }
            if (issuer == null || issuer.isBlank()) {
                throw new IllegalStateException("Production security failure: 'traceback.security.jwt.issuer' must be explicitly configured");
            }
            if (audience == null || audience.isBlank()) {
                throw new IllegalStateException("Production security failure: 'traceback.security.jwt.audience' must be explicitly configured");
            }
            if ((jwkSetUri == null || jwkSetUri.isBlank()) && (publicKeyLocation == null || publicKeyLocation.isBlank())) {
                throw new IllegalStateException("Production security failure: either 'traceback.security.jwt.jwk-set-uri' or 'traceback.security.jwt.public-key-location' must be explicitly configured");
            }
        }

        NimbusJwtDecoder decoder;
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        } else if (!isProd) {
            // Local dev fallback - use DevKeyManager keypair if active, otherwise generate fallback
            DevKeyManager devKeyManager = (devKeyManagerProvider != null) ? devKeyManagerProvider.getIfAvailable() : null;
            RSAPublicKey devKey = (devKeyManager != null) ? devKeyManager.getPublicKey() : getOrCreateDevPublicKey();
            decoder = NimbusJwtDecoder.withPublicKey(devKey).build();
            if (issuer == null || issuer.isBlank()) {
                issuer = "https://auth.traceback.dev";
            }
        } else {
            throw new IllegalStateException("JWT Decoder configuration missing public key or JWKS endpoint");
        }

        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuer != null ? issuer : "https://auth.traceback.dev");
        OAuth2TokenValidator<Jwt> combinedValidator = new DelegatingOAuth2TokenValidator<>(
                withIssuer,
                new AudienceValidator(audience != null && !audience.isBlank() ? audience : "traceback-api"),
                new MandatoryClaimsValidator()
        );
        decoder.setJwtValidator(combinedValidator);
        return decoder;
    }

    private synchronized RSAPublicKey getOrCreateDevPublicKey() {
        if (devPublicKey == null) {
            try {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                KeyPair kp = kpg.generateKeyPair();
                devPublicKey = (RSAPublicKey) kp.getPublic();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to generate dev RSA key pair", e);
            }
        }
        return devPublicKey;
    }

    private boolean isDevAuthPermitted() {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (isProd) {
            return false;
        }
        boolean hasDevProfile = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "dev".equalsIgnoreCase(p) || "local".equalsIgnoreCase(p));
        boolean propEnabled = Boolean.parseBoolean(
                environment.getProperty("traceback.security.dev-auth.enabled", "false")
        );
        return hasDevProfile && propEnabled;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
