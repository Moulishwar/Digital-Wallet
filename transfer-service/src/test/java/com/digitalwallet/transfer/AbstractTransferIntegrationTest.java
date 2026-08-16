package com.digitalwallet.transfer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Base for transfer-service integration tests: a real PostgreSQL, and a real HTTP server standing in
 * for the two services this one talks to.
 *
 * <p>WireMock rather than a mocked client bean, because the behaviour worth testing here lives in
 * the wire: a 422 body that has to be parsed into a failure reason, a 500, and a socket that simply
 * stops answering. A mocked Java method can return an exception, but it cannot reproduce a read
 * timeout, and the timeout path is the whole reason NEEDS_RECONCILIATION exists.
 *
 * <p>Both wallet-service and auth-service are pointed at the same stub server. Their paths do not
 * overlap, and running one server keeps the fixture simple.
 */
@SpringBootTest
public abstract class AbstractTransferIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    protected static final WireMockServer WIREMOCK =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        POSTGRES.start();
        WIREMOCK.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("wallet.base-url", WIREMOCK::baseUrl);
        registry.add("auth.base-url", WIREMOCK::baseUrl);
        // Short enough that the timeout test does not stall the suite, long enough that an ordinary
        // stubbed response is never mistaken for a slow one.
        registry.add("wallet.read-timeout", () -> "600ms");
        registry.add("wallet.connect-timeout", () -> "600ms");

        // The sweep is driven explicitly in the tests that care about it. Leaving it on a timer
        // would make unrelated tests racy for no benefit.
        registry.add("transfers.reconciliation.interval", () -> "3600s");
        registry.add("transfers.reconciliation.min-age", () -> "0s");
        registry.add("transfers.idempotency.max-wait", () -> "5s");
        registry.add("security.service-credential", () -> "test-service-credential");

        // Tokens in these tests are built by Spring Security Test, so the decoder is never invoked
        // and never fetches this. Whether a real token verifies against a real JWKS is
        // auth-service's concern, proven by running the two together rather than by coupling this
        // suite to another process.
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost:59999/.well-known/jwks.json");
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        // Child rows first: idempotency records reference the transfer they produced.
        jdbcTemplate.execute("DELETE FROM idempotency_record");
        jdbcTemplate.execute("DELETE FROM transfer");
        WIREMOCK.resetAll();
    }

    // ------------------------------------------------------------------ tokens

    /** A verified token for an ordinary user, as the resource server would have produced. */
    protected static JwtRequestPostProcessor userToken(UUID userId) {
        return jwt().jwt(builder -> builder
                        .subject(userId.toString())
                        .claim("handle", "sender")
                        .claim("roles", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    // ------------------------------------------------------------------- stubs

    /** auth-service resolves a handle to a user id. */
    protected static void stubHandleResolves(String handle, UUID userId) {
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/users/lookup"))
                .withQueryParam("handle", com.github.tomakehurst.wiremock.client.WireMock.equalTo(handle))
                .willReturn(okJson("""
                        {"userId": "%s", "handle": "%s", "fullName": "Test User"}
                        """.formatted(userId, handle))));
    }

    /** auth-service has never heard of this handle. */
    protected static void stubHandleUnknown(String handle) {
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/users/lookup"))
                .withQueryParam("handle", com.github.tomakehurst.wiremock.client.WireMock.equalTo(handle))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("""
                                {"type": "https://digitalwallet.example/errors/account-not-found",
                                 "title": "Account not found", "status": 404}
                                """)));
    }

    /** wallet-service accepts the posting and reports the sender's new balance. */
    protected static void stubPostingSucceeds(long senderBalanceAfterMinor) {
        WIREMOCK.stubFor(post(urlPathEqualTo("/internal/postings"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"journalEntryId": "%s", "replayed": false,
                                 "fromBalanceAfterMinor": %d, "toBalanceAfterMinor": 999}
                                """.formatted(UUID.randomUUID(), senderBalanceAfterMinor))));
    }

    /** wallet-service refuses the posting, in the RFC 7807 shape the real service uses. */
    protected static void stubPostingRejected(int status, String errorSlug, String detail) {
        WIREMOCK.stubFor(post(urlPathEqualTo("/internal/postings"))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("""
                                {"type": "https://digitalwallet.example/errors/%s",
                                 "title": "Rejected", "status": %d, "detail": "%s"}
                                """.formatted(errorSlug, status, detail))));
    }

    /** wallet-service accepts the connection and then never answers. */
    protected static void stubPostingTimesOut() {
        WIREMOCK.stubFor(post(urlPathEqualTo("/internal/postings"))
                .willReturn(aResponse().withStatus(201).withFixedDelay(5_000)));
    }

    protected static void stubPostingServerError() {
        WIREMOCK.stubFor(post(urlPathEqualTo("/internal/postings"))
                .willReturn(aResponse().withStatus(500)));
    }

    /** The reconciliation lookup finds a posting that did commit after all. */
    protected static void stubPostingLookupFound() {
        WIREMOCK.stubFor(get(urlPathEqualTo("/internal/postings"))
                .willReturn(okJson("""
                        {"journalEntryId": "%s", "replayed": true}
                        """.formatted(UUID.randomUUID()))));
    }

    /** The connection is dropped mid-request, so the caller gets no status at all. */
    protected static void stubPostingLookupConnectionReset() {
        WIREMOCK.stubFor(get(urlPathEqualTo("/internal/postings"))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));
    }

    /** The reconciliation lookup is certain no such posting exists, so a retry is safe. */
    protected static void stubPostingLookupNotFound() {
        WIREMOCK.stubFor(get(urlPathEqualTo("/internal/postings"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("""
                                {"type": "https://digitalwallet.example/errors/account-not-found",
                                 "title": "Account not found", "status": 404}
                                """)));
    }
}
