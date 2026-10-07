package com.digitalwallet.auth.config;

import com.digitalwallet.auth.user.AppUser;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Accounts that are made administrators by configuration, bound from {@code security.admins.*}.
 *
 * <p>This is how the first administrator exists at all: there is no endpoint that grants
 * {@code ROLE_ADMIN}, because an endpoint that can create admins needs an admin to call it. The
 * operator lists addresses in {@code ADMIN_EMAILS} instead, and those accounts are promoted when
 * they next log in.
 *
 * <p>Promotion only. Removing an address from the list does not demote the account, so an admin
 * granted some other way is never silently stripped by a configuration change.
 *
 * @param emails addresses to promote; matched case-insensitively
 */
@ConfigurationProperties("security.admins")
public record AdminProperties(@DefaultValue List<String> emails) {

    public Set<String> normalizedEmails() {
        return emails.stream()
                .map(AppUser::normalizeEmail)
                .filter(email -> email != null && !email.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
