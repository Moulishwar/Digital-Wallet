package com.digitalwallet.wallet.security;

import java.util.UUID;

/**
 * Supplies the id of the user making the current request.
 *
 * <p>An interface so that controllers depend on the question, not on how identity happens to
 * arrive. Controllers never read identity from a request
 * parameter or body, only from here, which is what keeps a caller from acting as someone else
 * simply by sending a different id.
 */
public interface CurrentUserProvider {

    /**
     * @throws com.digitalwallet.common.error.ApiException with
     *         {@link com.digitalwallet.common.error.ErrorCode#UNAUTHORIZED} if the caller has no
     *         usable identity
     */
    UUID requireCurrentUserId();
}
