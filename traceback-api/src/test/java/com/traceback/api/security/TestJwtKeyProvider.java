package com.traceback.api.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ephemeral in-memory RSA keypair provider for integration testing.
 * Generates genuine cryptographically signed JWTs using Nimbus JOSE and RS256.
 * Completely eliminates any external IdP network dependency in tests.
 */
@Component
public class TestJwtKeyProvider {

    public static final String DEFAULT_ISSUER = "https://auth.traceback.test";
    public static final String DEFAULT_AUDIENCE = "traceback-api";

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;
    private final KeyPair alternativeKeyPair; // for generating invalid signatures

    public TestJwtKeyProvider() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair primary = kpg.generateKeyPair();
            this.publicKey = (RSAPublicKey) primary.getPublic();
            this.privateKey = (RSAPrivateKey) primary.getPrivate();

            this.alternativeKeyPair = kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize test RSA keypair", e);
        }
    }

    public RSAPublicKey getPublicKey() {
        return publicKey;
    }

    public RSAPrivateKey getPrivateKey() {
        return privateKey;
    }

    public RSAPublicKey getAlternativePublicKey() {
        return (RSAPublicKey) alternativeKeyPair.getPublic();
    }

    public RSAPrivateKey getAlternativePrivateKey() {
        return (RSAPrivateKey) alternativeKeyPair.getPrivate();
    }

    public String createToken(String subject, List<String> roles) {
        return builder()
                .subject(subject)
                .roles(roles)
                .build();
    }

    public String createUnsignedToken(String subject, List<String> roles) {
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(DEFAULT_ISSUER)
                .audience(DEFAULT_AUDIENCE)
                .issueTime(Date.from(Instant.now().minusSeconds(10)))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(10)))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .claim("roles", roles)
                .build();
        com.nimbusds.jwt.PlainJWT plainJwt = new com.nimbusds.jwt.PlainJWT(claimsSet);
        return plainJwt.serialize();
    }

    public String createHmacToken(String subject, List<String> roles, byte[] hmacSecret) {
        try {
            JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(DEFAULT_ISSUER)
                    .audience(DEFAULT_AUDIENCE)
                    .issueTime(Date.from(Instant.now().minusSeconds(10)))
                    .notBeforeTime(Date.from(Instant.now().minusSeconds(10)))
                    .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                    .claim("roles", roles)
                    .build();
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256).build();
            SignedJWT signedJWT = new SignedJWT(header, claimsSet);
            signedJWT.sign(new com.nimbusds.jose.crypto.MACSigner(hmacSecret));
            return signedJWT.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate test HMAC JWT", e);
        }
    }

    public TokenBuilder builder() {
        return new TokenBuilder();
    }

    public class TokenBuilder {
        private String subject = "test-user-" + UUID.randomUUID().toString().substring(0, 8);
        private Object roles = List.of("OPERATOR");
        private String issuer = DEFAULT_ISSUER;
        private List<String> audience = List.of(DEFAULT_AUDIENCE);
        private Instant issuedAt = Instant.now().minusSeconds(10);
        private Instant expiresAt = Instant.now().plusSeconds(3600);
        private Instant notBefore = Instant.now().minusSeconds(10);
        private JWSAlgorithm algorithm = JWSAlgorithm.RS256;
        private RSAPrivateKey signingKey = privateKey;
        private Map<String, Object> customClaims = null;
        private boolean omitSubject = false;
        private boolean omitIssuedAt = false;
        private boolean omitExpiresAt = false;
        private boolean omitNotBefore = false;

        public TokenBuilder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public TokenBuilder omitSubject() {
            this.omitSubject = true;
            return this;
        }

        public TokenBuilder roles(Object roles) {
            this.roles = roles;
            return this;
        }

        public TokenBuilder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public TokenBuilder audience(String aud) {
            this.audience = List.of(aud);
            return this;
        }

        public TokenBuilder audiences(List<String> audiences) {
            this.audience = audiences;
            return this;
        }

        public TokenBuilder issuedAt(Instant issuedAt) {
            this.issuedAt = issuedAt;
            return this;
        }

        public TokenBuilder omitIssuedAt() {
            this.omitIssuedAt = true;
            return this;
        }

        public TokenBuilder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public TokenBuilder omitExpiresAt() {
            this.omitExpiresAt = true;
            return this;
        }

        public TokenBuilder notBefore(Instant notBefore) {
            this.notBefore = notBefore;
            return this;
        }

        public TokenBuilder omitNotBefore() {
            this.omitNotBefore = true;
            return this;
        }

        public TokenBuilder algorithm(JWSAlgorithm algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        public TokenBuilder signWithInvalidKey() {
            this.signingKey = (RSAPrivateKey) alternativeKeyPair.getPrivate();
            return this;
        }

        public TokenBuilder customClaims(Map<String, Object> claims) {
            this.customClaims = claims;
            return this;
        }

        public String build() {
            try {
                JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder();

                if (!omitSubject && subject != null) {
                    claimsBuilder.subject(subject);
                }
                if (issuer != null) {
                    claimsBuilder.issuer(issuer);
                }
                if (audience != null) {
                    claimsBuilder.audience(audience);
                }
                if (!omitIssuedAt && issuedAt != null) {
                    claimsBuilder.issueTime(Date.from(issuedAt));
                }
                if (!omitExpiresAt && expiresAt != null) {
                    claimsBuilder.expirationTime(Date.from(expiresAt));
                }
                if (!omitNotBefore && notBefore != null) {
                    claimsBuilder.notBeforeTime(Date.from(notBefore));
                }
                if (roles != null) {
                    claimsBuilder.claim("roles", roles);
                }
                if (customClaims != null) {
                    customClaims.forEach(claimsBuilder::claim);
                }

                JWTClaimsSet claimsSet = claimsBuilder.build();
                JWSHeader header = new JWSHeader.Builder(algorithm)
                        .keyID("test-key-1")
                        .build();

                SignedJWT signedJWT = new SignedJWT(header, claimsSet);
                signedJWT.sign(new RSASSASigner(signingKey));
                return signedJWT.serialize();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to generate test signed JWT", e);
            }
        }
    }
}
