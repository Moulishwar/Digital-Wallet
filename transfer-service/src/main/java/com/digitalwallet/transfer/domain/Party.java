package com.digitalwallet.transfer.domain;

import java.util.UUID;

/**
 * One side of a transfer: who they are, and how to name them to the other side.
 *
 * <p>The id is the identity: it is what ownership checks and the ledger posting use. The handle and
 * display name are labels for people to read, captured from auth-service when the transfer is made.
 *
 * @param displayName may be null; auth-service always has one, but nothing here depends on it
 */
public record Party(UUID userId, String handle, String displayName) {

    public Party {
        if (userId == null) {
            throw new IllegalArgumentException("A party needs a user id");
        }
    }
}
