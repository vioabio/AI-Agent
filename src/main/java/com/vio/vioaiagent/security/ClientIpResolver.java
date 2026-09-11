package com.vio.vioaiagent.security;

import jakarta.servlet.http.HttpServletRequest;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Resolves the client IP without trusting caller-controlled forwarding headers
 * by default.
 *
 * <p>{@code X-Forwarded-For} and {@code X-Real-IP} are considered only when
 * the socket peer belongs to an explicitly configured trusted proxy CIDR. For
 * a proxy chain, resolution walks from right to left and returns the first
 * untrusted hop. A malformed header makes the resolver fall back to the socket
 * peer instead of trying a less authoritative header.</p>
 */
public final class ClientIpResolver {

    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_REAL_IP = "X-Real-IP";
    private static final String UNKNOWN = "unknown";
    private static final int MAX_FORWARDED_HOPS = 32;
    private static final Pattern IPV4_LITERAL = Pattern.compile("^[0-9]{1,3}(?:\\.[0-9]{1,3}){3}$");
    private static final Pattern IPV6_LITERAL = Pattern.compile("^[0-9A-Fa-f:]+$");

    private final List<CidrRange> trustedProxyRanges;

    public ClientIpResolver(List<String> trustedProxyCidrs) {
        List<CidrRange> ranges = new ArrayList<>();
        if (trustedProxyCidrs != null) {
            for (String value : trustedProxyCidrs) {
                if (value == null || value.isBlank()) continue;
                ranges.add(CidrRange.parse(value));
            }
        }
        this.trustedProxyRanges = List.copyOf(ranges);
    }

    /** A safe default for callers constructed outside Spring. */
    public static ClientIpResolver denyForwardedHeaders() {
        return new ClientIpResolver(List.of());
    }

    public String resolve(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        IpLiteral socketPeer = IpLiteral.parse(request.getRemoteAddr());
        if (socketPeer == null) return safeRemoteAddress(request.getRemoteAddr());
        if (!isTrustedProxy(socketPeer)) return socketPeer.text();

        HeaderValue forwardedFor = readHeader(request, HEADER_FORWARDED_FOR);
        if (forwardedFor.present()) {
            return forwardedFor.valid()
                    ? resolveForwardedChain(socketPeer, forwardedFor.value())
                    : socketPeer.text();
        }

        HeaderValue realIp = readHeader(request, HEADER_REAL_IP);
        if (!realIp.present() || !realIp.valid()) return socketPeer.text();
        IpLiteral resolved = IpLiteral.parse(realIp.value());
        return resolved == null ? socketPeer.text() : resolved.text();
    }

    private String resolveForwardedChain(IpLiteral socketPeer, String header) {
        String[] rawHops = header.split(",", -1);
        if (rawHops.length == 0 || rawHops.length > MAX_FORWARDED_HOPS) return socketPeer.text();

        List<IpLiteral> hops = new ArrayList<>(rawHops.length);
        for (String rawHop : rawHops) {
            IpLiteral hop = IpLiteral.parse(rawHop);
            if (hop == null) return socketPeer.text();
            hops.add(hop);
        }

        IpLiteral candidate = socketPeer;
        for (int index = hops.size() - 1; index >= 0; index--) {
            if (!isTrustedProxy(candidate)) return candidate.text();
            candidate = hops.get(index);
        }
        return candidate.text();
    }

    private boolean isTrustedProxy(IpLiteral address) {
        for (CidrRange range : trustedProxyRanges) {
            if (range.contains(address.bytes())) return true;
        }
        return false;
    }

    private static HeaderValue readHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return HeaderValue.absent();
        List<String> parts = new ArrayList<>();
        while (values.hasMoreElements()) {
            String value = values.nextElement();
            if (value == null || value.isBlank()) return HeaderValue.invalid();
            parts.add(value.trim());
        }
        return new HeaderValue(true, true, String.join(",", parts));
    }

    private static String safeRemoteAddress(String value) {
        if (value == null) return UNKNOWN;
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.isEmpty() || normalized.length() > 128 ? UNKNOWN : normalized;
    }

    private record IpLiteral(byte[] bytes, String text) {
        private static IpLiteral parse(String raw) {
            if (raw == null) return null;
            String value = raw.trim();
            if (!looksLikeLiteral(value)) return null;
            try {
                InetAddress address = InetAddress.getByName(value);
                if (value.indexOf(':') >= 0 && !(address instanceof Inet6Address)) return null;
                if (value.indexOf(':') < 0 && !(address instanceof Inet4Address)) return null;
                return new IpLiteral(address.getAddress(), address.getHostAddress());
            } catch (UnknownHostException ex) {
                return null;
            }
        }

        private static boolean looksLikeLiteral(String value) {
            if (value.isEmpty() || value.length() > 64 || value.indexOf('%') >= 0) return false;
            return value.indexOf(':') >= 0
                    ? IPV6_LITERAL.matcher(value).matches()
                    : IPV4_LITERAL.matcher(value).matches();
        }
    }

    private record CidrRange(byte[] network, int prefixLength) {
        private static CidrRange parse(String raw) {
            String value = raw.trim();
            int separator = value.indexOf('/');
            if (separator != value.lastIndexOf('/')) throw invalidCidr(value);
            String addressPart = separator < 0 ? value : value.substring(0, separator);
            IpLiteral address = IpLiteral.parse(addressPart);
            if (address == null) throw invalidCidr(value);

            int maxBits = address.bytes().length * Byte.SIZE;
            int prefix = maxBits;
            if (separator >= 0) {
                String prefixPart = value.substring(separator + 1);
                try {
                    prefix = Integer.parseInt(prefixPart);
                } catch (NumberFormatException ex) {
                    throw invalidCidr(value);
                }
            }
            if (prefix < 0 || prefix > maxBits) throw invalidCidr(value);

            byte[] network = address.bytes().clone();
            maskHostBits(network, prefix);
            return new CidrRange(network, prefix);
        }

        private boolean contains(byte[] address) {
            if (address == null || address.length != network.length) return false;
            int fullBytes = prefixLength / Byte.SIZE;
            int remainingBits = prefixLength % Byte.SIZE;
            for (int index = 0; index < fullBytes; index++) {
                if (address[index] != network[index]) return false;
            }
            if (remainingBits == 0) return true;
            int mask = (0xFF << (Byte.SIZE - remainingBits)) & 0xFF;
            return ((address[fullBytes] & 0xFF) & mask)
                    == ((network[fullBytes] & 0xFF) & mask);
        }

        private static void maskHostBits(byte[] address, int prefix) {
            int fullBytes = prefix / Byte.SIZE;
            int remainingBits = prefix % Byte.SIZE;
            if (remainingBits > 0 && fullBytes < address.length) {
                int mask = (0xFF << (Byte.SIZE - remainingBits)) & 0xFF;
                address[fullBytes] = (byte) ((address[fullBytes] & 0xFF) & mask);
                fullBytes++;
            }
            for (int index = fullBytes; index < address.length; index++) address[index] = 0;
        }

        private static IllegalArgumentException invalidCidr(String value) {
            String safe = value.replace('\r', ' ').replace('\n', ' ');
            if (safe.length() > 128) safe = safe.substring(0, 128);
            return new IllegalArgumentException("trusted proxy CIDR 无效: " + safe);
        }
    }

    private record HeaderValue(boolean present, boolean valid, String value) {
        private static HeaderValue absent() {
            return new HeaderValue(false, true, null);
        }

        private static HeaderValue invalid() {
            return new HeaderValue(true, false, null);
        }
    }
}
