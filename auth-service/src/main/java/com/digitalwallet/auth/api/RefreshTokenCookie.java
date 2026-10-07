package com.digitalwallet.auth.api;

import com.digitalwallet.auth.config.JwtProperties;
import com.digitalwallet.auth.config.RefreshCookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Carries the refresh token in an httpOnly cookie for browser clients.
 *
 * <p>A browser app that keeps the refresh token in JavaScript-readable storage hands it to any
 * script that ever runs on the page, including an injected one, and the token lives for days. In an
 * httpOnly cookie no script can read it at all; the browser simply attaches it to the one path that
 * needs it.
 *
 * <p>The cookie is opt-in per request, through {@link #TRANSPORT_HEADER}. That keeps the JSON-body
 * contract unchanged for API clients such as curl and Postman, and it is also the CSRF defence: a
 * cookie the browser sends on its own is an ambient credential, so it is only honoured on requests
 * that also carry a custom header. Another site cannot add that header without a CORS preflight,
 * which the gateway refuses for any origin it does not trust. {@code SameSite=Strict} is the
 * second, independent layer.
 */
@Component
public class RefreshTokenCookie {

    public static final String TRANSPORT_HEADER = "X-Token-Transport";
    public static final String COOKIE_TRANSPORT = "cookie";

    /** Sent only to the token endpoints, never to the rest of the API. */
    static final String PATH = "/api/auth";

    private final RefreshCookieProperties properties;
    private final Duration lifetime;

    public RefreshTokenCookie(RefreshCookieProperties properties, JwtProperties jwtProperties) {
        this.properties = properties;
        this.lifetime = jwtProperties.refreshTokenTtl();
    }

    /** True when the caller asked for the refresh token to travel as a cookie. */
    public boolean requested(HttpServletRequest request) {
        return COOKIE_TRANSPORT.equalsIgnoreCase(request.getHeader(TRANSPORT_HEADER));
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> properties.name().equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    public ResponseCookie issue(String rawRefreshToken) {
        return base(rawRefreshToken).maxAge(lifetime).build();
    }

    /** An immediately-expiring cookie, which is how a server deletes one. */
    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(properties.name(), value)
                .httpOnly(true)
                .secure(properties.secure())
                .sameSite("Strict")
                .path(PATH);
    }
}
