package com.digitalwallet.wallet.statement;

import com.digitalwallet.wallet.account.Account;
import com.digitalwallet.wallet.account.WalletService;
import com.digitalwallet.wallet.ledger.LedgerLine;
import com.digitalwallet.wallet.ledger.LedgerLineRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a wallet's ledger history as a keyset-paginated statement.
 */
@Service
public class StatementService {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private final WalletService walletService;
    private final LedgerLineRepository ledgerLineRepository;

    public StatementService(WalletService walletService, LedgerLineRepository ledgerLineRepository) {
        this.walletService = walletService;
        this.ledgerLineRepository = ledgerLineRepository;
    }

    /**
     * @param encodedCursor null for the first page, otherwise the {@code nextCursor} from the
     *                      previous response
     */
    @Transactional(readOnly = true)
    public StatementPage statementFor(UUID ownerUserId, String encodedCursor, int requestedSize) {
        Account wallet = walletService.requireWalletFor(ownerUserId);
        int size = Math.clamp(requestedSize, 1, MAX_PAGE_SIZE);

        // Ask for one more row than requested. If it comes back, there is another page — which
        // avoids a second COUNT query purely to answer "is there more?".
        Pageable limit = PageRequest.ofSize(size + 1);

        List<LedgerLine> rows;
        if (encodedCursor == null || encodedCursor.isBlank()) {
            rows = ledgerLineRepository.findFirstPage(wallet.getId(), limit);
        } else {
            StatementCursor cursor = StatementCursor.decode(encodedCursor);
            rows = ledgerLineRepository.findNextPage(wallet.getId(), cursor.createdAt(), cursor.lineId(), limit);
        }

        boolean hasMore = rows.size() > size;
        List<LedgerLine> pageRows = hasMore ? rows.subList(0, size) : rows;

        List<StatementLine> lines = new ArrayList<>(pageRows.size());
        for (LedgerLine row : pageRows) {
            lines.add(new StatementLine(
                    row.getId(),
                    row.getCreatedAt(),
                    row.getJournalEntry().getType(),
                    row.getJournalEntry().getDescription(),
                    row.getAmountMinor(),
                    row.getBalanceAfterMinor(),
                    row.getCounterpartyHandle(),
                    row.getCounterpartyName(),
                    row.getJournalEntry().getMemo()));
        }

        String nextCursor = hasMore && !lines.isEmpty()
                ? StatementCursor.from(lines.get(lines.size() - 1)).encode()
                : null;

        return new StatementPage(List.copyOf(lines), nextCursor);
    }
}
