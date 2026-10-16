package com.vio.vioaiagent.config;

import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.servlet.server.Session;
import org.springframework.core.Ordered;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/** Applies the validated OIDC cookie and idle lifetime to the actual container. */
final class OidcSessionWebServerCustomizer
        implements WebServerFactoryCustomizer<ConfigurableServletWebServerFactory>, Ordered {
    private final OidcProperties properties;
    private final ServerProperties serverProperties;

    OidcSessionWebServerCustomizer(OidcProperties properties, ServerProperties serverProperties) {
        this.properties = properties;
        this.serverProperties = serverProperties;
    }

    @Override
    public void customize(ConfigurableServletWebServerFactory factory) {
        if (!properties.enabled()) return;
        Session session = serverProperties.getServlet().getSession();
        session.setTimeout(Duration.ofSeconds(properties.sessionIdleTimeoutSeconds()));
        session.setTrackingModes(Set.of(Session.SessionTrackingMode.COOKIE));
        session.getCookie().setName(properties.cookieName());
        session.getCookie().setHttpOnly(true);
        session.getCookie().setSecure(properties.cookieSecure());
        session.getCookie().setSameSite(Cookie.SameSite.valueOf(
                properties.cookieSameSite().toUpperCase(Locale.ROOT)));
        factory.setSession(session);
    }

    @Override
    public int getOrder() {
        // Boot's ServletWebServerFactoryCustomizer applies server.* at order 0.
        return 1;
    }
}
