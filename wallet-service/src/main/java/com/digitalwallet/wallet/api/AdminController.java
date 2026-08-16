package com.digitalwallet.wallet.api;

import com.digitalwallet.wallet.api.dto.WalletDtos.ReconciliationResponse;
import com.digitalwallet.wallet.ledger.LedgerAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operational checks.
 *
 * <p>Restricted to {@code ROLE_ADMIN} in M2, once there are roles to restrict it to.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "Operational checks on ledger integrity")
public class AdminController {

    private final LedgerAuditService auditService;

    public AdminController(LedgerAuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Proves the ledger is internally consistent, right now, against live data.
     *
     * <p>Two independent claims are checked. That every cached balance still equals the sum of its
     * own ledger lines, and that the signed total of every line in the system is zero. The first
     * says the cache has not drifted; the second says no posting has ever created or destroyed
     * value.
     */
    @GetMapping("/reconciliation")
    @Operation(summary = "Verify cached balances against the ledger and check the zero-sum invariant")
    public ReconciliationResponse reconcile() {
        List<UUID> drifted = auditService.accountsWithDriftedBalance();
        long ledgerSum = auditService.totalOfAllLines();
        boolean consistent = drifted.isEmpty() && ledgerSum == 0L;
        return new ReconciliationResponse(consistent, ledgerSum, drifted);
    }
}
