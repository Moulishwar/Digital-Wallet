package com.digitalwallet.gateway.ratelimit;

import com.digitalwallet.gateway.error.GatewayProblem;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Caps how fast any one client can hit the API.
 *
 * <p>Two things make this more than a counter. First, it is keyed by <b>token subject</b> when the
 * caller is authenticated, so one user cannot exhaust everyone's allowance by sharing an IP with
 * them — behind a corporate NAT or a mobile carrier, an IP is thousands of people. Second, it falls
 * back to the client address on the public routes, because a request to {@code /api/auth/login} has
 * no user yet, and that is exactly the endpoint worth protecting from someone working through a
 * list of passwords.
 *
 * <p><b>Known limitation, stated rather than hidden:</b> the buckets are in this JVM's memory. Run
 * two gateways and a client gets both allowances. Redis is the standard fix and is deliberately not
 * used here (extra infrastructure is not worth it for a system this size); the honest position is
 * that this is a per-instance limit, and it is documented as one in the README.
 *
 * <p>Ordered after Spring Security's filter chain so the caller has already been authenticated and
 * the subject is available to key on.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@ConditionalOnProperty(value = "gateway.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitWebFilter.class);

    /** Buckets untouched for this long are dropped, so the map cannot grow without bound. */
    private static final Duration IDLE_EVICTION = Duration.ofMinutes(10);

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitWebFilter(RateLimitProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(Authentication::isAuthenticated)
                .map(this::subjectKey)
                // No authenticated caller — a public route, or a request that never presented a
                // token. Fall back to where it came from.
                .defaultIfEmpty("")
                .flatMap(subject -> subject.isEmpty()
                        ? apply(exchange, chain, "ip:" + clientAddress(exchange), properties.anonymous())
                        : apply(exchange, chain, "user:" + subject, properties.authenticated()));
    }

    private Mono<Void> apply(ServerWebExchange exchange, WebFilterChain chain,
                             String key, RateLimitProperties.Allowance allowance) {

        long now = System.nanoTime();
        TokenBucket bucket = buckets.computeIfAbsent(key,
                k -> new TokenBucket(allowance.capacity(), allowance.refillPerSecond(), now));

        if (bucket.tryConsume(now)) {
            return chain.filter(exchange);
        }

        long retryAfter = bucket.secondsUntilNextToken();
        log.info("Rate limit reached for {} on {}", key, exchange.getRequest().getPath());
        exchange.getResponse().getHeaders().add("Retry-After", Long.toString(retryAfter));

        return GatewayProblem.write(exchange, objectMapper, HttpStatus.TOO_MANY_REQUESTS,
                "rate-limit-exceeded", "Too many requests",
                "Rate limit exceeded. Retry in " + retryAfter + " second(s).");
    }

    private String subjectKey(Authentication authentication) {
        if (authentication.getPrincipal() instanceof Jwt jwt && jwt.getSubject() != null) {
            return jwt.getSubject();
        }
        return "";
    }

    /**
     * The caller's address.
     *
     * <p>Taken from the connection, not from {@code X-Forwarded-For}. That header is client-supplied
     * unless a proxy you control overwrites it, and trusting it here would let anyone reset their
     * own limit by inventing a new value per request. A deployment that genuinely sits behind a
     * trusted proxy should enable Spring's forwarded-header handling rather than parsing it here.
     */
    private String clientAddress(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null
                ? "unknown"
                : remote.getAddress().getHostAddress();
    }

    /**
     * Drops buckets nobody has used lately.
     *
     * <p>Without this the map is an unbounded cache keyed by anything a caller can vary, which is a
     * memory leak with a stranger holding the tap.
     */
    @Scheduled(fixedDelay = 60_000)
    void evictIdleBuckets() {
        long cutoff = System.nanoTime() - IDLE_EVICTION.toNanos();
        buckets.entrySet().removeIf(entry -> entry.getValue().lastAccessNanos() < cutoff);
    }
}
