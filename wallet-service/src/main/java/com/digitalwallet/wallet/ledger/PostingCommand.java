package com.digitalwallet.wallet.ledger;

import com.digitalwallet.common.money.Money;
import java.util.List;
import java.util.UUID;

/**
 * A request to post one movement of value.
 *
 * <p>Expressed as a list of legs rather than a from/to pair so the same code path serves a
 * two-line transfer, a top-up against the funding account, and a future fee split across three
 * accounts. The balancing rule is the same in every case: the legs must sum to zero.
 *
 * @param externalRef the caller's identifier for this posting; unique, and what makes a retry safe
 * @param memo        the sender's note, shown on both parties' statements; may be null
 */
public record PostingCommand(JournalEntryType type,
                             String externalRef,
                             String description,
                             String memo,
                             List<PostingLeg> legs) {

    public PostingCommand {
        if (externalRef == null || externalRef.isBlank()) {
            throw new IllegalArgumentException("externalRef is required — it is the idempotency key");
        }
        if (legs == null || legs.size() < 2) {
            throw new IllegalArgumentException("A posting needs at least two legs");
        }
        legs = List.copyOf(legs);
    }

    /**
     * One side of the movement: a signed amount against one account.
     *
     * @param counterparty who this side's statement should name as the other party; null when the
     *                     other side is not a person, as with a top-up
     */
    public record PostingLeg(UUID accountId, Money amount, Counterparty counterparty) {

        public PostingLeg(UUID accountId, Money amount) {
            this(accountId, amount, null);
        }
    }

    /** A transfer with no statement labels: the shape used where only the arithmetic matters. */
    public static PostingCommand transfer(String externalRef, UUID fromAccountId, UUID toAccountId,
                                          Money amount, String description) {
        return transfer(externalRef, fromAccountId, toAccountId, amount, description, null, null, null);
    }

    /**
     * A transfer between two wallets.
     *
     * <p>Each side is labelled with the <em>other</em> party: the sender's line names the recipient
     * and the recipient's line names the sender, which is what each of them needs to read.
     */
    public static PostingCommand transfer(String externalRef, UUID fromAccountId, UUID toAccountId,
                                          Money amount, String description, String memo,
                                          Counterparty sender, Counterparty recipient) {
        return new PostingCommand(JournalEntryType.TRANSFER, externalRef, description, memo,
                List.of(new PostingLeg(fromAccountId, amount.negated(), recipient),
                        new PostingLeg(toAccountId, amount, sender)));
    }

    public static PostingCommand topUp(String externalRef, UUID fundingAccountId, UUID walletAccountId,
                                       Money amount, String description) {
        return new PostingCommand(JournalEntryType.TOPUP, externalRef, description, null,
                List.of(new PostingLeg(fundingAccountId, amount.negated()),
                        new PostingLeg(walletAccountId, amount)));
    }

    public List<UUID> distinctAccountIds() {
        return legs.stream().map(PostingLeg::accountId).distinct().toList();
    }
}
