package com.digitalwallet.transfer.domain;

import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Points at the last transfer a client has already seen, so the next page starts strictly after it.
 *
 * <p>Encoded rather than exposed as two raw query parameters, so clients treat it as a token to hand
 * back rather than something to construct. That leaves the ordering key free to change later without
 * breaking them.
 *
 * <p>Encoding, not encryption. It hides nothing sensitive: a caller can only page through their own
 * transfers, and ownership is checked against the token on every request regardless of what the
 * cursor says.
 */
public record TransferCursor(Instant createdAt, UUID transferId) {

    private static final String SEPARATOR = "|";

    public String encode() {
        String raw = createdAt.getEpochSecond() + ":" + createdAt.getNano() + SEPARATOR + transferId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static TransferCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\" + SEPARATOR);
            String[] timestamp = parts[0].split(":");
            return new TransferCursor(
                    Instant.ofEpochSecond(Long.parseLong(timestamp[0]), Long.parseLong(timestamp[1])),
                    UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            // A malformed cursor is bad input, not a server fault.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Malformed transfer cursor", e);
        }
    }

    public static TransferCursor from(Transfer transfer) {
        return new TransferCursor(transfer.getCreatedAt(), transfer.getId());
    }
}
