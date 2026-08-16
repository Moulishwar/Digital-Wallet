package com.digitalwallet.wallet.security;

import java.util.UUID;

/**
 * Supplies the id of the user making the current request.
 *
 * <p>Exists as an interface so that the M1 placeholder can be swapped for real JWT extraction in
 * M2 without touching a single controller. Controllers never read identity from a request
 * parameter or body — only from here — which is what keeps a caller from acting as someone else
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
