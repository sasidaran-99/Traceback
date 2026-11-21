package com.traceback.api.security.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Ephemeral in-memory RSA keypair manager for development-only JWT token issuance and verification.
 * Shared between DevTokenService (private key signing) and SecurityConfig (public key decoding).
 * Strictly isolated: activated only when dev/local profile is active and dev-auth is explicitly enabled.
 */
@Component
@Profile({"dev", "local"})
@ConditionalOnProperty(prefix = "traceback.security.dev-auth", name = "enabled", havingValue = "true")
public class DevKeyManager {

    private static final Logger log = LoggerFactory.getLogger(DevKeyManager.class);

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;

    public DevKeyManager() {
        try {
            log.info("Initializing application-managed RSA keypair for development authentication bridge");
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            this.publicKey = (RSAPublicKey) kp.getPublic();
            this.privateKey = (RSAPrivateKey) kp.getPrivate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize development RSA keypair", e);
        }
    }

    public RSAPublicKey getPublicKey() {
        return publicKey;
    }

    public RSAPrivateKey getPrivateKey() {
        return privateKey;
    }
}
