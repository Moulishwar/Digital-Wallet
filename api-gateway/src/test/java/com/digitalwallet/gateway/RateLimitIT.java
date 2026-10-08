package com.digitalwallet.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.concurrent.atomic.AtomicInteger;
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
 * The rate limiter, with deliberately tiny allowances so the boundary is reachable in a test.
 *
 * <p>The case that matters most is the last one. Keying the limit by IP alone would mean two users
 * behind the same address — an office, a mobile carrier, any NAT — share one allowance, so one busy
 * client silently throttles strangers. Keying authenticated traffic by token subject is what stops
 * that, and it is invisible unless something checks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class RateLimitIT {

    private static final String ISSUER = "https://digitalwallet.example/auth";
    private static final int AUTHENTICATED_CAPACITY = 5;
    private static final int ANONYMOUS_CAPACITY = 3;

    private static final WireMockServer AUTH =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer WALLET =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private static RSAKey signingKey;

    static {
        AUTH.start();
        WALLET.start();
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
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> AUTH.baseUrl() + "/.well-known/jwks.json");
        registry.add("security.jwt.expected-issuer", () -> ISSUER);

        registry.add("gateway.rate-limit.enabled", () -> true);
        registry.add("gateway.rate-limit.authenticated.capacity", () -> AUTHENTICATED_CAPACITY);
        // No refill worth speaking of during a test, so the burst allowance is the whole allowance
        // and the boundary is deterministic rather than a race against the clock.
        registry.add("gateway.rate-limit.authenticated.refill-per-second", () -> 0.001);
        registry.add("gateway.rate-limit.anonymous.capacity", () -> ANONYMOUS_CAPACITY);
        registry.add("gateway.rate-limit.anonymous.refill-per-second", () -> 0.001);
        // The test client connects from loopback, so loopback plays the reverse proxy.
        registry.add("gateway.rate-limit.trusted-proxies", () -> "127.0.0.1/32");
    }

    @Autowired
    private WebTestClient webTestClient;

    @BeforeEach
    void stubs() {
        AUTH.resetAll();
        WALLET.resetAll();
        AUTH.stubFor(get(urlPathEqualTo("/.well-known/jwks.json"))
                .willReturn(okJson("{\"keys\":[" + signingKey.toPublicJWK().toJSONString() + "]}")));
        AUTH.stubFor(post(urlPathEqualTo("/api/auth/login")).willReturn(okJson("{}")));
        WALLET.stubFor(get(urlPathEqualTo("/api/wallets/me")).willReturn(okJson("{}")));
    }

    private static String tokenFor(UUID userId) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(userId.toString())
                    .issuer(ISSUER)
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(Date.from(Instant.now().plusSeconds(900)))
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

    /** @return how many of {@code attempts} requests were allowed through */
    private int burst(int attempts, String token) {
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < attempts; i++) {
            Integer status = webTestClient.get().uri("/api/wallets/me")
                    .header("Authorization", "Bearer " + token)
                    .exchange()
                    .returnResult(Void.class)
                    .getStatus().value();
            if (status == 200) {
                allowed.incrementAndGet();
            }
        }
        return allowed.get();
    }

    @Test
    @DisplayName("a user's burst is capped, and the refusal is a 429 Problem Detail with Retry-After")
    void authenticatedBurstIsCapped() {
        String token = tokenFor(UUID.randomUUID());

        assertThat(burst(AUTHENTICATED_CAPACITY, token))
                .as("everything within the allowance goes through")
                .isEqualTo(AUTHENTICATED_CAPACITY);

        webTestClient.get().uri("/api/wallets/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectBody()
                .jsonPath("$.title").isEqualTo("Too many requests")
                .jsonPath("$.type").isEqualTo("https://digitalwallet.example/errors/rate-limit-exceeded");
    }

    @Test
    @DisplayName("public routes are limited too, and more tightly — that is where password guessing "
            + "arrives")
    void anonymousRoutesAreLimited() {
        int allowed = 0;
        int refused = 0;
        for (int i = 0; i < ANONYMOUS_CAPACITY + 3; i++) {
            int status = webTestClient.post().uri("/api/auth/login")
                    .header("Content-Type", "application/json")
                    .bodyValue("{\"email\":\"a@b.c\",\"password\":\"guess-" + i + "\"}")
                    .exchange()
                    .returnResult(Void.class)
                    .getStatus().value();
            if (status == 429) {
                refused++;
            } else {
                allowed++;
            }
        }

        assertThat(allowed).isEqualTo(ANONYMOUS_CAPACITY);
        assertThat(refused).isEqualTo(3);
    }

    @Test
    @DisplayName("one user exhausting their allowance does not throttle another user on the same "
            + "address")
    void allowancesAreScopedPerUserNotPerAddress() {
        String heavyUser = tokenFor(UUID.randomUUID());
        String quietUser = tokenFor(UUID.randomUUID());

        // Spend the first user's allowance completely, and then some.
        burst(AUTHENTICATED_CAPACITY + 3, heavyUser);

        webTestClient.get().uri("/api/wallets/me")
                .header("Authorization", "Bearer " + heavyUser)
                .exchange()
                .expectStatus().isEqualTo(429);

        // Same source address, different person. Their allowance is untouched. Keyed by IP, this
        // request would be refused for something someone else did.
        webTestClient.get().uri("/api/wallets/me")
                .header("Authorization", "Bearer " + quietUser)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("behind a trusted proxy, each visitor it reports gets their own allowance")
    void visitorsBehindTheProxyAreLimitedSeparately() {
        for (int i = 0; i < ANONYMOUS_CAPACITY; i++) {
            login("203.0.113.10").expectStatus().isOk();
        }
        login("203.0.113.10").expectStatus().isEqualTo(429);

        // Another visitor arriving through the same proxy. Keyed by the proxy's own address, this
        // would be refused for what the first visitor did.
        login("203.0.113.11").expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec login(String visitor) {
        return webTestClient.post().uri("/api/auth/login")
                .header("X-Real-IP", visitor)
                .header("Content-Type", "application/json")
                .bodyValue("{\"email\":\"a@b.c\",\"password\":\"guess\"}")
                .exchange();
    }
}
