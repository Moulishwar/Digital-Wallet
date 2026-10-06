package com.digitalwallet.transfer.api;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.digitalwallet.transfer.AbstractTransferIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract: authentication, ownership, status codes, validation and error shape.
 */
@AutoConfigureMockMvc
class TransferApiIT extends AbstractTransferIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static String sendBody(String handle, long amountMinor, String note) {
        return """
                {"recipientHandle": "%s", "amountMinor": %d, "note": %s}
                """.formatted(handle, amountMinor, note == null ? "null" : "\"" + note + "\"");
    }

    @Test
    @DisplayName("a request with no token is rejected as 401 with a Problem Detail body")
    void missingTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/transfers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    @DisplayName("a successful transfer is 201 and reports the sender's new balance")
    void happyPath() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        stubHandleResolves("alice", recipient);
        stubPostingSucceeds(45_000L);

        mockMvc.perform(post("/api/transfers")
                        .with(userToken(sender))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("alice", 5_000L, "Dinner")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amountMinor").value(5_000))
                .andExpect(jsonPath("$.amount").value(50.00))
                .andExpect(jsonPath("$.note").value("Dinner"))
                .andExpect(jsonPath("$.recipientUserId").value(recipient.toString()))
                .andExpect(jsonPath("$.recipientHandle").value("alice"))
                .andExpect(jsonPath("$.recipientName").value(RECIPIENT_NAME))
                .andExpect(jsonPath("$.senderBalanceAfterMinor").value(45_000))
                .andExpect(jsonPath("$.failureReason", nullValue()))
                .andExpect(jsonPath("$.transferId", notNullValue()));
    }

    @Test
    @DisplayName("the posting carries both parties' names and the note, so each statement can say "
            + "who the money came from or went to")
    void postingCarriesStatementLabels() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        stubHandleResolves("alice", recipient);
        stubPostingSucceeds(45_000L);

        mockMvc.perform(post("/api/transfers")
                        .with(userToken(sender))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("alice", 5_000L, "Dinner")))
                .andExpect(status().isCreated());

        WIREMOCK.verify(postRequestedFor(urlPathEqualTo("/internal/postings"))
                .withRequestBody(matchingJsonPath("$.memo", equalTo("Dinner")))
                .withRequestBody(matchingJsonPath("$.fromHandle", equalTo(SENDER_HANDLE)))
                .withRequestBody(matchingJsonPath("$.fromName", equalTo(SENDER_NAME)))
                .withRequestBody(matchingJsonPath("$.toHandle", equalTo("alice")))
                .withRequestBody(matchingJsonPath("$.toName", equalTo(RECIPIENT_NAME)))
                // Identity still comes from the token, not from the profile lookup, whose id the
                // fixture deliberately makes different.
                .withRequestBody(matchingJsonPath("$.fromOwnerUserId", equalTo(sender.toString()))));
    }

    @Test
    @DisplayName("a recipient written as @Handle is found, because the @ is stripped and case folded "
            + "before the lookup")
    void leadingAtSignIsAccepted() throws Exception {
        UUID recipient = UUID.randomUUID();
        stubHandleResolves("alice", recipient);
        stubPostingSucceeds(45_000L);

        mockMvc.perform(post("/api/transfers")
                        .with(userToken(UUID.randomUUID()))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("  @Alice ", 5_000L, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recipientUserId").value(recipient.toString()));

        WIREMOCK.verify(getRequestedFor(urlPathEqualTo("/api/users/lookup"))
                .withQueryParam("handle", equalTo("alice")));
    }

    @Test
    @DisplayName("insufficient funds is a 422 Problem Detail, and the transfer is recorded as FAILED")
    void insufficientFundsFails() throws Exception {
        UUID sender = UUID.randomUUID();
        stubHandleResolves("alice", UUID.randomUUID());
        stubPostingRejected(422, "insufficient-funds", "Balance is 1000 but the posting requires 5000");

        mockMvc.perform(post("/api/transfers")
                        .with(userToken(sender))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("alice", 5_000L, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://digitalwallet.example/errors/insufficient-funds"))
                .andExpect(jsonPath("$.title").value("Insufficient funds"));

        assertOneTransferWithStatus(sender, "FAILED");
    }

    @Test
    @DisplayName("a frozen wallet is reported as its own reason, not lumped in with insufficient funds")
    void frozenAccountFails() throws Exception {
        UUID sender = UUID.randomUUID();
        stubHandleResolves("alice", UUID.randomUUID());
        stubPostingRejected(409, "account-not-active", "Account is FROZEN");

        mockMvc.perform(post("/api/transfers")
                        .with(userToken(sender))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("alice", 5_000L, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://digitalwallet.example/errors/account-not-active"));

        String reason = jdbcTemplate.queryForObject(
                "SELECT failure_reason FROM transfer WHERE sender_user_id = ?", String.class, sender);
        org.assertj.core.api.Assertions.assertThat(reason).isEqualTo("ACCOUNT_NOT_ACTIVE");
    }

    @Test
    @DisplayName("an unknown handle is a 404 and never creates a transfer")
    void unknownRecipient() throws Exception {
        UUID sender = UUID.randomUUID();
        stubHandleUnknown("nobody");

        mockMvc.perform(post("/api/transfers")
                        .with(userToken(sender))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("nobody", 5_000L, null)))
                .andExpect(status().isNotFound());

        assertTransferCount(0);
    }

    @Test
    @DisplayName("sending to yourself is rejected before anything is written")
    void selfTransferRejected() throws Exception {
        UUID sender = UUID.randomUUID();
        stubHandleResolves("myself", sender);

        mockMvc.perform(post("/api/transfers")
                        .with(userToken(sender))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("myself", 5_000L, null)))
                .andExpect(status().isBadRequest());

        assertTransferCount(0);
    }

    @Test
    @DisplayName("a transfer without an Idempotency-Key is a 400, not a 500")
    void missingIdempotencyKeyIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .with(userToken(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("alice", 5_000L, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a non-positive amount is rejected by validation with the offending field named")
    void nonPositiveAmountRejected() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .with(userToken(UUID.randomUUID()))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("alice", -100L, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Request validation failed"))
                .andExpect(jsonPath("$.errors.amountMinor", notNullValue()));
    }

    @Test
    @DisplayName("an amount over the per-transfer ceiling is refused")
    void amountCeilingEnforced() throws Exception {
        stubHandleResolves("alice", UUID.randomUUID());

        mockMvc.perform(post("/api/transfers")
                        .with(userToken(UUID.randomUUID()))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("alice", 10_000_001L, null)))
                .andExpect(status().isBadRequest());

        assertTransferCount(0);
    }

    @Test
    @DisplayName("one user cannot read another user's transfer, and cannot tell it apart from a "
            + "transfer that does not exist")
    void ownershipIsCheckedAgainstTheToken() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID snooper = UUID.randomUUID();
        stubHandleResolves("alice", UUID.randomUUID());
        stubPostingSucceeds(1_000L);

        String response = mockMvc.perform(post("/api/transfers")
                        .with(userToken(sender))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("alice", 5_000L, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String transferId = response.replaceAll(".*\"transferId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/transfers/" + transferId).with(userToken(sender)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // 404, not 403: a 403 would confirm the id is real, which is enough to map out other
        // people's activity by probing.
        mockMvc.perform(get("/api/transfers/" + transferId).with(userToken(snooper)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("history shows only your own transfers, newest first, and pages with a cursor")
    void historyIsScopedAndPaged() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        stubHandleResolves("alice", UUID.randomUUID());
        stubPostingSucceeds(1_000L);

        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/api/transfers")
                            .with(userToken(sender))
                            .header("Idempotency-Key", "key-" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(sendBody("alice", 100L * i, "note " + i)))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/api/transfers")
                        .with(userToken(otherUser))
                        .header("Idempotency-Key", "other-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("alice", 999L, null)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/transfers").with(userToken(sender)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfers", hasSize(3)))
                .andExpect(jsonPath("$.transfers[0].note").value("note 3"))
                .andExpect(jsonPath("$.nextCursor", nullValue()));

        String firstPage = mockMvc.perform(get("/api/transfers")
                        .with(userToken(sender))
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfers", hasSize(2)))
                .andExpect(jsonPath("$.nextCursor", notNullValue()))
                .andReturn().getResponse().getContentAsString();

        String cursor = firstPage.replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/transfers")
                        .with(userToken(sender))
                        .param("size", "2")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfers", hasSize(1)))
                .andExpect(jsonPath("$.transfers[0].note").value("note 1"));
    }

    @Test
    @DisplayName("a malformed cursor is a 400, not a server error")
    void malformedCursorRejected() throws Exception {
        mockMvc.perform(get("/api/transfers")
                        .with(userToken(UUID.randomUUID()))
                        .param("cursor", "not-a-real-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Malformed transfer cursor"));
    }

    private void assertTransferCount(int expected) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM transfer", Integer.class);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(expected);
    }

    private void assertOneTransferWithStatus(UUID sender, String status) {
        String actual = jdbcTemplate.queryForObject(
                "SELECT status FROM transfer WHERE sender_user_id = ?", String.class, sender);
        org.assertj.core.api.Assertions.assertThat(actual).isEqualTo(status);
    }
}
