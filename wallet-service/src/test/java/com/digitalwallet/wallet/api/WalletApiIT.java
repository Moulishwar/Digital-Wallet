package com.digitalwallet.wallet.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.digitalwallet.common.security.ServiceCredential;
import com.digitalwallet.wallet.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract: authentication, authorization, status codes, validation and error shape.
 *
 * <p>Tokens here are constructed by Spring Security Test rather than fetched from auth-service.
 * That keeps these tests about wallet-service's own behaviour — whether a real token verifies
 * against a real JWKS is auth-service's concern, and it is covered by driving both services
 * together rather than by coupling this suite to another running process.
 */
@AutoConfigureMockMvc
class WalletApiIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** A verified token for an ordinary user, as the resource server would have produced. */
    private static JwtRequestPostProcessor userToken(UUID userId) {
        return jwt().jwt(builder -> builder
                        .subject(userId.toString())
                        .claim("handle", "test_user")
                        .claim("roles", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static JwtRequestPostProcessor adminToken() {
        return jwt().jwt(builder -> builder
                        .subject(UUID.randomUUID().toString())
                        .claim("roles", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private UUID provisionUser() throws Exception {
        UUID userId = UUID.randomUUID();
        mockMvc.perform(post("/internal/accounts")
                        .with(serviceCall())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerUserId": "%s"}
                                """.formatted(userId)))
                .andExpect(status().isCreated());
        return userId;
    }

    @Test
    @DisplayName("provisioning the same user twice returns the existing wallet rather than failing")
    void provisioningIsIdempotent() throws Exception {
        UUID userId = provisionUser();

        mockMvc.perform(post("/internal/accounts")
                        .with(serviceCall())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerUserId": "%s"}
                                """.formatted(userId)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a request with no token is rejected as 401 with a Problem Detail body")
    void missingTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/wallets/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.type").value("https://digitalwallet.example/errors/unauthorized"));
    }

    @Test
    @DisplayName("identity comes from the token subject, so one user cannot read another's wallet")
    void tokenSubjectDecidesWhoseWalletIsReturned() throws Exception {
        UUID alice = provisionUser();
        UUID bob = provisionUser();

        mockMvc.perform(post("/api/wallets/me/topups")
                        .with(userToken(alice))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor": 40000}
                                """))
                .andExpect(status().isCreated());

        // Bob's token sees Bob's wallet. There is no parameter he could change to see Alice's,
        // because the API never accepts a user id.
        mockMvc.perform(get("/api/wallets/me").with(userToken(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(0));

        mockMvc.perform(get("/api/wallets/me").with(userToken(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(40000));
    }

    @Test
    @DisplayName("a top-up without an Idempotency-Key is a 400, not a 500")
    void missingIdempotencyKeyIsBadRequest() throws Exception {
        UUID userId = provisionUser();

        mockMvc.perform(post("/api/wallets/me/topups")
                        .with(userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor": 10000}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a non-positive amount is rejected by validation with the offending field named")
    void nonPositiveAmountIsRejected() throws Exception {
        UUID userId = provisionUser();

        mockMvc.perform(post("/api/wallets/me/topups")
                        .with(userToken(userId))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor": -500}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Request validation failed"))
                .andExpect(jsonPath("$.errors.amountMinor", notNullValue()));
    }

    @Test
    @DisplayName("top-up returns 201 the first time and 200 when the same key is replayed")
    void topUpStatusDistinguishesFreshFromReplay() throws Exception {
        UUID userId = provisionUser();
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/wallets/me/topups")
                        .with(userToken(userId))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor": 25000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceMinor").value(25000))
                .andExpect(jsonPath("$.balance").value(250.00))
                .andExpect(jsonPath("$.replayed").value(false));

        mockMvc.perform(post("/api/wallets/me/topups")
                        .with(userToken(userId))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor": 25000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(25000))
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    @DisplayName("one user's idempotency key does not collide with another's")
    void idempotencyKeysAreScopedPerUser() throws Exception {
        UUID alice = provisionUser();
        UUID bob = provisionUser();

        for (UUID user : List.of(alice, bob)) {
            mockMvc.perform(post("/api/wallets/me/topups")
                            .with(userToken(user))
                            .header("Idempotency-Key", "shared-key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amountMinor": 5000}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.replayed").value(false));
        }
    }

    @Test
    @DisplayName("the statement lists postings newest first with a running balance")
    void statementReportsHistory() throws Exception {
        UUID userId = provisionUser();
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/api/wallets/me/topups")
                            .with(userToken(userId))
                            .header("Idempotency-Key", "key-" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amountMinor": 1000}
                                    """))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/wallets/me/statement").with(userToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(3)))
                .andExpect(jsonPath("$.lines[0].type").value("TOPUP"))
                .andExpect(jsonPath("$.lines[0].amountMinor").value(1000))
                .andExpect(jsonPath("$.lines[0].balanceAfterMinor").value(3000))
                .andExpect(jsonPath("$.nextCursor", nullValue()));
    }

    @Test
    @DisplayName("statement paging hands back a cursor and does not repeat rows")
    void statementPagesWithACursor() throws Exception {
        UUID userId = provisionUser();
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post("/api/wallets/me/topups")
                            .with(userToken(userId))
                            .header("Idempotency-Key", "page-key-" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amountMinor": 500}
                                    """))
                    .andExpect(status().isCreated());
        }

        String response = mockMvc.perform(get("/api/wallets/me/statement")
                        .with(userToken(userId))
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.nextCursor", notNullValue()))
                .andReturn().getResponse().getContentAsString();

        String cursor = response.replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/wallets/me/statement")
                        .with(userToken(userId))
                        .param("size", "2")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].balanceAfterMinor").value(1500));
    }

    @Test
    @DisplayName("a malformed cursor is a 400, not a server error")
    void malformedCursorIsRejected() throws Exception {
        UUID userId = provisionUser();

        mockMvc.perform(get("/api/wallets/me/statement")
                        .with(userToken(userId))
                        .param("cursor", "not-a-real-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Malformed statement cursor"));
    }

    @Test
    @DisplayName("reconciliation reports the ledger as consistent")
    void reconciliationIsClean() throws Exception {
        UUID userId = provisionUser();
        mockMvc.perform(post("/api/wallets/me/topups")
                .with(userToken(userId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amountMinor": 9900}
                        """));

        mockMvc.perform(get("/api/admin/reconciliation").with(adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.ledgerSumMinor").value(0))
                .andExpect(jsonPath("$.accountsWithDriftedBalance", hasSize(0)));
    }

    @Test
    @DisplayName("an ordinary user cannot reach the admin endpoint")
    void adminEndpointRequiresTheAdminRole() throws Exception {
        mockMvc.perform(get("/api/admin/reconciliation").with(userToken(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    // ------------------------------------------------------- /internal/postings
    //
    // The endpoint transfer-service calls. It is addressed in owner user ids, never account ids:
    // wallet account identifiers are this service's private business, and a caller that never
    // learns one cannot post against the wrong account or drift out of step with a change here.

    private String postingBody(String externalRef, UUID from, UUID to, long amountMinor) {
        return """
                {"externalRef": "%s", "fromOwnerUserId": "%s", "toOwnerUserId": "%s",
                 "amountMinor": %d, "description": "Transfer"}
                """.formatted(externalRef, from, to, amountMinor);
    }

    @Test
    @DisplayName("a posting moves money between two users named by id, and reports both balances")
    void postingMovesMoneyBetweenUsers() throws Exception {
        UUID alice = provisionUser();
        UUID bob = provisionUser();
        topUp(alice, 30000);

        mockMvc.perform(post("/internal/postings")
                        .with(serviceCall())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postingBody("transfer-1", alice, bob, 12000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.fromBalanceAfterMinor").value(18000))
                .andExpect(jsonPath("$.toBalanceAfterMinor").value(12000))
                .andExpect(jsonPath("$.journalEntryId", notNullValue()));
    }

    @Test
    @DisplayName("a recipient who never got a wallet is provisioned on the way in, rather than "
            + "having the transfer fail for a reason the sender cannot act on")
    void postingProvisionsAMissingRecipientWallet() throws Exception {
        UUID alice = provisionUser();
        UUID strangerWithNoWallet = UUID.randomUUID();
        topUp(alice, 5000);

        mockMvc.perform(post("/internal/postings")
                        .with(serviceCall())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postingBody("transfer-2", alice, strangerWithNoWallet, 5000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.toBalanceAfterMinor").value(5000));
    }

    @Test
    @DisplayName("replaying an externalRef returns 200 and the original posting, moving nothing more")
    void postingIsIdempotentOnExternalRef() throws Exception {
        UUID alice = provisionUser();
        UUID bob = provisionUser();
        topUp(alice, 30000);

        mockMvc.perform(post("/internal/postings")
                        .with(serviceCall())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postingBody("transfer-3", alice, bob, 12000)))
                .andExpect(status().isCreated());

        // 200 rather than 201 is how the caller's reconciliation sweep tells "already committed"
        // from "just committed".
        mockMvc.perform(post("/internal/postings")
                        .with(serviceCall())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postingBody("transfer-3", alice, bob, 12000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.fromBalanceAfterMinor").value(18000));

        mockMvc.perform(get("/api/wallets/me").with(userToken(alice)))
                .andExpect(jsonPath("$.balanceMinor").value(18000));
    }

    @Test
    @DisplayName("reusing an externalRef for a different pair of wallets is a conflict, not a "
            + "success reported against someone else's posting")
    void replayedReferenceMustNameTheSameWallets() throws Exception {
        UUID alice = provisionUser();
        UUID bob = provisionUser();
        UUID carol = provisionUser();
        topUp(alice, 30000);

        mockMvc.perform(post("/internal/postings")
                        .with(serviceCall())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postingBody("transfer-4", alice, bob, 5000)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/internal/postings")
                        .with(serviceCall())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postingBody("transfer-4", alice, carol, 5000)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a posting larger than the balance is refused as 422 and changes nothing")
    void postingBeyondTheBalanceIsRefused() throws Exception {
        UUID alice = provisionUser();
        UUID bob = provisionUser();
        topUp(alice, 1000);

        mockMvc.perform(post("/internal/postings")
                        .with(serviceCall())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postingBody("transfer-5", alice, bob, 5000)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://digitalwallet.example/errors/insufficient-funds"));

        mockMvc.perform(get("/api/wallets/me").with(userToken(alice)))
                .andExpect(jsonPath("$.balanceMinor").value(1000));
    }

    @Test
    @DisplayName("a posting from a user to themselves is rejected")
    void postingToSelfIsRejected() throws Exception {
        UUID alice = provisionUser();

        mockMvc.perform(post("/internal/postings")
                        .with(serviceCall())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postingBody("transfer-6", alice, alice, 1000)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("looking up an externalRef answers definitively, which is what makes a retry safe")
    void postingLookupAnswersForReconciliation() throws Exception {
        UUID alice = provisionUser();
        UUID bob = provisionUser();
        topUp(alice, 5000);

        mockMvc.perform(get("/internal/postings").with(serviceCall()).param("externalRef", "never-happened"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/internal/postings")
                        .with(serviceCall())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postingBody("transfer-7", alice, bob, 5000)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/internal/postings").with(serviceCall()).param("externalRef", "transfer-7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journalEntryId", notNullValue()));
    }

    @Test
    @DisplayName("an internal endpoint refuses a caller with no service credential")
    void internalRoutesRequireTheServiceCredential() throws Exception {
        UUID alice = provisionUser();
        UUID bob = provisionUser();

        // Being on the network is not authority to move money.
        mockMvc.perform(post("/internal/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postingBody("no-credential", alice, bob, 1000)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/internal/postings").param("externalRef", "anything"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a wrong service credential is refused, and a user token is not a substitute for it")
    void internalRoutesRefuseWrongOrUserCredentials() throws Exception {
        UUID alice = provisionUser();
        UUID bob = provisionUser();

        mockMvc.perform(post("/internal/postings")
                        .header(ServiceCredential.HEADER, "not-the-credential")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postingBody("wrong-credential", alice, bob, 1000)))
                .andExpect(status().isUnauthorized());

        // A perfectly valid user token grants ROLE_USER, and /internal requires ROLE_SERVICE. No
        // ordinary user, however legitimate, can post arbitrary movements into the ledger.
        mockMvc.perform(post("/internal/postings")
                        .with(userToken(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postingBody("user-token", alice, bob, 1000)))
                .andExpect(status().isForbidden());
    }

    private void topUp(UUID userId, long amountMinor) throws Exception {
        mockMvc.perform(post("/api/wallets/me/topups")
                        .with(userToken(userId))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor": %d}
                                """.formatted(amountMinor)))
                .andExpect(status().isCreated());
    }
}
