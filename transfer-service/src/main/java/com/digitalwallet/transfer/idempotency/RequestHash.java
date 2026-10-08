package com.digitalwallet.transfer.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Fingerprints a transfer request, so that reusing an idempotency key with a different body can be
 * told apart from a genuine retry.
 *
 * <p>The fields are canonicalised before hashing rather than the raw JSON being digested. Two
 * requests that mean the same thing must produce the same hash even if a client reorders its JSON
 * fields, changes its whitespace, or sends {@code "@Alice"} where it sent {@code "alice"} before;
 * hashing the raw bytes would call those different requests and reject a legitimate retry.
 *
 * <p>A separator that cannot occur inside any field is used between them. Concatenating values
 * directly would let {@code ("ab", "c")} and {@code ("a", "bc")} collide, which is exactly the kind
 * of thing that never happens until it does.
 */
public final class RequestHash {

    /** A unit separator: not a legal character in a handle, an amount, or a note. */
    private static final String FIELD_SEPARATOR = "";

    private RequestHash() {
    }

    public static String of(String recipientHandle, long amountMinor, String note) {
        String canonical = String.join(FIELD_SEPARATOR,
                canonicalHandle(recipientHandle),
                Long.toString(amountMinor),
                note == null ? "" : note.strip());

        return sha256Hex(canonical);
    }

    /**
     * Handles are case-insensitive and may be written with a leading {@code @}. Both spellings name
     * the same person, so both must hash the same. TransferService resolves the recipient with this
     * same canonical form, which is what keeps "the request" and "who gets paid" in agreement.
     */
    public static String canonicalHandle(String handle) {
        if (handle == null) {
            return "";
        }
        String trimmed = handle.strip();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM. If it is missing, the platform is broken.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
