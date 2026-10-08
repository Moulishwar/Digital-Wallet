package com.digitalwallet.wallet.api.dto;

import com.digitalwallet.wallet.account.Account;
import com.digitalwallet.wallet.ledger.JournalEntryType;
import com.digitalwallet.wallet.ledger.PostingResult;
import com.digitalwallet.wallet.statement.EntryView;
import com.digitalwallet.wallet.statement.StatementLine;
import com.digitalwallet.wallet.statement.StatementPage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response shapes for the wallet API.
 *
 * <p>These are separate types from the JPA entities on purpose. Serializing an entity directly
 * couples the wire format to the database schema and makes it far too easy to leak a column that
 * was never meant to leave the server. Keeping them apart means a field is exposed only when
 * someone writes it here.
 *
 * <p>Every amount crosses the wire in minor units as an integer. A JSON number like {@code 12.10}
 * is parsed as a binary double by most clients, which cannot represent it exactly; sending
 * {@code 1210} avoids the whole class of problem. A formatted string is included alongside purely
 * for display.
 */
public final class WalletDtos {

    private WalletDtos() {
    }

    // ---------------------------------------------------------------- requests

    @Schema(description = "Adds money to the caller's wallet from the simulated funding source.")
    public record TopUpRequest(
            @NotNull
            @Positive(message = "amountMinor must be greater than zero")
            @Max(value = 10_000_000L, message = "amountMinor exceeds the per-top-up ceiling of 100000.00")
            @Schema(description = "Amount in paise. 10000 means 100.00.", example = "10000")
            Long amountMinor) {
    }

    @Schema(description = "Provisions a wallet for a user. Called by auth-service at registration.")
    public record CreateAccountRequest(
            @NotNull
            @Schema(description = "The user who will own the wallet")
            UUID ownerUserId) {
    }

    /**
     * A movement between two users' wallets.
     *
     * <p>Deliberately expressed in <em>owner user ids</em>, not account ids. Wallet account
     * identifiers are this service's private business: a caller that never learns them cannot
     * accidentally post against the funding account, and cannot drift out of step when an account
     * is replaced. Resolution happens here, against the same idempotent provisioning path
     * registration uses, so a recipient whose wallet was never created can still be paid.
     */
    @Schema(description = "Posts a balanced two-sided movement between two users' wallets.")
    public record PostingRequest(
            @NotBlank
            @Size(max = 128)
            @Schema(description = "Caller's unique reference. Replaying it returns the original posting "
                    + "rather than moving money again.")
            String externalRef,

            @NotNull
            @Schema(description = "The user whose wallet is debited")
            UUID fromOwnerUserId,

            @NotNull
            @Schema(description = "The user whose wallet is credited")
            UUID toOwnerUserId,

            @NotNull
            @Positive
            @Schema(description = "Amount in paise, always positive. The debit side is derived.")
            Long amountMinor,

            @Size(max = 255) String description,

            // Statement labels. All optional: they make each party's statement readable, but the
            // money moves the same with or without them, so their absence is never a reason to
            // refuse a posting.

            @Size(max = 140)
            @Schema(description = "The sender's note, shown on both parties' statements")
            String memo,

            @Size(max = 32)
            @Schema(description = "Sender's handle, shown on the recipient's statement")
            String fromHandle,

            @Size(max = 120)
            @Schema(description = "Sender's display name, shown on the recipient's statement")
            String fromName,

            @Size(max = 32)
            @Schema(description = "Recipient's handle, shown on the sender's statement")
            String toHandle,

            @Size(max = 120)
            @Schema(description = "Recipient's display name, shown on the sender's statement")
            String toName) {
    }

    // --------------------------------------------------------------- responses

    public record WalletResponse(UUID walletId,
                                 String currency,
                                 long balanceMinor,
                                 @Schema(description = "Formatted balance, for display only", example = "1234.50")
                                 BigDecimal balance,
                                 String status) {

        public static WalletResponse from(Account account) {
            return new WalletResponse(account.getId(), account.getCurrency(),
                    account.getBalanceMinor(), account.balance().toMajor(), account.getStatus().name());
        }
    }

    public record TopUpResponse(UUID journalEntryId,
                                long balanceMinor,
                                BigDecimal balance,
                                @Schema(description = "True when this idempotency key had already been used "
                                        + "and no new money moved")
                                boolean replayed) {
    }

