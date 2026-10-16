package com.vio.vioaiagent.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vio.vioaiagent.config.OidcClientRegistrationFactory;
import com.vio.vioaiagent.config.OidcProperties;
import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.security.OidcPrincipalPolicyAdapter;
import com.vio.vioaiagent.security.OidcSessionLifetime;
import com.vio.vioaiagent.security.PrincipalPolicy;
import com.vio.vioaiagent.security.PrincipalPolicyDecision;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Anonymous-readable session bootstrap; never publishes credentials or tool bindings. */
@RestController
@RequestMapping("/auth")
public class AuthSessionController {
    private final OidcProperties oidc;
    private final SecurityProperties security;
    private final OidcPrincipalPolicyAdapter adapter;

    public AuthSessionController(OidcProperties oidc, SecurityProperties security,
                                 OidcPrincipalPolicyAdapter adapter) {
        this.oidc = oidc;
        this.security = security;
        this.adapter = adapter;
    }

    @GetMapping("/session")
    @Operation(operationId = "getAuthSession", summary = "读取当前浏览器会话与认证模式")
    public ResponseEntity<SessionStatus> session(HttpServletRequest request) {
        Instant serverTime = Instant.now();
        String mode = oidc.enabled() ? "oidc" : security.isEnabled() ? "api_key" : "disabled";
        String loginUrl = oidc.enabled() ? request.getContextPath() + "/oauth2/authorization/"
                + OidcClientRegistrationFactory.REGISTRATION_ID : null;
        String logoutUrl = oidc.enabled() ? request.getContextPath() + "/logout" : null;
        Object csrfValue = request.getAttribute(CsrfToken.class.getName());
        CsrfStatus csrf = new CsrfStatus(oidc.enabled(), oidc.enabled() && csrfValue instanceof CsrfToken,
                "X-CSRF-TOKEN", "CSRF-TOKEN");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean trusted = OidcSessionLifetime.isBrowserAuthentication(authentication)
                || authentication instanceof JwtAuthenticationToken && authentication.isAuthenticated();
        PrincipalPolicyDecision decision = oidc.enabled() && trusted
                ? adapter.resolve(authentication, OidcSessionLifetime.validatedDeadline(request)) : null;
        PrincipalPolicy policy = decision != null && decision.allowed() ? decision.policy() : null;
        Instant deadline = policy == null ? null : authentication instanceof JwtAuthenticationToken jwt
                ? jwt.getToken().getExpiresAt() : OidcSessionLifetime.validatedDeadline(request);
        SessionStatus body = new SessionStatus(mode, policy != null,
                decision != null && !decision.allowed(), policy == null ? null : policy.principalId(),
                policy == null ? null : policy.tenantId(), policy == null ? List.of() : policy.roles().stream().sorted().toList(),
                serverTime, deadline, loginUrl, logoutUrl, csrf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SessionStatus(String mode, boolean authenticated, boolean accessDenied,
                                String principalId, String tenantId, List<String> roles,
                                Instant serverTime, Instant absoluteExpiresAt, String loginUrl,
                                String logoutUrl, CsrfStatus csrf) { }

    public record CsrfStatus(boolean enabled, boolean available, String headerName, String cookieName) { }
}
