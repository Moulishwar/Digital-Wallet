package com.digitalwallet.transfer.client;

import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
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
 * Turns a public handle into the user id a posting needs.
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
     * @return empty when the handle definitively does not exist
     * @throws ApiException if auth-service cannot be reached. An unreachable directory must not be
     *                      reported as "no such user" — that would tell a sender their recipient
     *                      does not exist because of an outage on our side.
     */
    public Optional<UUID> findUserIdByHandle(String handle, String bearerToken) {
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
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "Could not verify the recipient right now. Please try again.", unreachable);
        }

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            return Optional.of(response.getBody().userId());
        }
        if (response.getStatusCode().value() == 404) {
            return Optional.empty();
        }

        log.warn("auth-service returned {} resolving a handle", response.getStatusCode());
        throw new ApiException(ErrorCode.INTERNAL_ERROR,
                "Could not verify the recipient right now. Please try again.");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserLookup(UUID userId, String handle, String fullName) {
    }
}
