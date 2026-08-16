package com.digitalwallet.auth.user;

public enum UserStatus {

    ACTIVE,

    /** Blocked from logging in. Existing access tokens still run out on their own short TTL. */
    SUSPENDED;

    public boolean canAuthenticate() {
        return this == ACTIVE;
    }
}
