package com.vio.vioaiagent.config;

import com.vio.vioaiagent.security.OidcSessionLifetime;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

import java.io.IOException;

/** Stamp a fresh absolute lifetime and return to the same-origin SPA entry point. */
final class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {
    private final OidcSessionLifetime lifetime;

    OidcLoginSuccessHandler(OidcSessionLifetime lifetime) {
        this.lifetime = lifetime;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        lifetime.start(request.getSession(true), authentication);
        new HttpSessionRequestCache().removeRequest(request, response);
        response.setHeader("Cache-Control", "no-store");
        // Do not follow a saved API request or accept user-controlled return URLs.
        response.sendRedirect("/");
    }
}
