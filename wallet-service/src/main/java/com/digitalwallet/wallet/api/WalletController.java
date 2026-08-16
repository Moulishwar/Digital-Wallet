package com.digitalwallet.wallet.api;

import com.digitalwallet.common.money.Money;
import com.digitalwallet.wallet.account.Account;
import com.digitalwallet.wallet.account.TopUpOutcome;
import com.digitalwallet.wallet.account.WalletService;
import com.digitalwallet.wallet.api.dto.WalletDtos.StatementResponse;
import com.digitalwallet.wallet.api.dto.WalletDtos.TopUpRequest;
import com.digitalwallet.wallet.api.dto.WalletDtos.TopUpResponse;
import com.digitalwallet.wallet.api.dto.WalletDtos.WalletResponse;
import com.digitalwallet.wallet.security.CurrentUserProvider;
import com.digitalwallet.wallet.statement.StatementPage;
import com.digitalwallet.wallet.statement.StatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own wallet.
 *
 * <p>Every route is scoped to {@code /me} and resolves identity through
 * {@link CurrentUserProvider}. There is deliberately no {@code userId} path variable or query
 * parameter anywhere in this controller — if there were, the server would have to decide whether
 * the caller is allowed to use it, and forgetting that check even once is how insecure direct
 * object reference vulnerabilities happen. Not accepting the parameter removes the question.
 */
@RestController
@RequestMapping("/api/wallets/me")
@Validated
@Tag(name = "Wallet", description = "Balance, top-up and statement for the calling user")
public class WalletController {

    private final WalletService walletService;
    private final StatementService statementService;
    private final CurrentUserProvider currentUserProvider;

    public WalletController(WalletService walletService,
                            StatementService statementService,
                            CurrentUserProvider currentUserProvider) {
        this.walletService = walletService;
        this.statementService = statementService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    @Operation(summary = "Current balance and status of the caller's wallet")
    public WalletResponse getWallet() {
        UUID userId = currentUserProvider.requireCurrentUserId();
        Account wallet = walletService.requireWalletFor(userId);
        return WalletResponse.from(wallet);
    }

    /**
     * Adds money to the wallet.
     *
     * <p>The {@code Idempotency-Key} header is required rather than optional. A top-up that times
     * out leaves the client unable to tell whether it succeeded, and the only safe thing it can do
     * is retry — which must not deposit twice. Making the key mandatory means every caller is
     * correct by default instead of only the careful ones.
     *
     * <p>Returns 201 for a posting that actually moved money and 200 when the key had already been
     * used, so a client can tell a fresh top-up from a replay.
     */
    @PostMapping("/topups")
    @Operation(summary = "Add money to the wallet from the simulated funding source")
    public ResponseEntity<TopUpResponse> topUp(
            @RequestHeader("Idempotency-Key")
            @NotBlank @Size(max = 64)
            @Parameter(description = "Client-generated unique key. Retrying with the same key returns "
                    + "the original result instead of depositing again.", required = true)
            String idempotencyKey,

            @Valid @RequestBody TopUpRequest request) {

        UUID userId = currentUserProvider.requireCurrentUserId();
        TopUpOutcome outcome = walletService.topUp(
                userId, Money.positive(request.amountMinor()), idempotencyKey);

        TopUpResponse body = new TopUpResponse(
                outcome.journalEntryId(),
                outcome.balanceMinor(),
                Money.ofMinor(outcome.balanceMinor()).toMajor(),
                outcome.replayed());

        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    @GetMapping("/statement")
    @Operation(summary = "Paginated ledger history, newest first")
    public StatementResponse statement(
            @RequestParam(required = false)
            @Parameter(description = "The nextCursor from the previous page. Omit for the first page.")
            String cursor,

            @RequestParam(defaultValue = "20")
            @Parameter(description = "Rows per page, capped at 100")
            int size) {

        UUID userId = currentUserProvider.requireCurrentUserId();
        StatementPage page = statementService.statementFor(userId, cursor, size);
        return StatementResponse.from(page);
    }
}
