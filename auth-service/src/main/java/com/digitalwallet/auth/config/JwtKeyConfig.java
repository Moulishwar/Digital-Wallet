package com.digitalwallet.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The signing key, and the encoder and decoder built from it.
 *
 * <p>Asymmetric on purpose. auth-service holds the private key and is the only thing that can mint
 * a token; every other service verifies with the public key published at the JWKS endpoint. With a
 * shared HMAC secret instead, any service holding the secret could forge a token for any user, so
 * compromising wallet-service would be enough to mint an admin token. Here it is not.
 */
@Configuration
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    @Bean
    public RSAKey signingKey(JwtProperties properties) {
        if (properties.hasConfiguredKeypair()) {
            RSAPublicKey publicKey = PemKeys.publicKey(properties.publicKey());
            RSAPrivateKey privateKey = PemKeys.privateKey(properties.privateKey());
            log.info("Loaded configured RSA signing key (kid={})", properties.keyId());
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(properties.keyId())
                    .build();
        }

        if (!properties.allowEphemeralKeys()) {
            // Refuse to start rather than invent a key. A service that silently generates its own
            // signing material looks healthy while every restart invalidates every token, and
            // running more than one instance would mean each signs with a different key.
            throw new IllegalStateException("""
                    No JWT signing keypair configured.

                    Set JWT_PRIVATE_KEY and JWT_PUBLIC_KEY (PEM) in the environment, or set
                    security.jwt.allow-ephemeral-keys=true if this is a test.
                    """);
        }

        log.warn("=".repeat(78));
        log.warn("Generating an EPHEMERAL RSA signing key. Every token dies with this process,");
        log.warn("and a second instance would sign with a different key. Tests only.");
        log.warn("=".repeat(78));
        KeyPair keyPair = generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey(keyPair.getPrivate())
                .keyID(properties.keyId())
                .build();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey signingKey) {
        return new ImmutableJWKSet<>(new JWKSet(signingKey));
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * Validates the tokens this service itself issued, for its own authenticated endpoints. It
     * uses the public key directly rather than fetching its own JWKS over HTTP.
     */
    @Bean
    public JwtDecoder jwtDecoder(RSAKey signingKey) {
        try {
            return NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build a JWT decoder from the signing key", e);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is unavailable in this JVM", e);
        }
    }
}