    /**
     * <p>The two balance fields are reported per side of the request rather than as a map keyed by
     * account, so the caller still never needs to know an account id. They are null on the
     * reconciliation lookup, which is given only a reference and so has no notion of "from" and
     * "to"; that caller is asking whether the posting exists, not what it produced.
     */
    public record PostingResponse(UUID journalEntryId,
                                  String externalRef,
                                  JournalEntryType type,
                                  Instant postedAt,
                                  boolean replayed,
                                  @Schema(description = "Debited wallet's balance after this posting, in minor units")
                                  Long fromBalanceAfterMinor,
                                  @Schema(description = "Credited wallet's balance after this posting, in minor units")
                                  Long toBalanceAfterMinor) {

        public static PostingResponse of(PostingResult result, UUID fromAccountId, UUID toAccountId) {
            return new PostingResponse(result.journalEntryId(), result.externalRef(), result.type(),
                    result.postedAt(), result.replayed(),
                    result.balancesAfter().get(fromAccountId),
                    result.balancesAfter().get(toAccountId));
        }

        /** For the reconciliation lookup, which knows only the reference. */
        public static PostingResponse existing(PostingResult result) {
            return new PostingResponse(result.journalEntryId(), result.externalRef(), result.type(),
                    result.postedAt(), result.replayed(), null, null);
        }
    }

    public record StatementLineResponse(UUID lineId,
                                        @Schema(description = "Open with GET /api/wallets/me/entries/{journalEntryId} "
                                                + "to see both sides of this movement")
                                        UUID journalEntryId,
                                        Instant occurredAt,
                                        JournalEntryType type,
                                        String description,
                                        @Schema(description = "Signed: negative left the wallet, positive arrived")
                                        long amountMinor,
                                        long balanceAfterMinor,
                                        @Schema(description = "Who the money came from (credit) or went to "
                                                + "(debit). Null for a top-up.", example = "alice")
                                        String counterpartyHandle,
                                        @Schema(description = "The counterparty's display name when the money "
                                                + "moved", example = "Alice Rao")
                                        String counterpartyName,
                                        @Schema(description = "The sender's note, shown to both parties",
                                                example = "Dinner")
                                        String memo) {

        public static StatementLineResponse from(StatementLine line) {
            return new StatementLineResponse(line.lineId(), line.journalEntryId(), line.occurredAt(), line.type(),
                    line.description(), line.amountMinor(), line.balanceAfterMinor(),
                    line.counterpartyHandle(), line.counterpartyName(), line.memo());
        }
    }

    public record StatementResponse(List<StatementLineResponse> lines,
                                    @Schema(description = "Pass back as ?cursor= for the next page. "
                                            + "Null means this was the last page.")
                                    String nextCursor) {

        public static StatementResponse from(StatementPage page) {
            return new StatementResponse(page.lines().stream().map(StatementLineResponse::from).toList(),
                    page.nextCursor());
        }
    }

    @Schema(description = "One journal entry from the caller's side: every line, but only the caller's balance.")
    public record EntryResponse(UUID journalEntryId,
                                JournalEntryType type,
                                Instant postedAt,
                                String description,
                                String memo,
                                @Schema(description = "The caller's own line first")
                                List<EntryLineResponse> lines,
                                @Schema(description = "Signed total of the lines. Always 0: money moved, none created.")
                                long sumMinor) {

        public static EntryResponse from(EntryView entry) {
            return new EntryResponse(entry.journalEntryId(), entry.type(), entry.postedAt(),
                    entry.description(), entry.memo(),
                    entry.lines().stream().map(EntryLineResponse::from).toList(),
                    entry.sumMinor());
        }
    }

    public record EntryLineResponse(@Schema(description = "Whose line: YOU, COUNTERPARTY, FUNDING or FEES")
                                    EntryView.Party party,
                                    @Schema(description = "Set on a COUNTERPARTY line", example = "bob")
                                    String counterpartyHandle,
                                    String counterpartyName,
                                    @Schema(description = "Signed: negative left the account, positive arrived")
                                    long amountMinor,
                                    @Schema(description = "Only on your own line. Nobody else's balance is ever shown.")
                                    Long balanceAfterMinor) {

        public static EntryLineResponse from(EntryView.Line line) {
            return new EntryLineResponse(line.party(), line.counterpartyHandle(), line.counterpartyName(),
                    line.amountMinor(), line.balanceAfterMinor());
        }
    }

    @Schema(description = "Proof that every cached balance still equals the sum of its ledger lines.")
    public record ReconciliationResponse(boolean consistent,
                                         @Schema(description = "Signed total of every ledger line. Must be zero — "
                                                 + "money is only moved, never created or destroyed.")
                                         long ledgerSumMinor,
                                         List<UUID> accountsWithDriftedBalance) {
    }
}
