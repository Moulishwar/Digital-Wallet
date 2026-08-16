package com.digitalwallet.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate limits, bound from {@code gateway.rate-limit.*}.
 *
 * @param authenticated allowance per user, keyed by the token subject
 * @param anonymous     allowance per client address, for the routes that have no user yet — which
 *                      is where credential stuffing lands
 */
@ConfigurationProperties("gateway.rate-limit")
public record RateLimitProperties(boolean enabled, Allowance authenticated, Allowance anonymous) {

    /**
     * @param capacity        the most a client may spend in one burst
     * @param refillPerSecond the sustained rate, once the burst is spent
     */
    public record Allowance(long capacity, double refillPerSecond) {
    }
}
