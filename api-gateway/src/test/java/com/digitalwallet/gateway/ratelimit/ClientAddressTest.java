package com.digitalwallet.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class ClientAddressTest {

    private static final ClientAddress BEHIND_NGINX = new ClientAddress(List.of("172.16.0.0/12"));

    private static MockServerHttpRequest from(String connection, String reported) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new InetSocketAddress(connection, 40_000));
        if (reported != null) {
            request.header(ClientAddress.HEADER, reported);
        }
        return request.build();
    }

    @Test
    @DisplayName("a request from a trusted proxy is attributed to the client the proxy reports")
    void believesTrustedProxy() {
        assertThat(BEHIND_NGINX.of(from("172.18.0.7", "203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("anyone else claiming an address is keyed by their connection instead")
    void ignoresHeaderFromAnyoneElse() {
        assertThat(BEHIND_NGINX.of(from("198.51.100.4", "203.0.113.9"))).isEqualTo("198.51.100.4");
    }

    @Test
    @DisplayName("with no trusted proxies configured, the header is never believed")
    void trustsNoOneByDefault() {
        assertThat(new ClientAddress(List.of("")).of(from("172.18.0.7", "203.0.113.9"))).isEqualTo("172.18.0.7");
    }

    @Test
    @DisplayName("a trusted proxy that reports nothing leaves the connection address")
    void fallsBackWithoutHeader() {
        assertThat(BEHIND_NGINX.of(from("172.18.0.7", null))).isEqualTo("172.18.0.7");
        assertThat(BEHIND_NGINX.of(from("172.18.0.7", " "))).isEqualTo("172.18.0.7");
    }

    @Test
    @DisplayName("ranges match on their prefix only, and a bare address is a range of one")
    void rangesMatchOnPrefix() throws Exception {
        ClientAddress.Range docker = ClientAddress.Range.parse("172.16.0.0/12");
        assertThat(docker.contains(java.net.InetAddress.getByName("172.31.255.255"))).isTrue();
        assertThat(docker.contains(java.net.InetAddress.getByName("172.32.0.1"))).isFalse();
        assertThat(ClientAddress.Range.parse("10.0.0.5").contains(java.net.InetAddress.getByName("10.0.0.5"))).isTrue();
        assertThat(ClientAddress.Range.parse("10.0.0.5").contains(java.net.InetAddress.getByName("10.0.0.6"))).isFalse();
    }

    @Test
    @DisplayName("a misspelt range fails at start-up rather than silently trusting nobody")
    void rejectsNonsense() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new ClientAddress(List.of("nginx")));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new ClientAddress(List.of("10.0.0.0/33")));
    }
}
