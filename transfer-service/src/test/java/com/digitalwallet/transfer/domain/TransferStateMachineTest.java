package com.digitalwallet.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.digitalwallet.common.money.Money;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The rules about what a transfer may do next.
 *
 * <p>Worth testing on its own because the expensive mistakes here are all invisible from outside:
 * a completed transfer that can be completed again is a transfer that can be paid twice, and
 * nothing about the HTTP response would look wrong when it happened.
 */
class TransferStateMachineTest {

    private static Transfer newTransfer() {
        return Transfer.open(UUID.randomUUID(), UUID.randomUUID(), Money.positive(50_000L), "Dinner");
    }

    @Test
    @DisplayName("a new transfer starts pending, with nothing moved and no failure recorded")
    void startsPending() {
        Transfer transfer = newTransfer();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.getFailureReason()).isNull();
        assertThat(transfer.getCompletedAt()).isNull();
        assertThat(transfer.getStatus().isTerminal()).isFalse();
    }

    @Test
    @DisplayName("a transfer cannot be opened between a user and themselves")
    void rejectsSelfTransfer() {
        UUID sameUser = UUID.randomUUID();

        assertThatThrownBy(() -> Transfer.open(sameUser, sameUser, Money.positive(100L), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("completing records the time and leaves no failure reason behind")
    void completing() {
        Transfer transfer = newTransfer();

        transfer.complete();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getCompletedAt()).isNotNull();
        assertThat(transfer.getFailureReason()).isNull();
    }

    @Test
    @DisplayName("failing requires a reason — a failure nobody can explain is a bug, not a state")
    void failingNeedsAReason() {
        Transfer transfer = newTransfer();

        assertThatThrownBy(() -> transfer.fail(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    @DisplayName("a completed transfer cannot be completed again — that is how you pay twice")
    void completedIsFinal() {
        Transfer transfer = newTransfer();
        transfer.complete();

        assertThatThrownBy(transfer::complete).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.fail(FailureReason.INSUFFICIENT_FUNDS))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(transfer::markUnresolved).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a failed transfer cannot later be completed by a stale retry")
    void failedIsFinal() {
        Transfer transfer = newTransfer();
        transfer.fail(FailureReason.INSUFFICIENT_FUNDS);

        assertThatThrownBy(transfer::complete).isInstanceOf(IllegalStateException.class);
        assertThat(transfer.getFailureReason()).isEqualTo(FailureReason.INSUFFICIENT_FUNDS);
    }

    @Test
    @DisplayName("an unresolved transfer can still settle either way — that is the point of the sweep")
    void unresolvedCanSettle() {
        Transfer completed = newTransfer();
        completed.markUnresolved();
        completed.complete();
        assertThat(completed.getStatus()).isEqualTo(TransferStatus.COMPLETED);

        Transfer failed = newTransfer();
        failed.markUnresolved();
        failed.fail(FailureReason.INSUFFICIENT_FUNDS);
        assertThat(failed.getStatus()).isEqualTo(TransferStatus.FAILED);
    }

    @ParameterizedTest
    @EnumSource(TransferStatus.class)
    @DisplayName("no status may transition back into pending")
    void nothingReturnsToPending(TransferStatus from) {
        assertThat(from.canTransitionTo(TransferStatus.PENDING)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = TransferStatus.class, names = {"COMPLETED", "FAILED"})
    @DisplayName("terminal statuses permit no transition at all")
    void terminalStatusesAreClosed(TransferStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (TransferStatus next : TransferStatus.values()) {
            assertThat(terminal.canTransitionTo(next)).isFalse();
        }
    }
}
