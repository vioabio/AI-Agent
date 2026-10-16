package com.vio.vioaiagent.security;

import com.vio.vioaiagent.config.OidcProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/** Server-owned login timestamp; neither session activity nor request claims renew it. */
@Component
public final class OidcSessionLifetime {
    public static final String SESSION_ATTRIBUTE = OidcSessionLifetime.class.getName() + ".login";
    public static final String DEADLINE_ATTRIBUTE = OidcSessionLifetime.class.getName() + ".deadline";
    private final OidcProperties properties;
    private final Clock clock;

    @Autowired
    public OidcSessionLifetime(OidcProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public OidcSessionLifetime(OidcProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Called only by the OAuth2 success handler, after validated browser authentication. */
    public Instant start(HttpSession session, Authentication authentication) {
        BrowserIdentity identity = identity(authentication).orElseThrow(() ->
                new IllegalArgumentException("A validated OIDC browser identity is required"));
        Instant loginAt = clock.instant();
        Instant deadline = loginAt.plusSeconds(properties.sessionAbsoluteTimeoutSeconds());
        session.setAttribute(SESSION_ATTRIBUTE, new LoginStamp(identity, loginAt, deadline));
        return deadline;
    }

    /** Legacy, malformed, switched-principal and expired sessions all fail closed. */
    public Optional<Instant> deadline(HttpSession session, Authentication authentication) {
        if (session == null) return Optional.empty();
        Object value = session.getAttribute(SESSION_ATTRIBUTE);
        if (!(value instanceof LoginStamp stamp)) return Optional.empty();
        Optional<BrowserIdentity> identity = identity(authentication);
        Instant now = clock.instant();
        if (identity.isEmpty() || !identity.get().equals(stamp.identity())
                || stamp.loginAt() == null || stamp.absoluteExpiresAt() == null
                || stamp.loginAt().isAfter(now)
                || !stamp.absoluteExpiresAt().isAfter(stamp.loginAt())) return Optional.empty();
        Instant configuredLimit = stamp.loginAt().plusSeconds(properties.sessionAbsoluteTimeoutSeconds());
        Instant deadline = stamp.absoluteExpiresAt().isBefore(configuredLimit)
                ? stamp.absoluteExpiresAt() : configuredLimit;
        return now.isBefore(deadline) ? Optional.of(deadline) : Optional.empty();
    }

    public static boolean isBrowserAuthentication(Authentication authentication) {
        return authentication instanceof OAuth2AuthenticationToken oauth
                && oauth.isAuthenticated() && oauth.getPrincipal() instanceof OidcUser;
    }

    /** This attribute is populated by the filter, never by a client-provided header. */
    public static Instant validatedDeadline(HttpServletRequest request) {
        Object value = request.getAttribute(DEADLINE_ATTRIBUTE);
        return value instanceof Instant instant ? instant : null;
    }

    private Optional<BrowserIdentity> identity(Authentication authentication) {
        if (!isBrowserAuthentication(authentication)) return Optional.empty();
        OAuth2AuthenticationToken oauth = (OAuth2AuthenticationToken) authentication;
        OidcUser user = (OidcUser) oauth.getPrincipal();
        if (user.getSubject() == null || user.getSubject().isBlank()
                || user.getIssuer() == null || oauth.getName() == null || oauth.getName().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new BrowserIdentity(oauth.getAuthorizedClientRegistrationId(),
                user.getIssuer().toString(), user.getSubject(), oauth.getName()));
    }

    public record BrowserIdentity(String registrationId, String issuer, String subject,
                                  String principalName) implements Serializable { }

    public record LoginStamp(BrowserIdentity identity, Instant loginAt,
                             Instant absoluteExpiresAt) implements Serializable { }
}
