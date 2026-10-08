package com.digitalwallet.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token settings, bound from {@code security.jwt.*}.
 *
 * @param accessTokenTtl    short by design: a stolen access token stops working quickly, and it
 *                          cannot be revoked once issued because verification is offline
 * @param refreshTokenTtl   longer, but revocable: refresh tokens are stored, so they can be killed
 * @param privateKey        PEM-encoded PKCS#8 RSA private key, from the environment
 * @param publicKey         PEM-encoded X.509 RSA public key, from the environment
 * @param allowEphemeralKeys generate a throwaway keypair when none is configured. Tests only.
 */
@ConfigurationProperties("security.jwt")
public record JwtProperties(String issuer,
                            Duration accessTokenTtl,
                            Duration refreshTokenTtl,
                            String keyId,
                            String privateKey,
                            String publicKey,
                            boolean allowEphemeralKeys) {

    public boolean hasConfiguredKeypair() {
        return privateKey != null && !privateKey.isBlank()
                && publicKey != null && !publicKey.isBlank();
    }
}
