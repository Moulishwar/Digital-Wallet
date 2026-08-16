package com.digitalwallet.transfer.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fingerprint that decides whether a repeated idempotency key is a genuine retry or a client
 * bug.
 *
 * <p>Both directions matter and both are easy to get wrong. Too strict, and a legitimate retry that
 * spelled a handle differently is rejected as a conflict. Too loose, and two genuinely different
 * transfers share a fingerprint, so the second silently receives the first one's receipt and never
 * happens.
 */
class RequestHashTest {

    @Test
    @DisplayName("the same request hashes the same way every time")
    void isStable() {
        assertThat(RequestHash.of("alice", 50_000L, "Dinner"))
                .isEqualTo(RequestHash.of("alice", 50_000L, "Dinner"));
    }

    @Test
    @DisplayName("handles differing only by case or a leading @ name the same person, so they match")
    void handlesAreCanonicalised() {
        String expected = RequestHash.of("alice", 50_000L, null);

        assertThat(RequestHash.of("Alice", 50_000L, null)).isEqualTo(expected);
        assertThat(RequestHash.of("@alice", 50_000L, null)).isEqualTo(expected);
        assertThat(RequestHash.of("  @ALICE  ", 50_000L, null)).isEqualTo(expected);
    }

    @Test
    @DisplayName("an absent note and an empty note are the same request")
    void absentNoteMatchesEmptyNote() {
        assertThat(RequestHash.of("alice", 100L, null))
                .isEqualTo(RequestHash.of("alice", 100L, "  "));
    }

    @Test
    @DisplayName("a different amount is a different request")
    void amountChangesTheHash() {
        assertThat(RequestHash.of("alice", 50_000L, "Dinner"))
                .isNotEqualTo(RequestHash.of("alice", 50_001L, "Dinner"));
    }

    @Test
    @DisplayName("a different recipient is a different request")
    void recipientChangesTheHash() {
        assertThat(RequestHash.of("alice", 50_000L, "Dinner"))
                .isNotEqualTo(RequestHash.of("bob", 50_000L, "Dinner"));
    }

    @Test
    @DisplayName("a different note is a different request")
    void noteChangesTheHash() {
        assertThat(RequestHash.of("alice", 50_000L, "Dinner"))
                .isNotEqualTo(RequestHash.of("alice", 50_000L, "Rent"));
    }

    @Test
    @DisplayName("fields cannot bleed into each other and collide")
    void fieldsAreSeparated() {
        // Without a separator between fields, ("ab", 1) and ("a", ...) style shifts can produce the
        // same concatenation. Two requests that differ only in where the boundary falls must differ.
        assertThat(RequestHash.of("ab", 1L, "c"))
                .isNotEqualTo(RequestHash.of("a", 1L, "bc"));
        assertThat(RequestHash.of("alice", 1L, "23"))
                .isNotEqualTo(RequestHash.of("alice", 123L, ""));
    }

    @Test
    @DisplayName("the hash is a full-length SHA-256, which is what the column is sized for")
    void isSha256Hex() {
        assertThat(RequestHash.of("alice", 1L, null))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }
}
