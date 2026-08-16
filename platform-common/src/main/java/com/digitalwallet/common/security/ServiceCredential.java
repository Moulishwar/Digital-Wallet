package com.digitalwallet.common.security;

/**
 * The shared secret one service presents to another, and the header it travels in.
 *
 * <p>Deliberately simple. mTLS is the correct production answer and is listed as a future extension
 * in DESIGN.md section 14, rather than being half-implemented here and described as if it were the
 * real thing. What this does buy is that reaching the network is no longer, by itself, enough to
 * move money — which is the gap that matters most.
 */
public final class ServiceCredential {

    /** Not an {@code Authorization} header, so it can never be confused with a user's bearer token. */
    public static final String HEADER = "X-Service-Credential";

    /** The authority granted to a caller that presents a valid credential. */
    public static final String ROLE = "ROLE_SERVICE";

    private ServiceCredential() {
    }
}
