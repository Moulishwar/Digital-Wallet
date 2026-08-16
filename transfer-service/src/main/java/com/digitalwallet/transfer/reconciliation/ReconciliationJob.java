package com.digitalwallet.transfer.reconciliation;

import com.digitalwallet.transfer.client.PostingOutcome;
import com.digitalwallet.transfer.client.WalletPostingClient;
import com.digitalwallet.transfer.config.TransferProperties;
import com.digitalwallet.transfer.domain.Transfer;
import com.digitalwallet.transfer.domain.TransferRepository;
import com.digitalwallet.transfer.domain.TransferService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles transfers whose outcome was never established.
 *
 * <p>This is the answer to the one genuinely distributed problem in the design: transfer-service
 * called wallet-service, and the network died before the reply arrived. Did the money move?
 *
 * <p>It is answerable because {@code journal_entry.external_ref} is unique over in the ledger and
 * carries the transfer id. So the sweep can simply ask, and the answer is unambiguous:
 *
 * <ul>
 *   <li><b>The posting exists</b> — it committed. Mark the transfer completed.</li>
 *   <li><b>It does not</b> — it never committed. Retry it. The unique constraint makes a duplicate
 *       impossible even if the original request turns out to have been in flight all along, which
 *       is the entire reason that constraint is there rather than being a convenience column.</li>
 * </ul>
 *
 * <p>Nothing here guesses. If wallet-service cannot be reached, the transfer is left exactly as it
 * was and the next run tries again — a transfer that stays unresolved is a visible problem, while
 * one wrongly marked failed is an invisible one.
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final TransferRepository transferRepository;
    private final TransferService transferService;
    private final WalletPostingClient walletClient;
    private final TransferProperties properties;

    public ReconciliationJob(TransferRepository transferRepository,
                             TransferService transferService,
                             WalletPostingClient walletClient,
                             TransferProperties properties) {
        this.transferRepository = transferRepository;
        this.transferService = transferService;
        this.walletClient = walletClient;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${transfers.reconciliation.interval}")
    public void sweep() {
        List<Transfer> unresolved = findDueForReconciliation();
        if (unresolved.isEmpty()) {
            return;
        }

        log.info("Reconciling {} transfer(s) of unknown outcome", unresolved.size());
        for (Transfer transfer : unresolved) {
            try {
                reconcile(transfer);
            } catch (RuntimeException e) {
                // One stubborn transfer must not stop the rest of the batch being settled.
                log.warn("Could not reconcile transfer {} this run: {}", transfer.getId(), e.toString());
            }
        }
    }

    @Transactional(readOnly = true)
    public List<Transfer> findDueForReconciliation() {
        Instant olderThan = Instant.now().minus(properties.reconciliation().minAge());
        return transferRepository.findUnresolved(
                olderThan, PageRequest.ofSize(properties.reconciliation().batchSize()));
    }

    /**
     * Establishes what actually happened to one transfer, and settles it.
     *
     * <p>Visible for testing: the sweep's whole value is in the two branches below, and driving them
     * directly is far clearer than waiting for a scheduler to fire.
     */
    public void reconcile(Transfer transfer) {
        Optional<UUID> existingPosting = walletClient.findPosting(transfer.getId());

        if (existingPosting.isPresent()) {
            log.info("Transfer {} did commit after all (journal entry {}) — completing",
                    transfer.getId(), existingPosting.get());
            transfer.complete();
            transferService.save(transfer);
            return;
        }

        // wallet-service is certain there is no such posting, so the original attempt never
        // committed and retrying cannot duplicate anything.
        log.info("Transfer {} never committed — retrying the posting", transfer.getId());
        PostingOutcome outcome = walletClient.post(
                transfer.getId(),
                transfer.getSenderUserId(),
                transfer.getRecipientUserId(),
                transfer.getAmountMinor(),
                "Transfer " + transfer.getId());

        switch (outcome) {
            case PostingOutcome.Posted posted -> {
                transfer.complete();
                transferService.save(transfer);
                log.info("Transfer {} completed on retry ({})", transfer.getId(), posted.journalEntryId());
            }
            case PostingOutcome.Rejected rejected -> {
                transfer.fail(rejected.reason());
                transferService.save(transfer);
                log.info("Transfer {} failed on retry: {}", transfer.getId(), rejected.reason());
            }
            // Still unknown. Left as it is for a later run rather than guessed at.
            case PostingOutcome.Unknown unknown ->
                    log.warn("Transfer {} is still unresolved: {}", transfer.getId(), unknown.cause());
        }
    }
}
