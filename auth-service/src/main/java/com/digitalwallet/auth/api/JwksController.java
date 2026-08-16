package com.digitalwallet.auth.api;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the public half of the signing key so other services can verify tokens on their own.
 *
 * <p>This is what keeps verification offline: wallet-service fetches this once, caches it, and then
 * validates every request locally. No call back to auth-service per request, so auth-service being
 * down does not stop already-issued tokens from working.
 */
@RestController
@Tag(name = "JWKS", description = "Public keys for verifying tokens issued by this service")
public class JwksController {

    private final RSAKey signingKey;

    public JwksController(RSAKey signingKey) {
        this.signingKey = signingKey;
    }

    @GetMapping("/.well-known/jwks.json")
    @Operation(summary = "Public key set used to verify access tokens")
    public Map<String, Object> jwks() {
        // toPublicJWK() strips the private exponent and CRT parameters. Serializing the full key
        // here would hand out the ability to mint tokens, so this call is the entire security
        // boundary of this endpoint — there is a test asserting the response has no "d" member.
        return new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }
}
