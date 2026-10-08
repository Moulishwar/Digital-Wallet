package com.digitalwallet.transfer.api.dto;

import com.digitalwallet.common.money.Money;
import com.digitalwallet.transfer.domain.FailureReason;
import com.digitalwallet.transfer.domain.Transfer;
import com.digitalwallet.transfer.domain.TransferStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response shapes for the transfer API.
 *
 * <p>Separate types from the JPA entities, for the same reason as everywhere else in this project:
 * serializing an entity couples the wire format to the schema and makes it easy to leak a column
 * that was never meant to leave the server.
 *
 * <p>Amounts cross the wire in minor units as integers. A JSON number like {@code 12.10} is parsed
 * as a binary double by most clients and cannot be represented exactly; {@code 1210} avoids the
 * whole class of problem. The formatted string alongside it is for display only.
 */
public final class TransferDtos {

    private TransferDtos() {
    }

    // ---------------------------------------------------------------- requests

    @Schema(description = "Sends money from the caller's wallet to another user, found by handle.")
    public record SendTransferRequest(
            @NotBlank
            @Size(max = 33)
            @Schema(description = "The recipient's public handle. A leading @ is accepted.",
                    example = "alice")
            String recipientHandle,

            @NotNull
            @Positive(message = "amountMinor must be greater than zero")
            @Schema(description = "Amount in paise. 50000 means 500.00.", example = "50000")
            Long amountMinor,

            // Short enough to sit on one ruled line of a statement, on a phone as on a laptop.
            // The column holds 140, so notes written before this limit still read back whole.
            @Size(max = 30, message = "note must be at most 30 characters")
            @Schema(description = "Optional note, shown to both sender and recipient. At most 30 characters.",
                    example = "Dinner")
            String note) {
    }

    // --------------------------------------------------------------- responses

    /**
     * @param senderBalanceAfterMinor the sender's balance once the money moved. Null unless the
     *                                transfer completed; there is no new balance to report for one
     *                                that failed or has not settled.
     */
    public record TransferResponse(UUID transferId,
                                   TransferStatus status,
                                   UUID recipientUserId,
                                   @Schema(description = "Recipient's handle when the transfer was made. Null "
                                           + "on transfers created before handles were recorded.",
                                           example = "alice")
                                   String recipientHandle,
                                   @Schema(description = "Recipient's display name when the transfer was made",
                                           example = "Alice Rao")
                                   String recipientName,
                                   long amountMinor,
                                   @Schema(description = "Formatted amount, for display only", example = "500.00")
                                   BigDecimal amount,
                                   String note,
                                   @Schema(description = "Set only when the transfer failed")
                                   FailureReason failureReason,
                                   Long senderBalanceAfterMinor,
                                   Instant createdAt,
                                   Instant completedAt) {

        public static TransferResponse from(Transfer transfer, Long senderBalanceAfterMinor) {
            return new TransferResponse(
                    transfer.getId(),
                    transfer.getStatus(),
                    transfer.getRecipientUserId(),
                    transfer.getRecipientHandle(),
                    transfer.getRecipientName(),
                    transfer.getAmountMinor(),
                    Money.ofMinor(transfer.getAmountMinor()).toMajor(),
                    transfer.getNote(),
                    transfer.getFailureReason(),
                    senderBalanceAfterMinor,
                    transfer.getCreatedAt(),
                    transfer.getCompletedAt());
        }

        public static TransferResponse from(Transfer transfer) {
            return from(transfer, null);
        }
    }

    public record TransferPageResponse(List<TransferResponse> transfers,
                                       @Schema(description = "Pass back as ?cursor= for the next page. "
                                               + "Null means this was the last page.")
                                       String nextCursor) {
    }
}
