package com.digitalwallet.gateway.ratelimit;

/**
 * A single client's allowance, as a token bucket.
 *
 * <p>Chosen over a fixed window because a fixed window lets a client spend its whole allowance at
 * the end of one window and again at the start of the next — twice the intended rate, in an
 * instant, precisely when someone is hammering the service. A bucket refills continuously, so the
 * long-run rate is the refill rate and {@code capacity} is only how much burst is tolerated.
 *
 * <p>Refill is computed on read rather than by a timer. There is no background thread per client,
 * and a bucket nobody touches costs nothing until it is touched again.
 */
final class TokenBucket {

    private final double capacity;
    private final double refillPerNano;

    private double tokens;
    private long lastRefillNanos;
    private volatile long lastAccessNanos;

    TokenBucket(long capacity, double refillPerSecond, long nowNanos) {
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000.0;
        this.tokens = capacity;
        this.lastRefillNanos = nowNanos;
        this.lastAccessNanos = nowNanos;
    }

    /**
     * @return true if the request may proceed, false if the client is over its limit
     */
    synchronized boolean tryConsume(long nowNanos) {
        lastAccessNanos = nowNanos;

        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
            lastRefillNanos = nowNanos;
        }

        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** How long until at least one token is available, for the {@code Retry-After} header. */
    synchronized long secondsUntilNextToken() {
        if (tokens >= 1.0) {
            return 0;
        }
        double needed = 1.0 - tokens;
        double seconds = needed / (refillPerNano * 1_000_000_000.0);
        return Math.max(1L, (long) Math.ceil(seconds));
    }

    long lastAccessNanos() {
        return lastAccessNanos;
    }
}
