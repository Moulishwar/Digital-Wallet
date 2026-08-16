package com.digitalwallet.auth.config;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Parses PEM-encoded RSA keys supplied through configuration.
 *
 * <p>Whitespace is stripped rather than trusted, because these arrive through environment
 * variables and {@code .env} files where line wrapping is unpredictable.
 */
final class PemKeys {

    private PemKeys() {
    }

    static RSAPrivateKey privateKey(String pem) {
        byte[] der = decode(pem, "PRIVATE KEY");
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(
                    "security.jwt.private-key is not a valid PKCS#8 RSA private key", e);
        }
    }

    static RSAPublicKey publicKey(String pem) {
        byte[] der = decode(pem, "PUBLIC KEY");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(
                    "security.jwt.public-key is not a valid X.509 RSA public key", e);
        }
    }

    private static byte[] decode(String pem, String label) {
        String base64 = pem
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Key is not valid base64 PEM content", e);
        }
    }
}
