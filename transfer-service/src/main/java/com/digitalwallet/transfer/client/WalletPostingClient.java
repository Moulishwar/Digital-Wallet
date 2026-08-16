package com.digitalwallet.transfer.client;

import com.digitalwallet.common.security.ServiceCredential;
import com.digitalwallet.transfer.domain.FailureReason;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Asks wallet-service to move money, and reads its answer honestly.
 *
 * <p>The whole job of this class is classification: turning an HTTP result into one of the three
 * {@link PostingOutcome} cases. The temptation to collapse "it failed" and "I do not know whether it
 * failed" into one branch is exactly what this exists to resist — that conflation is how a system
 * either loses a payment or makes it twice.
 *
 * <p>The request names users, not accounts. Wallet account ids are wallet-service's private
 * business, and this service never sees one.
 */
@Component
public class WalletPostingClient {

    private static final Logger log = LoggerFactory.getLogger(WalletPostingClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public WalletPostingClient(RestClient.Builder builder,
                               ObjectMapper objectMapper,
                               @Value("${wallet.base-url}") String baseUrl,
                               @Value("${wallet.connect-timeout}") Duration connectTimeout,
                               @Value("${wallet.read-timeout}") Duration readTimeout,
                               @Value("${security.service-credential}") String serviceCredential) {

        // Explicit timeouts. The defaults are effectively infinite, so a wedged wallet-service
        // would hold request threads open until the pool is exhausted and this service stopped
        // answering too — one service's bad day becoming two.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                // Set once on the client, so no future call can be added that forgets it. Note this
                // is the service's own credential, distinct from the caller's bearer token — the
                // ledger does not act on a user's authority, it acts on this service's.
                .defaultHeader(ServiceCredential.HEADER, serviceCredential)
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Posts the movement.
     *
     * @param externalRef the transfer id. Unique in the ledger, which is what makes a later retry
     *                    of this exact call incapable of moving the money twice.
     */
    public PostingOutcome post(UUID externalRef, UUID senderUserId, UUID recipientUserId,
                               long amountMinor, String description) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalRef", externalRef.toString());
        body.put("fromOwnerUserId", senderUserId.toString());
        body.put("toOwnerUserId", recipientUserId.toString());
        body.put("amountMinor", amountMinor);
        body.put("description", description);

        ResponseEntity<String> response;
        try {
            response = restClient.post()
                    .uri("/internal/postings")
                    .body(body)
                    // Suppress the default "throw on error status" behaviour: a 422 here is a
                    // business answer to classify, not an exception to unwind on.
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, res) -> { })
                    .toEntity(String.class);
        } catch (ResourceAccessException io) {
            // Timed out, or the connection failed. The posting may or may not have committed.
            log.warn("Posting {} did not return an answer: {}", externalRef, io.toString());
            return new PostingOutcome.Unknown(io.getMostSpecificCause().toString());
        } catch (RuntimeException unexpected) {
            log.warn("Posting {} failed unexpectedly: {}", externalRef, unexpected.toString());
            return new PostingOutcome.Unknown(unexpected.toString());
        }

        HttpStatusCode status = response.getStatusCode();

        if (status.is2xxSuccessful()) {
            return parsePosted(externalRef, response.getBody());
        }

        if (status.is5xxServerError()) {
            // wallet-service broke while handling this. It may have committed before it broke.
            log.warn("Posting {} got {} from wallet-service", externalRef, status);
            return new PostingOutcome.Unknown("wallet-service returned " + status);
        }

        return classify(externalRef, status, response.getBody());
    }

    /**
     * Asks whether a posting with this reference exists — the question the reconciliation sweep
     * needs answered after an unknown outcome.
     *
     * @return empty when wallet-service is certain there is no such posting, so retrying is safe.
     *         An {@link Optional} is not enough to express "could not reach it", so an unreachable
     *         service throws rather than being mistaken for a definite "no".
     */
    public Optional<UUID> findPosting(UUID externalRef) {
        ResponseEntity<String> response = restClient.get()
                .uri(uri -> uri.path("/internal/postings")
                        .queryParam("externalRef", externalRef.toString())
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, res) -> { })
                .toEntity(String.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            return Optional.ofNullable(readPosting(response.getBody())).map(WalletPosting::journalEntryId);
        }
        if (response.getStatusCode().value() == 404) {
            return Optional.empty();
        }
        throw new IllegalStateException(
                "wallet-service returned " + response.getStatusCode() + " looking up " + externalRef);
    }

    private PostingOutcome parsePosted(UUID externalRef, String body) {
        WalletPosting posting = readPosting(body);
        if (posting == null || posting.fromBalanceAfterMinor() == null) {
            // A 2xx we cannot read means the posting probably happened but we cannot say what it
            // produced. Treating that as success would report a balance we never received.
            log.warn("Posting {} returned an unreadable 2xx body", externalRef);
            return new PostingOutcome.Unknown("Unreadable response from wallet-service");
        }
        return new PostingOutcome.Posted(
                posting.journalEntryId(), posting.fromBalanceAfterMinor(), posting.replayed());
    }

    /**
     * Classifies a 4xx.
     *
     * <p>Only an error this service actually recognises becomes a rejection, because a rejection is
     * terminal — the transfer can never settle afterwards. An unfamiliar 4xx is far more likely to
     * be an infrastructure problem (a stale route, a rejected credential, a proxy answering for a
     * service that is not there) than a refusal of the payment, and permanently failing someone's
     * transfer over one would tell them their payment was refused when it was never seen.
     */
    private PostingOutcome classify(UUID externalRef, HttpStatusCode status, String body) {
        String type = null;
        String detail = null;
        try {
            ProblemDetailBody problem = objectMapper.readValue(body, ProblemDetailBody.class);
            type = problem.type();
            detail = problem.detail();
        } catch (Exception unreadable) {
            log.debug("Could not read the Problem Detail from a {} on posting {}", status, externalRef);
        }

        Optional<FailureReason> reason = FailureReason.fromProblemType(type);
        if (reason.isEmpty()) {
            log.warn("Posting {} got an unrecognised {} from wallet-service (type={}) — treating the "
                    + "outcome as unknown rather than failing the transfer", externalRef, status, type);
            return new PostingOutcome.Unknown("Unrecognised " + status + " from wallet-service");
        }

        log.info("Posting {} rejected with {} ({})", externalRef, status, reason.get());
        return new PostingOutcome.Rejected(reason.get(),
                detail != null ? detail : reason.get().detail());
    }

    private WalletPosting readPosting(String body) {
        try {
            return objectMapper.readValue(body, WalletPosting.class);
        } catch (Exception unreadable) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WalletPosting(UUID journalEntryId,
                                 boolean replayed,
                                 Long fromBalanceAfterMinor,
                                 Long toBalanceAfterMinor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProblemDetailBody(String type, String title, String detail) {
    }
}
