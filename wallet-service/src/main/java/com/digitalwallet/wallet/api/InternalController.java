package com.digitalwallet.wallet.api;

import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
import com.digitalwallet.common.money.Money;
import com.digitalwallet.wallet.account.Account;
import com.digitalwallet.wallet.account.WalletService;
import com.digitalwallet.wallet.api.dto.WalletDtos.CreateAccountRequest;
import com.digitalwallet.wallet.api.dto.WalletDtos.PostingRequest;
import com.digitalwallet.wallet.api.dto.WalletDtos.PostingResponse;
import com.digitalwallet.wallet.api.dto.WalletDtos.WalletResponse;
import com.digitalwallet.wallet.ledger.LedgerPostingService;
import com.digitalwallet.wallet.ledger.PostingCommand;
import com.digitalwallet.wallet.ledger.PostingResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoints. These are never routed through the gateway — the gateway's route
 * table simply has no entry under {@code /internal}, so they are unreachable from outside the
 * network the services share.
 *
 * <p>M5 additionally requires a service credential header here, so that reaching the network is
 * not by itself enough to move money.
 */
@RestController
@RequestMapping("/internal")
@Tag(name = "Internal", description = "Service-to-service only. Not exposed through the gateway.")
public class InternalController {

    private final WalletService walletService;
    private final LedgerPostingService postingService;

    public InternalController(WalletService walletService, LedgerPostingService postingService) {
        this.walletService = walletService;
        this.postingService = postingService;
    }

    /**
     * Provisions a wallet. Called by auth-service when a user registers.
     *
     * <p>Idempotent by design: calling it for a user who already has a wallet returns the existing
     * one with 200 rather than failing. Registration retries are normal, and a hard failure here
     * would block a signup for no good reason.
     */
    @PostMapping("/accounts")
    @Operation(summary = "Provision a wallet for a user (idempotent)")
    public ResponseEntity<WalletResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        boolean existedBefore = walletService.walletExistsFor(request.ownerUserId());
        Account wallet = walletService.provisionWallet(request.ownerUserId());
        return ResponseEntity.status(existedBefore ? HttpStatus.OK : HttpStatus.CREATED)
                .body(WalletResponse.from(wallet));
    }

    /**
     * Posts a balanced two-sided movement between two users' wallets.
     *
     * <p>This is the endpoint transfer-service calls. It takes a positive amount and derives the
     * debit side itself, so a caller cannot accidentally submit two credits and create money.
     *
     * <p>The request names <em>users</em>, not accounts. Resolving them here keeps account ids
     * inside the service that owns them, and lets a transfer to someone whose wallet was never
     * provisioned — because this service was down when they registered (section 7.1) — succeed
     * instead of failing for a reason the sender cannot act on.
     *
     * <p>Returns 200 rather than 201 when {@code externalRef} has been seen before, which is how
     * the caller's reconciliation sweep distinguishes "already committed" from "just committed".
     */
    @PostMapping("/postings")
    @Operation(summary = "Post a balanced journal entry between two wallets (idempotent on externalRef)")
    public ResponseEntity<PostingResponse> post(@Valid @RequestBody PostingRequest request) {
        if (request.fromOwnerUserId().equals(request.toOwnerUserId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "fromOwnerUserId and toOwnerUserId must differ");
        }

        // Idempotent and race-safe — see WalletService.provisionWallet.
        Account from = walletService.provisionWallet(request.fromOwnerUserId());
        Account to = walletService.provisionWallet(request.toOwnerUserId());

        PostingCommand command = PostingCommand.transfer(
                request.externalRef(),
                from.getId(),
                to.getId(),
                Money.positive(request.amountMinor()),
                request.description());

        PostingResult result;
        try {
            result = postingService.post(command);
        } catch (DataIntegrityViolationException raced) {
            // Another caller committed this same externalRef first. Its outcome is the right
            // answer for us too — that is what makes the reference an idempotency key.
            result = postingService.findByExternalRef(request.externalRef()).orElseThrow(() -> raced);
        }

        // A replay only speaks for this request if it is the same movement. If the reference was
        // reused for a different pair of wallets, saying "already done" would report someone
        // else's posting as this caller's success.
        if (!result.balancesAfter().containsKey(from.getId())
                || !result.balancesAfter().containsKey(to.getId())) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "externalRef already belongs to a posting between different wallets");
        }

        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(PostingResponse.of(result, from.getId(), to.getId()));
    }

    /**
     * Looks up a posting by the caller's reference.
     *
     * <p>Exists for reconciliation: when a caller's request timed out and it does not know whether
     * the posting committed, this answers definitively. 404 means it never happened and is safe to
     * retry.
     */
    @GetMapping("/postings")
    @Operation(summary = "Find a posting by externalRef, for reconciliation after a timeout")
    public PostingResponse findByExternalRef(@RequestParam String externalRef) {
        return postingService.findByExternalRef(externalRef)
                .map(PostingResponse::existing)
                .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND,
                        "No posting exists with that externalRef"));
    }
}
