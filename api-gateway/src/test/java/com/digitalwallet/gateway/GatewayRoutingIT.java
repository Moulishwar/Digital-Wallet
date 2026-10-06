package com.digitalwallet.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * What the gateway does with a request before any service sees it.
 *
 * <p>Tokens here are real: signed with a throwaway RSA key that the stubbed JWKS endpoint publishes,
 * so the resource-server path is genuinely exercised — signature, expiry and issuer all verified
 * against a key set fetched over HTTP. Faking the authentication would have skipped the one thing
 * this milestone adds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayRoutingIT {

    private static final String ISSUER = "https://digitalwallet.example/auth";

    private static final WireMockServer AUTH =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer WALLET =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer TRANSFER =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private static RSAKey signingKey;

    static {
        AUTH.start();
        WALLET.start();
        TRANSFER.start();
        try {
            signingKey = new RSAKeyGenerator(2048).keyID("test-signing-key").generate();
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate a test signing key", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("AUTH_SERVICE_URL", AUTH::baseUrl);
        registry.add("WALLET_SERVICE_URL", WALLET::baseUrl);
        registry.add("TRANSFER_SERVICE_URL", TRANSFER::baseUrl);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> AUTH.baseUrl() + "/.well-known/jwks.json");
        registry.add("security.jwt.expected-issuer", () -> ISSUER);
        // Routing and authentication are what these tests are about. The limiter has its own.
        registry.add("gateway.rate-limit.enabled", () -> false);
    }

    @Autowired
    private WebTestClient webTestClient;

    @BeforeAll
    static void publishTheKeySet() {
        AUTH.stubFor(get(urlPathEqualTo("/.well-known/jwks.json"))
                .willReturn(okJson("{\"keys\":[" + signingKey.toPublicJWK().toJSONString() + "]}")));
    }

    @BeforeEach
    void resetStubs() {
        WALLET.resetAll();
        TRANSFER.resetAll();
        AUTH.resetAll();
        publishTheKeySet();
    }

    /** A genuine RS256 token, signed with the key the stubbed JWKS publishes. */
    private static String tokenFor(UUID userId, String issuer, Instant expiry) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(userId.toString())
                    .issuer(issuer)
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(Date.from(expiry))
                    .claim("handle", "tester")
                    .claim("roles", List.of("ROLE_USER"))
                    .build();

            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Could not mint a test token", e);
        }
    }

    private static String validToken() {
        return tokenFor(UUID.randomUUID(), ISSUER, Instant.now().plusSeconds(900));
    }

    // ---------------------------------------------------------------- routing

    @Test
    @DisplayName("login reaches auth-service without a token, because it is how you get one")
    void authRoutesArePublic() {
        AUTH.stubFor(post(urlPathEqualTo("/api/auth/login"))
                .willReturn(okJson("{\"accessToken\":\"x\"}")));

        webTestClient.post().uri("/api/auth/login")
                .header("Content-Type", "application/json")
                .bodyValue("{\"email\":\"a@b.c\",\"password\":\"secret\"}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("the public key set is reachable without a token")
    void jwksIsPublic() {
        webTestClient.get().uri("/.well-known/jwks.json")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("a valid token is routed through to the wallet and transfer services")
    void authenticatedRoutesReachTheirService() {
        WALLET.stubFor(get(urlPathEqualTo("/api/wallets/me"))
                .willReturn(okJson("{\"balanceMinor\":1000}")));
        TRANSFER.stubFor(get(urlPathEqualTo("/api/transfers"))
                .willReturn(okJson("{\"transfers\":[]}")));

        String token = validToken();

        webTestClient.get().uri("/api/wallets/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.balanceMinor").isEqualTo(1000);

        webTestClient.get().uri("/api/transfers")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        // The token is passed through untouched, so the service can verify it for itself rather
        // than trusting that the gateway did.
        WALLET.verify(getRequestedFor(urlPathEqualTo("/api/wallets/me"))
                .withHeader("Authorization", equalTo("Bearer " + token)));
    }

    // --------------------------------------------------------- rejected early

    @Test
    @DisplayName("a request with no token never reaches the service")
    void missingTokenIsRejectedAtTheEdge() {
        WALLET.stubFor(get(urlPathEqualTo("/api/wallets/me")).willReturn(okJson("{}")));

        webTestClient.get().uri("/api/wallets/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectBody()
                .jsonPath("$.title").isEqualTo("Unauthorized")
                .jsonPath("$.type").isEqualTo("https://digitalwallet.example/errors/unauthorized");

        // The point of checking at the edge: the service was never troubled.
        WALLET.verify(0, getRequestedFor(urlPathEqualTo("/api/wallets/me")));
    }

    @Test
    @DisplayName("an expired token is rejected at the edge")
    void expiredTokenIsRejected() {
        WALLET.stubFor(get(urlPathEqualTo("/api/wallets/me")).willReturn(okJson("{}")));

        String expired = tokenFor(UUID.randomUUID(), ISSUER, Instant.now().minusSeconds(60));

        webTestClient.get().uri("/api/wallets/me")
                .header("Authorization", "Bearer " + expired)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith("application/problem+json");

        WALLET.verify(0, getRequestedFor(urlPathEqualTo("/api/wallets/me")));
    }

    @Test
    @DisplayName("a correctly signed token from the wrong issuer is still rejected")
    void wrongIssuerIsRejected() {
        WALLET.stubFor(get(urlPathEqualTo("/api/wallets/me")).willReturn(okJson("{}")));

        // Signed with the right key, so the signature verifies — but minted by something else.
        // Without the issuer check this would sail through.
        String foreign = tokenFor(UUID.randomUUID(), "https://someone-else.example/auth",
                Instant.now().plusSeconds(900));

        webTestClient.get().uri("/api/wallets/me")
                .header("Authorization", "Bearer " + foreign)
                .exchange()
                .expectStatus().isUnauthorized();

        WALLET.verify(0, getRequestedFor(urlPathEqualTo("/api/wallets/me")));
    }

    @Test
    @DisplayName("a token that is not signed by the published key is rejected, with the same "
            + "Problem Detail body as a missing token")
    void forgedTokenIsRejected() {
        webTestClient.get().uri("/api/wallets/me")
                .header("Authorization", "Bearer not.a.real.token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectBody()
                .jsonPath("$.title").isEqualTo("Unauthorized")
                .jsonPath("$.type").isEqualTo("https://digitalwallet.example/errors/unauthorized");
    }

    // ------------------------------------------------------------- /internal

    @Test
    @DisplayName("the service-to-service endpoints are not reachable through the gateway, with or "
            + "without a valid token")
    void internalRoutesAreNotExposed() {
        WALLET.stubFor(post(urlPathEqualTo("/internal/postings"))
                .willReturn(aResponse().withStatus(201)));

        // Unauthenticated.
        webTestClient.post().uri("/internal/postings")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();

        // And with a perfectly good user token, because being a legitimate user is not authority to
        // post arbitrary movements into the ledger.
        webTestClient.post().uri("/internal/postings")
                .header("Authorization", "Bearer " + validToken())
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isForbidden();

        WALLET.verify(0, com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(urlPathEqualTo("/internal/postings")));
    }

    @Test
    @DisplayName("an unrouted path is a 404 rather than being forwarded somewhere")
    void unknownPathIsNotRouted() {
        webTestClient.get().uri("/api/nonexistent")
                .header("Authorization", "Bearer " + validToken())
                .exchange()
                .expectStatus().isNotFound();
    }
}
