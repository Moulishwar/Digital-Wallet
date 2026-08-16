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
 */
public record PostingCommand(JournalEntryType type,
                             String externalRef,
                             String description,
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

    /** One side of the movement: a signed amount against one account. */
    public record PostingLeg(UUID accountId, Money amount) {
    }

    public static PostingCommand transfer(String externalRef, UUID fromAccountId, UUID toAccountId,
                                          Money amount, String description) {
        return new PostingCommand(JournalEntryType.TRANSFER, externalRef, description,
                List.of(new PostingLeg(fromAccountId, amount.negated()),
                        new PostingLeg(toAccountId, amount)));
    }

    public static PostingCommand topUp(String externalRef, UUID fundingAccountId, UUID walletAccountId,
                                       Money amount, String description) {
        return new PostingCommand(JournalEntryType.TOPUP, externalRef, description,
                List.of(new PostingLeg(fundingAccountId, amount.negated()),
                        new PostingLeg(walletAccountId, amount)));
    }

    public List<UUID> distinctAccountIds() {
        return legs.stream().map(PostingLeg::accountId).distinct().toList();
    }
}
