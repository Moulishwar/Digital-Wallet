package com.digitalwallet.gateway.ratelimit;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rate limits, bound from {@code gateway.rate-limit.*}.
 *
 * @param authenticated allowance per user, keyed by the token subject
 * @param anonymous     allowance per client address, for the routes that have no user yet, which
 *                      is where credential stuffing lands
 * @param trustedProxies address ranges (CIDR) of reverse proxies whose {@code X-Real-IP} header is
 *                      believed; empty, the default, believes no one
 */
@ConfigurationProperties("gateway.rate-limit")
public record RateLimitProperties(boolean enabled, Allowance authenticated, Allowance anonymous,
                                  @DefaultValue List<String> trustedProxies) {

    /**
     * @param capacity        the most a client may spend in one burst
     * @param refillPerSecond the sustained rate, once the burst is spent
     */
    public record Allowance(long capacity, double refillPerSecond) {
    }
}
