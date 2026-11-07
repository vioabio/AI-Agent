package com.vio.vioaiagent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Enforces the browser Origin/Referer boundary for state-changing OIDC calls.
 * CORS remains responsible for preflight; this filter protects credentialed
 * requests that could otherwise be forged from an untrusted web origin.
 */
final class OidcOriginFilter extends OncePerRequestFilter {
    private static final Set<String> STATE_CHANGING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final Set<String> allowedOrigins;

    OidcOriginFilter(Set<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? Set.of() : Set.copyOf(allowedOrigins);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if (!STATE_CHANGING_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            filterChain.doFilter(request, response);
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            String referer = request.getHeader("Referer");
            origin = refererOrigin(referer);
            if (referer != null && !referer.isBlank() && origin.isBlank()) {
                response.sendError(HttpStatus.FORBIDDEN.value(), "Referer 格式无效");
                return;
            }
        }
        // Non-browser clients do not send either header; CSRF token validation
        // still applies, while browser requests must identify an allowed origin.
        if (origin != null && !origin.isBlank() && !allowedOrigins.contains(origin.trim())) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "Origin 不在允许白名单");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String refererOrigin(String referer) {
        if (referer == null || referer.isBlank()) return "";
        try {
            java.net.URI uri = java.net.URI.create(referer);
            if (uri.getScheme() == null || uri.getHost() == null) return "";
            return uri.getScheme() + "://" + uri.getRawAuthority();
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }
}
