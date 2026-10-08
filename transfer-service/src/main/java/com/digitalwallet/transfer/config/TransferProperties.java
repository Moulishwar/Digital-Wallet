package com.digitalwallet.transfer.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Transfer behaviour, bound from {@code transfers.*}.
 *
 * @param maxAmountMinor  ceiling on a single transfer, in paise. Not a regulatory limit but a blast
 *                        radius, so a fat-fingered or hostile request cannot move an absurd sum in
 *                        one call. There are no per-day or velocity limits.
 * @param reconciliation  settings for the sweep that settles transfers of unknown outcome
 * @param idempotency     settings for handling repeated {@code Idempotency-Key} values
 */
@ConfigurationProperties("transfers")
public record TransferProperties(long maxAmountMinor,
                                 Reconciliation reconciliation,
                                 Idempotency idempotency) {

    /**
     * @param interval  how often the sweep runs
     * @param minAge    how long a transfer must have been unresolved before it is swept. A row
     *                  marked a moment ago may still have its original request in flight, and
     *                  sweeping it immediately would race that request rather than resolve it.
     * @param batchSize how many to settle per run, so one sweep cannot monopolise the service
     */
    public record Reconciliation(Duration interval, Duration minAge, int batchSize) {
    }

    /**
     * @param maxWait how long a duplicate request waits for the original to record its answer
     *                before giving up. Bounded so that one request dying mid-flight cannot hang
     *                every retry that follows it.
     */
    public record Idempotency(Duration maxWait) {
    }
}
