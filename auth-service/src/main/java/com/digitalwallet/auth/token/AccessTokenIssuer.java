package com.digitalwallet.auth.token;

import com.digitalwallet.auth.config.JwtProperties;
import com.digitalwallet.auth.user.AppUser;
import com.digitalwallet.auth.user.Role;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Mints short-lived RS256 access tokens.
 *
 * <p>The claims are deliberately minimal: who the caller is, what they may do, and when the token
 * stops being valid. Nothing sensitive goes in: a JWT is signed, not encrypted, so anyone holding
 * one can read every claim in it. Email addresses and names stay out for that reason.
 */
@Service
public class AccessTokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public AccessTokenIssuer(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public IssuedAccessToken issue(AppUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        List<String> roles = user.getRoles().stream().map(Role::name).sorted().toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                // The subject is the user id, not the handle or email: it is the only identifier
                // that never changes, and downstream services key their own data on it.
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .claim("handle", user.getHandle())
                .claim("roles", roles)
                .build();

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                // Tells verifiers which key from the JWKS to check against, so the key can be
                // rotated by publishing a second one before retiring the first.
                .keyId(properties.keyId())
                .build();

        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(value, expiresAt, properties.accessTokenTtl().toSeconds());
    }

    public record IssuedAccessToken(String value, Instant expiresAt, long expiresInSeconds) {
    }
}
