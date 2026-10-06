package com.digitalwallet.transfer.api;

import com.digitalwallet.transfer.api.dto.TransferDtos.SendTransferRequest;
import com.digitalwallet.transfer.api.dto.TransferDtos.TransferPageResponse;
import com.digitalwallet.transfer.api.dto.TransferDtos.TransferResponse;
import com.digitalwallet.transfer.domain.TransferService;
import com.digitalwallet.transfer.domain.TransferService.SendOutcome;
import com.digitalwallet.transfer.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sending money, and looking at what you have sent.
 *
 * <p>There is no {@code userId} parameter anywhere in this controller. Identity comes from the
 * verified token on every route, so there is nothing for a caller to tamper with and no per-endpoint
 * decision about whether they are allowed to use the id they supplied.
 */
@RestController
@RequestMapping("/api/transfers")
@Validated
@Tag(name = "Transfers", description = "Send money to another user and track what happened")
public class TransferController {

    private final TransferService transferService;
    private final CurrentUserProvider currentUserProvider;

    public TransferController(TransferService transferService,
                              CurrentUserProvider currentUserProvider) {
        this.transferService = transferService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Sends money to another user, found by handle.
     *
     * <p>{@code Idempotency-Key} is required rather than optional. A transfer that times out leaves
     * the client unable to tell whether it succeeded, and the only thing it can safely do is retry —
     * which must not pay twice. Requiring the key makes every caller correct by default instead of
     * only the careful ones.
     *
     * <p>Three success-ish outcomes are possible and they mean different things. <b>201</b>: the
     * money moved. <b>202</b>: the request is recorded but wallet-service did not say whether it
     * took effect, so the outcome will be settled shortly and can be polled by id. A rejection comes
     * back as a Problem Detail with the status matching the reason.
     *
     * <p>The response is written as pre-serialized JSON so that a replayed request returns the
     * original response rather than a fresh rendering of it — the same fields and values, though
     * not byte for byte, since jsonb storage reorders keys (see {@code TransferService.SendOutcome}).
     */
    @PostMapping
    @Operation(summary = "Send money to another user (idempotent)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The money moved",
                    content = @Content(schema = @Schema(implementation = TransferResponse.class))),
            @ApiResponse(responseCode = "202", description = "Recorded; the outcome is not yet known",
                    content = @Content(schema = @Schema(implementation = TransferResponse.class))),
            @ApiResponse(responseCode = "409", description = "The key was reused with a different body, "
                    + "or an earlier request holding it is still running"),
            @ApiResponse(responseCode = "422", description = "Rejected — for example, insufficient funds")
    })
    public ResponseEntity<String> send(
            @RequestHeader("Idempotency-Key")
            @NotBlank @Size(max = 64)
            @Parameter(description = "Client-generated unique key. Retrying with the same key returns "
                    + "the original result instead of sending again.", required = true)
            String idempotencyKey,

            @Valid @RequestBody SendTransferRequest request) {

        SendOutcome outcome = transferService.send(idempotencyKey, request);

        return ResponseEntity.status(outcome.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(outcome.body());
    }

    /**
     * One transfer's current status.
     *
     * <p>This is what a client polls after a 202. Ownership is re-checked against the token here,
     * not assumed from the fact that an id was known.
     */
    @GetMapping("/{transferId}")
    @Operation(summary = "Status of one of your transfers")
    public TransferResponse getTransfer(@PathVariable UUID transferId) {
        UUID userId = currentUserProvider.requireCurrentUserId();
        return transferService.requireOwnTransfer(transferId, userId);
    }

    @GetMapping
    @Operation(summary = "Your transfer history, newest first")
    public TransferPageResponse history(
            @RequestParam(required = false)
            @Parameter(description = "The nextCursor from the previous page. Omit for the first page.")
            String cursor,

            @RequestParam(defaultValue = "20")
            @Parameter(description = "Rows per page, capped at 100")
            int size) {

        UUID userId = currentUserProvider.requireCurrentUserId();
        return transferService.historyFor(userId, cursor, size);
    }
}
