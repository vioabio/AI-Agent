package com.vio.vioaiagent.config;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.security.OidcSessionLifetime;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/** Runs after Bearer authentication and before authorization/MVC identity adaptation. */
final class OidcAbsoluteSessionFilter extends OncePerRequestFilter {
    private final OidcSessionLifetime lifetime;
    private final OidcProperties properties;

    OidcAbsoluteSessionFilter(OidcSessionLifetime lifetime, OidcProperties properties) {
        this.lifetime = lifetime;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        request.removeAttribute(OidcSessionLifetime.DEADLINE_ATTRIBUTE);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (OidcSessionLifetime.isBrowserAuthentication(authentication)) {
            HttpSession session = request.getSession(false);
            Optional<Instant> deadline = lifetime.deadline(session, authentication);
            if (deadline.isPresent()) {
                request.setAttribute(OidcSessionLifetime.DEADLINE_ATTRIBUTE, deadline.get());
            } else {
                SecurityContextHolder.clearContext();
                RequestContext.clear();
                if (session != null) session.invalidate();
                String path = request.getContextPath().isBlank() ? "/" : request.getContextPath();
                response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(properties.cookieName(), "")
                        .path(path).maxAge(0).httpOnly(true).secure(properties.cookieSecure())
                        .sameSite(properties.cookieSameSite()).build().toString());
            }
        }
        // A valid Bearer JWT replaces any stale cookie identity and stays independent
        // of the browser lifetime. Public status then returns anonymous after expiry.
        chain.doFilter(request, response);
    }
}
