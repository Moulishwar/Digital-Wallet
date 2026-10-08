package com.digitalwallet.auth.wallet;

import com.digitalwallet.common.security.ServiceCredential;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls wallet-service to provision a wallet when a user registers.
 *
 * <p>Failures here are logged and swallowed on purpose. A signup should not fail because a
 * downstream service is restarting: the user's account is real either way, and wallet-service
 * creates the wallet on first access if it is missing. Making registration depend on wallet-service
 * being up would couple the two services far more tightly than the design intends.
 */
@Component
public class WalletClient {

    private static final Logger log = LoggerFactory.getLogger(WalletClient.class);

    private final RestClient restClient;

    public WalletClient(RestClient.Builder builder,
                        @Value("${wallet.base-url}") String baseUrl,
                        @Value("${wallet.connect-timeout}") Duration connectTimeout,
                        @Value("${wallet.read-timeout}") Duration readTimeout,
                        @Value("${security.service-credential}") String serviceCredential) {

        // Explicit timeouts. The defaults are effectively infinite, which would let a wedged
        // wallet-service hold registration threads open until the pool is exhausted.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                // Set once on the client rather than per call, so a new request cannot be added
                // later that forgets it and fails in a way that looks like an outage.
                .defaultHeader(ServiceCredential.HEADER, serviceCredential)
                .build();
    }

    /**
     * @return true if the wallet is known to exist, false if provisioning could not be confirmed
     */
    public boolean provisionWallet(UUID userId) {
        try {
            restClient.post()
                    .uri("/internal/accounts")
                    .body(Map.of("ownerUserId", userId.toString()))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not provision a wallet for user {} — wallet-service will create it "
                    + "on first access. Reason: {}", userId, e.toString());
            return false;
        }
    }
}
