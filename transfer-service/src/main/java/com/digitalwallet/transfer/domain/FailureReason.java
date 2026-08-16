package com.digitalwallet.transfer.domain;

import com.digitalwallet.common.error.ErrorCode;
import java.util.Optional;

/**
 * Why a transfer failed, in a form a client can branch on.
 *
 * <p>These are deliberately a closed set rather than whatever text wallet-service happened to
 * return. A caller should be able to tell "you do not have enough money" from "that wallet is
 * frozen" without parsing prose, and this service should not leak another service's wording into
 * its own contract.
 */
public enum FailureReason {

    INSUFFICIENT_FUNDS(ErrorCode.INSUFFICIENT_FUNDS, "The sender's wallet does not hold enough funds"),
    ACCOUNT_NOT_ACTIVE(ErrorCode.ACCOUNT_NOT_ACTIVE, "One of the wallets is frozen or closed"),
    RECIPIENT_NOT_FOUND(ErrorCode.ACCOUNT_NOT_FOUND, "No such recipient"),
    REJECTED(ErrorCode.VALIDATION_FAILED, "The posting was rejected as invalid");

    private final ErrorCode errorCode;
    private final String detail;

    FailureReason(ErrorCode errorCode, String detail) {
        this.errorCode = errorCode;
        this.detail = detail;
    }

    /** The error this failure is reported to the client as, so the status code matches the cause. */
    public ErrorCode errorCode() {
        return errorCode;
    }

    public String detail() {
        return detail;
    }

    /**
     * Maps a Problem Detail {@code type} URI from wallet-service onto our own vocabulary.
     *
     * <p>Returns empty for anything unrecognised, and that emptiness is load-bearing. Failing a
     * transfer is <em>terminal</em>: it can never be retried or settled afterwards. So it may only
     * be done on an error we actually understand to mean "this will never succeed as sent".
     *
     * <p>A 404 from a mistyped route, a 401 from an expired service credential, a 405 from a proxy
     * — none of those are refusals of the payment, but all of them are 4xx. Reading them as
     * rejections would permanently kill transfers over an infrastructure mistake, and the sender
     * would be told their payment failed when nothing about their payment was wrong. Anything not
     * on this list is treated as an unknown outcome instead, and left for reconciliation to
     * establish.
     */
    public static Optional<FailureReason> fromProblemType(String typeUri) {
        if (typeUri == null) {
            return Optional.empty();
        }
        if (typeUri.endsWith("/insufficient-funds")) {
            return Optional.of(INSUFFICIENT_FUNDS);
        }
        if (typeUri.endsWith("/account-not-active")) {
            return Optional.of(ACCOUNT_NOT_ACTIVE);
        }
        if (typeUri.endsWith("/account-not-found")) {
            return Optional.of(RECIPIENT_NOT_FOUND);
        }
        if (typeUri.endsWith("/validation-failed")) {
            // Malformed as sent. Retrying the identical request cannot help.
            return Optional.of(REJECTED);
        }
        return Optional.empty();
    }
}
