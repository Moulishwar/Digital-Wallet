package com.digitalwallet.transfer.security;

import java.util.UUID;

/**
 * Who is calling, and the token they called with.
 *
 * <p>An interface rather than direct use of {@code SecurityContextHolder} so that controllers and
 * services depend on the question, not on how the answer happens to arrive.
 */
public interface CurrentUserProvider {

    /**
     * The authenticated caller's user id, taken from the verified token's subject.
     *
     * <p>Never from a request parameter. If the API accepted a user id, every endpoint would have
     * to decide whether the caller may use it, and forgetting once is how insecure direct object
     * reference bugs happen.
     */
    UUID requireCurrentUserId();

    /**
     * The caller's raw bearer token, for forwarding to another service on their behalf.
     *
     * <p>Forwarding the user's own token, rather than calling downstream with some service-wide
     * credential, means the downstream request carries exactly the caller's authority and no more.
     */
    String requireCurrentToken();
}
