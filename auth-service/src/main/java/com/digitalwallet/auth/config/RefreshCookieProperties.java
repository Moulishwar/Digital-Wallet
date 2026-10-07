package com.digitalwallet.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the refresh token is carried for browser clients, bound from {@code security.refresh-cookie.*}.
 *
 * @param name   the cookie name
 * @param secure send the cookie over HTTPS only. On by default; the only reason to turn it off is
 *               a plain-HTTP development setup on an address other than {@code localhost}, which
 *               browsers already treat as secure
 */
@ConfigurationProperties("security.refresh-cookie")
public record RefreshCookieProperties(@DefaultValue("dw_refresh") String name,
                                      @DefaultValue("true") boolean secure) {
}
