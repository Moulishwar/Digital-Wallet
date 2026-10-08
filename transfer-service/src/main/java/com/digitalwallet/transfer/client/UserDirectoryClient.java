package com.digitalwallet.transfer.client;

import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
import com.digitalwallet.transfer.domain.Party;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Finds out who the two parties to a transfer are: the recipient by handle, and the sender's own
 * profile, so each can be named on the other's statement.
 *
 * <p>Calls auth-service <em>as the caller</em>, forwarding their bearer token rather than using a
 * service-wide credential. Handle lookup is an authenticated endpoint precisely so that it cannot be
 * used to enumerate accounts, and forwarding preserves that: this service can look up exactly what
 * the person asking could have looked up themselves.
 */
@Component
public class UserDirectoryClient {

    private static final Logger log = LoggerFactory.getLogger(UserDirectoryClient.class);

    private final RestClient restClient;

    public UserDirectoryClient(RestClient.Builder builder,
                               @Value("${auth.base-url}") String baseUrl,
                               @Value("${auth.connect-timeout}") Duration connectTimeout,
                               @Value("${auth.read-timeout}") Duration readTimeout) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    /**
     * @param handle already canonical: trimmed, lowercased, no leading {@code @}. auth-service
     *               folds case but does not strip the {@code @}, so passing it through raw makes
     *               {@code @alice} look like a different, nonexistent user.
     * @return empty when the handle definitively does not exist
     * @throws ApiException if auth-service cannot be reached. An unreachable directory must not be
     *                      reported as "no such user"; that would tell a sender their recipient
     *                      does not exist because of an outage on our side.
     */
    public Optional<Party> findByHandle(String handle, String bearerToken) {
        ResponseEntity<UserLookup> response;
        try {
            response = restClient.get()
                    .uri(uri -> uri.path("/api/users/lookup").queryParam("handle", handle).build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, res) -> { })
                    .toEntity(UserLookup.class);
        } catch (ResourceAccessException unreachable) {
            log.warn("Could not reach auth-service to resolve a handle: {}", unreachable.toString());
            throw directoryUnavailable(unreachable);
        }

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            UserLookup found = response.getBody();
            return Optional.of(new Party(found.userId(), found.handle(), found.fullName()));
        }
        if (response.getStatusCode().value() == 404) {
            return Optional.empty();
        }

        log.warn("auth-service returned {} resolving a handle", response.getStatusCode());
        throw directoryUnavailable(null);
    }

    /**
     * The caller's own handle and display name, for the recipient's statement.
     *
     * <p>Only the labels are taken from the answer. The caller's identity is already known from the
     * verified token, and the returned party carries that id rather than anything read back here.
     */
    public Party requireCaller(UUID callerUserId, String bearerToken) {
        ResponseEntity<OwnProfile> response;
        try {
            response = restClient.get()
                    .uri("/api/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, res) -> { })
                    .toEntity(OwnProfile.class);
        } catch (ResourceAccessException unreachable) {
            log.warn("Could not reach auth-service for the caller's profile: {}", unreachable.toString());
            throw directoryUnavailable(unreachable);
        }

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            return new Party(callerUserId, response.getBody().handle(), response.getBody().fullName());
        }

        log.warn("auth-service returned {} for the caller's profile", response.getStatusCode());
        throw directoryUnavailable(null);
    }

    private static ApiException directoryUnavailable(Exception cause) {
        return new ApiException(ErrorCode.INTERNAL_ERROR,
                "Could not verify the recipient right now. Please try again.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserLookup(UUID userId, String handle, String fullName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OwnProfile(String handle, String fullName) {
    }
}
