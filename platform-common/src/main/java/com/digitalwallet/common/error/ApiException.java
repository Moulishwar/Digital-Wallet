package com.digitalwallet.common.error;

/**
 * A failure that is meant to reach the client, carrying the {@link ErrorCode} that decides its
 * status and title.
 *
 * <p>The {@code detail} is shown to the caller, so it must never contain anything sensitive:
 * no password hashes, no tokens, and no confirmation of whether some other user exists.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ApiException(ErrorCode code, String detail, Throwable cause) {
        super(detail, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static ApiException accountNotFound(Object id) {
        return new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "No account found for " + id);
    }

    public static ApiException insufficientFunds(long balanceMinor, long requestedMinor) {
        return new ApiException(ErrorCode.INSUFFICIENT_FUNDS,
                "Balance is %d but the posting requires %d".formatted(balanceMinor, requestedMinor));
    }
}
