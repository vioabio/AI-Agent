package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.config.OidcPrincipalPolicyProperties;
import com.vio.vioaiagent.config.OidcProperties;
import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.security.OidcPrincipalPolicyAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthSessionControllerTest {
    @AfterEach
    void cleanup() { SecurityContextHolder.clearContext(); }

    @Test
    void apiKeyCompatibilityModeNeverReportsSharedKeyAsPersonalLogin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", "shared-test-fixture");
        AuthSessionController.SessionStatus status = controller(true).session(request).getBody();
        assertNotNull(status);
        assertEquals("api_key", status.mode());
        assertFalse(status.authenticated());
        assertFalse(status.accessDenied());
        assertNull(status.principalId());
        assertNull(status.tenantId());
        assertTrue(status.roles().isEmpty());
        assertNull(status.absoluteExpiresAt());
        assertNull(status.loginUrl());
        assertNull(status.logoutUrl());
        assertFalse(status.csrf().enabled());
        assertFalse(status.csrf().available());
        assertEquals("no-store", controller(true).session(request).getHeaders().getCacheControl());
    }

    @Test
    void disabledModeDoesNotInventPersonalIdentityFromUnrelatedAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("unrelated", "fixture", List.of()));
        AuthSessionController.SessionStatus status = controller(false)
                .session(new MockHttpServletRequest()).getBody();
        assertNotNull(status);
        assertEquals("disabled", status.mode());
        assertFalse(status.authenticated());
        assertNull(status.principalId());
    }

    private AuthSessionController controller(boolean enabled) {
        OidcProperties oidc = new OidcProperties();
        SecurityProperties security = new SecurityProperties(enabled, Map.of(), List.of(), List.of());
        return new AuthSessionController(oidc, security,
                new OidcPrincipalPolicyAdapter(oidc, new OidcPrincipalPolicyProperties()));
    }
}
