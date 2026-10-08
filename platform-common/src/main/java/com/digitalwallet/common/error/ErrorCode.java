package com.digitalwallet.common.error;

import org.springframework.http.HttpStatus;

/**
 * Every failure the API can return on purpose, with the status and human-readable title it maps to.
 *
 * <p>Keeping these in one enum means the wire format cannot drift per controller, and the set of
 * things that can go wrong is readable in one place.
 */
public enum ErrorCode {

    /** The caller has no wallet, or named an account that does not exist. */
    ACCOUNT_NOT_FOUND("account-not-found", HttpStatus.NOT_FOUND, "Account not found"),

    /** A wallet already exists for this user; provisioning is idempotent, so this is a conflict. */
    ACCOUNT_ALREADY_EXISTS("account-already-exists", HttpStatus.CONFLICT, "Account already exists"),

    /** The account is frozen or closed and cannot take part in a posting. */
    ACCOUNT_NOT_ACTIVE("account-not-active", HttpStatus.CONFLICT, "Account is not active"),

    /**
     * The debit would take the balance below zero. 422 rather than 400: the request was
     * well-formed, it just cannot be satisfied given current state.
     */
    INSUFFICIENT_FUNDS("insufficient-funds", HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds"),

    /**
     * A journal entry whose lines do not sum to zero. This is a bug, never a user error: it means
     * money would have been created or destroyed, so it is deliberately a 500.
     */
    UNBALANCED_ENTRY("unbalanced-entry", HttpStatus.INTERNAL_SERVER_ERROR, "Unbalanced journal entry"),

    /** Same idempotency key replayed with a different request body. */
    IDEMPOTENCY_CONFLICT("idempotency-conflict", HttpStatus.CONFLICT, "Idempotency key reused with a different request"),

    /**
     * An earlier request holding this key has not finished yet. Distinct from
     * {@link #IDEMPOTENCY_CONFLICT}: the caller did nothing wrong and retrying shortly will return
     * the original outcome, whereas a conflict will never succeed as sent.
     */
    IDEMPOTENCY_IN_PROGRESS("idempotency-in-progress", HttpStatus.CONFLICT, "A request with this idempotency key is still in progress"),

    /** Request body or parameters failed validation. */
    VALIDATION_FAILED("validation-failed", HttpStatus.BAD_REQUEST, "Request validation failed"),

    /** Caller is not permitted to see or touch this resource. */
    FORBIDDEN("forbidden", HttpStatus.FORBIDDEN, "Forbidden"),

    /** Caller did not present a usable identity. */
    UNAUTHORIZED("unauthorized", HttpStatus.UNAUTHORIZED, "Unauthorized"),

    /** Anything unhandled. The detail is never echoed to the client. */
    INTERNAL_ERROR("internal-error", HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private static final String TYPE_PREFIX = "https://digitalwallet.example/errors/";

    private final String slug;
    private final HttpStatus status;
    private final String title;

    ErrorCode(String slug, HttpStatus status, String title) {
        this.slug = slug;
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** The stable {@code type} URI reported in the Problem Detail body. */
    public String typeUri() {
        return TYPE_PREFIX + slug;
    }
}
