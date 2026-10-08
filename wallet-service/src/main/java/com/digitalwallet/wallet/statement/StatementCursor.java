package com.digitalwallet.wallet.statement;

import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Points at the last row a client has already seen, so the next page can start strictly after it.
 *
 * <p>Encoded rather than exposed as two raw query parameters for one practical reason: it keeps
 * the paging key opaque, so clients treat it as a token to hand back rather than something to
 * construct themselves. That leaves the ordering key free to change later without breaking them.
 *
 * <p>It is encoding, not encryption: it hides nothing sensitive, since a caller can only ever
 * page through their own statement, and ownership is checked separately on every request.
 */
public record StatementCursor(Instant createdAt, UUID lineId) {

    private static final String SEPARATOR = "|";

    public String encode() {
        String raw = createdAt.getEpochSecond() + ":" + createdAt.getNano() + SEPARATOR + lineId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static StatementCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\" + SEPARATOR);
            String[] timestamp = parts[0].split(":");
            return new StatementCursor(
                    Instant.ofEpochSecond(Long.parseLong(timestamp[0]), Long.parseLong(timestamp[1])),
                    UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            // A malformed cursor is bad input, not a server fault.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Malformed statement cursor", e);
        }
    }

    public static StatementCursor from(StatementLine line) {
        return new StatementCursor(line.occurredAt(), line.lineId());
    }
}
