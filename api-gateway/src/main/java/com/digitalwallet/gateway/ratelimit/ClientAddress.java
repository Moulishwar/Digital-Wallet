package com.digitalwallet.gateway.ratelimit;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Works out which client a request came from, for keying the anonymous rate limit.
 *
 * <p>Normally that is simply the other end of the connection. Behind a reverse proxy, though, the
 * other end is always the proxy, and every visitor would share one allowance. So a request that
 * arrives <i>from a trusted proxy</i> is attributed to the address the proxy reports in
 * {@code X-Real-IP}.
 *
 * <p>Only from a trusted proxy: the header is just text, and believed from anyone else it would let
 * a client claim a fresh address on every request and never be limited at all. The proxy must also
 * overwrite the header rather than pass along whatever the client sent; nginx's
 * {@code proxy_set_header X-Real-IP $remote_addr} does exactly that.
 */
public final class ClientAddress {

    static final String HEADER = "X-Real-IP";

    private final List<Range> trustedProxies;

    public ClientAddress(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies.stream()
                .map(String::trim)
                .filter(cidr -> !cidr.isEmpty())
                .map(Range::parse)
                .toList();
    }

    public String of(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        InetAddress connection = remote == null ? null : remote.getAddress();
        if (connection == null) {
            return "unknown";
        }
        String reported = request.getHeaders().getFirst(HEADER);
        if (reported != null && !reported.isBlank() && isTrusted(connection)) {
            return reported.trim();
        }
        return connection.getHostAddress();
    }

    private boolean isTrusted(InetAddress address) {
        return trustedProxies.stream().anyMatch(range -> range.contains(address));
    }

    /** An address range in CIDR notation, such as {@code 172.16.0.0/12}; a bare address is a /32. */
    record Range(byte[] network, int prefixLength) {

        static Range parse(String cidr) {
            String[] parts = cidr.split("/", 2);
            byte[] network = literal(parts[0]).getAddress();
            int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : network.length * 8;
            if (prefix < 0 || prefix > network.length * 8) {
                throw new IllegalArgumentException("Not a valid address range: " + cidr);
            }
            return new Range(network, prefix);
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            for (int bit = 0; bit < prefixLength; bit++) {
                int mask = 0x80 >>> (bit % 8);
                if ((candidate[bit / 8] & mask) != (network[bit / 8] & mask)) {
                    return false;
                }
            }
            return true;
        }

        /** Parses an address literal without ever looking a name up in DNS. */
        private static InetAddress literal(String text) {
            if (!text.matches("[0-9a-fA-F:.]+")) {
                throw new IllegalArgumentException("Not an IP address: " + text);
            }
            try {
                return InetAddress.getByName(text);
            } catch (java.net.UnknownHostException e) {
                throw new IllegalArgumentException("Not an IP address: " + text, e);
            }
        }
    }
}
