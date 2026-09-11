package com.vio.vioaiagent.mcp.transport;

import java.net.URI;
import java.util.Locale;

/**
 * 安全校验自研 MCP 实验传输的 endpoint。
 *
 * <p>生产主链使用 Spring AI MCP provider；此策略只保证实验 transport
 * 不会因为服务端返回一条恶意 SSE endpoint 而跨主机跳转。DNS pinning、
 * 凭据 audience 和生产网络策略仍由正式 provider/网关负责。</p>
 */
final class McpEndpointPolicy {
    private McpEndpointPolicy() { }

    static URI validateBase(String value) {
        URI uri = parse(value, "MCP base URL");
        String scheme = lower(uri.getScheme());
        if (!"https".equals(scheme) && !("http".equals(scheme) && isLoopback(uri.getHost()))) {
            throw new IllegalArgumentException("实验 MCP 仅允许 HTTPS；HTTP 仅限 loopback");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getQuery() != null) {
            throw new IllegalArgumentException("MCP base URL 不得包含 userinfo/query/fragment");
        }
        return uri;
    }

    static URI validateSessionEndpoint(String baseUrl, String endpoint) {
        URI base = validateBase(baseUrl);
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("MCP session endpoint 不能为空");
        }
        URI candidate;
        try {
            // The MCP SSE handshake commonly returns a relative `data:` URI
            // (for example `/mcp/message?sessionId=...`).  Relative paths are
            // resolved against the already validated base origin; absolute
            // endpoints still go through the same-origin check below.
            candidate = URI.create(endpoint.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("MCP session endpoint 格式无效", ex);
        }
        URI resolved = candidate.isAbsolute() ? candidate : base.resolve(candidate);
        if (resolved.getScheme() == null || resolved.getHost() == null
                || !sameOrigin(base, resolved) || resolved.getUserInfo() != null
                || resolved.getFragment() != null) {
            throw new IllegalArgumentException("MCP session endpoint 必须与 base URL 同源且不得含 userinfo/fragment");
        }
        return resolved;
    }

    private static URI parse(String value, String label) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException(label + " 必须是绝对 URL");
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(label + " 格式无效", ex);
        }
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized) || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized) || "[::1]".equals(normalized);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
