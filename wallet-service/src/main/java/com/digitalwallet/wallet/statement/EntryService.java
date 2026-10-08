package com.digitalwallet.wallet.statement;

import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
import com.digitalwallet.wallet.account.Account;
import com.digitalwallet.wallet.account.WalletService;
import com.digitalwallet.wallet.ledger.JournalEntry;
import com.digitalwallet.wallet.ledger.JournalEntryRepository;
import com.digitalwallet.wallet.ledger.LedgerLine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One journal entry as seen by one of the people in it: every line, so the double entry is
 * visible, but only the caller's own balance.
 *
 * <p>The other side of a transfer is another person's wallet. Showing that it moved +50.00 is fine
 * (the caller sent it), but its balance afterwards is that person's private business, so it is
 * never included.
 */
@Service
public class EntryService {

    private final WalletService walletService;
    private final JournalEntryRepository journalEntryRepository;

    public EntryService(WalletService walletService, JournalEntryRepository journalEntryRepository) {
        this.walletService = walletService;
        this.journalEntryRepository = journalEntryRepository;
    }

    /**
     * @throws ApiException 404 when the entry does not exist or the caller has no line in it. The
     *                      two are deliberately indistinguishable, so entry ids cannot be probed.
     */
    @Transactional(readOnly = true)
    public EntryView entryFor(UUID ownerUserId, UUID journalEntryId) {
        Account wallet = walletService.requireWalletFor(ownerUserId);

        JournalEntry entry = journalEntryRepository.findById(journalEntryId)
                .orElseThrow(EntryService::noSuchEntry);

        LedgerLine own = entry.getLines().stream()
                .filter(line -> line.getAccount().getId().equals(wallet.getId()))
                .findFirst()
                .orElseThrow(EntryService::noSuchEntry);

        List<EntryView.Line> lines = new ArrayList<>(entry.getLines().size());
        for (LedgerLine line : entry.getLines()) {
            lines.add(line == own ? ownLine(line) : otherLine(line, own));
        }
        // The caller's own line first; the order of the rest carries no meaning.
        lines.sort(Comparator.comparing(line -> line.party() != EntryView.Party.YOU));

        long sum = entry.getLines().stream().mapToLong(LedgerLine::getAmountMinor).sum();

        return new EntryView(entry.getId(), entry.getType(), entry.getPostedAt(),
                entry.getDescription(), entry.getMemo(), List.copyOf(lines), sum);
    }

    private static EntryView.Line ownLine(LedgerLine line) {
        return new EntryView.Line(EntryView.Party.YOU, null, null,
                line.getAmountMinor(), line.getBalanceAfterMinor());
    }

    /**
     * Another account's line. For a person, the name comes from the caller's own line, which
     * recorded who the other side was; the other line itself names the caller.
     */
    private static EntryView.Line otherLine(LedgerLine line, LedgerLine own) {
        EntryView.Party party = switch (line.getAccount().getType()) {
            case USER_WALLET -> EntryView.Party.COUNTERPARTY;
            case SYSTEM_FUNDING -> EntryView.Party.FUNDING;
            case SYSTEM_FEES -> EntryView.Party.FEES;
        };
        boolean person = party == EntryView.Party.COUNTERPARTY;
        return new EntryView.Line(party,
                person ? own.getCounterpartyHandle() : null,
                person ? own.getCounterpartyName() : null,
                line.getAmountMinor(),
                null);
    }

    private static ApiException noSuchEntry() {
        return new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "No such entry");
    }
}
