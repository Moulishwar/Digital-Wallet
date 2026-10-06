package com.digitalwallet.transfer.reconciliation;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.digitalwallet.transfer.AbstractTransferIntegrationTest;
import com.digitalwallet.transfer.domain.Transfer;
import com.digitalwallet.transfer.domain.TransferRepository;
import com.digitalwallet.transfer.domain.TransferStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What happens when wallet-service stops answering mid-transfer.
 *
 * <p>This is the design's one genuinely distributed problem. The posting may or may not have
 * committed, and there is no way to tell from here — so the transfer is recorded as unresolved and
 * the client is told 202 rather than being given a guess dressed up as an answer.
 *
 * <p>The sweep then settles it by asking wallet-service whether a posting with this transfer's id
 * exists. Both directions are tested below, because getting either one wrong loses money: treating
 * "found" as "retry" pays twice, and treating "not found" as "done" pays never.
 */
@AutoConfigureMockMvc
class ReconciliationIT extends AbstractTransferIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReconciliationJob reconciliationJob;

    @Autowired
    private TransferRepository transferRepository;

    private static String sendBody(String handle, long amountMinor) {
        return """
                {"recipientHandle": "%s", "amountMinor": %d, "note": null}
                """.formatted(handle, amountMinor);
    }

    /** Drives a transfer to NEEDS_RECONCILIATION by making the posting call time out. */
    private UUID transferWithUnknownOutcome(UUID sender) throws Exception {
        stubHandleResolves("alice", UUID.randomUUID());
        stubPostingTimesOut();

        String response = mockMvc.perform(post("/api/transfers")
                        .with(userToken(sender))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("alice", 5_000L)))
                // 202, not 201 and not a failure: recorded, outcome not yet known.
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("NEEDS_RECONCILIATION"))
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(response.replaceAll(".*\"transferId\":\"([^\"]+)\".*", "$1"));
    }

    @Test
    @DisplayName("a timed-out posting yields 202 and an unresolved transfer, not a guess")
    void timeoutIsRecordedHonestly() throws Exception {
        UUID sender = UUID.randomUUID();

        UUID transferId = transferWithUnknownOutcome(sender);

        assertThat(transferRepository.findById(transferId))
                .get()
                .extracting(Transfer::getStatus)
                .isEqualTo(TransferStatus.NEEDS_RECONCILIATION);

        // The client can poll it, and sees the same honest answer.
        mockMvc.perform(get("/api/transfers/" + transferId).with(userToken(sender)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_RECONCILIATION"));
    }

    @Test
    @DisplayName("a 500 from wallet-service is also unresolved — it may have committed before it broke")
    void serverErrorIsUnresolvedNotFailed() throws Exception {
        stubHandleResolves("alice", UUID.randomUUID());
        stubPostingServerError();

        mockMvc.perform(post("/api/transfers")
                        .with(userToken(UUID.randomUUID()))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("alice", 5_000L)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("NEEDS_RECONCILIATION"));
    }

    @Test
    @DisplayName("the sweep completes a transfer whose posting did commit after all")
    void sweepCompletesAPostingThatLanded() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID transferId = transferWithUnknownOutcome(sender);

        // wallet-service confirms a posting with this transfer's reference exists. Because that
        // reference is unique in the ledger, the answer is unambiguous.
        WIREMOCK.resetAll();
        stubPostingLookupFound();

        reconciliationJob.sweep();

        assertThat(transferRepository.findById(transferId))
                .get()
                .extracting(Transfer::getStatus)
                .isEqualTo(TransferStatus.COMPLETED);

        // Crucially, it did not post again. That would have paid twice.
        WIREMOCK.verify(0, postRequestedFor(urlPathEqualTo("/internal/postings")));
    }

    @Test
    @DisplayName("the sweep retries a posting that never committed, and completes it")
    void sweepRetriesAPostingThatNeverLanded() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID transferId = transferWithUnknownOutcome(sender);

        WIREMOCK.resetAll();
        stubPostingLookupNotFound();
        stubPostingSucceeds(45_000L);

        reconciliationJob.sweep();

        assertThat(transferRepository.findById(transferId))
                .get()
                .extracting(Transfer::getStatus)
                .isEqualTo(TransferStatus.COMPLETED);

        // The retry is rebuilt from the stored transfer, not from the long-gone request, and must
        // still carry the labels the recipient's statement will show. auth-service is not asked
        // again: the stubs above were reset, and the sweep has no user token to ask with.
        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/internal/postings"))
                .withRequestBody(matchingJsonPath("$.fromName", equalTo(SENDER_NAME)))
                .withRequestBody(matchingJsonPath("$.toName", equalTo(RECIPIENT_NAME))));
    }

    @Test
    @DisplayName("a retry that wallet-service refuses settles the transfer as failed")
    void sweepFailsATransferWalletRefuses() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID transferId = transferWithUnknownOutcome(sender);

        WIREMOCK.resetAll();
        stubPostingLookupNotFound();
        stubPostingRejected(422, "insufficient-funds", "Balance is 0 but the posting requires 5000");

        reconciliationJob.sweep();

        Transfer settled = transferRepository.findById(transferId).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(settled.getFailureReason()).isNotNull();
    }

    @Test
    @DisplayName("an unrecognised error leaves the transfer unresolved rather than failing it on a "
            + "misunderstanding")
    void sweepLeavesUnrecognisableErrorsAlone() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID transferId = transferWithUnknownOutcome(sender);

        // No stubs: every request gets a bare 404 with no Problem Detail. That is what a mistyped
        // route or a proxy answering for an absent service looks like — an error about our
        // plumbing, not a refusal of the payment. Reading it as a rejection would permanently fail
        // a transfer that was never actually refused, and tell the sender so.
        //
        // An unresolved transfer is a visible problem; a wrongly failed one is invisible.
        WIREMOCK.resetAll();

        reconciliationJob.sweep();

        assertThat(transferRepository.findById(transferId))
                .get()
                .extracting(Transfer::getStatus)
                .isEqualTo(TransferStatus.NEEDS_RECONCILIATION);
    }

    @Test
    @DisplayName("a wallet-service that cannot be reached at all also leaves the transfer alone")
    void sweepLeavesUnreachableTransfersAlone() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID transferId = transferWithUnknownOutcome(sender);

        // The connection is dropped mid-request, so the lookup throws rather than returning any
        // status at all. The sweep must survive that and change nothing.
        WIREMOCK.resetAll();
        stubPostingLookupConnectionReset();

        reconciliationJob.sweep();

        assertThat(transferRepository.findById(transferId))
                .get()
                .extracting(Transfer::getStatus)
                .isEqualTo(TransferStatus.NEEDS_RECONCILIATION);
    }

    @Test
    @DisplayName("one unreachable transfer does not stop the rest of the batch settling")
    void oneStuckTransferDoesNotBlockTheBatch() throws Exception {
        UUID sender = UUID.randomUUID();
        transferWithUnknownOutcome(sender);
        transferWithUnknownOutcome(sender);

        WIREMOCK.resetAll();
        stubPostingLookupFound();

        reconciliationJob.sweep();

        List<Transfer> transfers = transferRepository.findAll();
        assertThat(transfers).hasSize(2)
                .allSatisfy(t -> assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETED));
    }
}
