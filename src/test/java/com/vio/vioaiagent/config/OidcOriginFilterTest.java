package com.vio.vioaiagent.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OidcOriginFilterTest {

    @Test
    void rejectsStateChangingRequestFromUnknownOrigin() throws Exception {
        OidcOriginFilter filter = new OidcOriginFilter(Set.of("https://agent.example.com"));
        MockHttpServletRequest request = request("POST", "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void allowsConfiguredOriginAndNonBrowserRequest() throws Exception {
        OidcOriginFilter filter = new OidcOriginFilter(Set.of("https://agent.example.com"));
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
        filter.doFilter(request("PATCH", "https://agent.example.com"), allowedResponse, new MockFilterChain());
        assertEquals(200, allowedResponse.getStatus());

        MockHttpServletResponse nonBrowserResponse = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request("DELETE", null), nonBrowserResponse, chain);
        assertTrue(nonBrowserResponse.getStatus() < 400);
    }

    private MockHttpServletRequest request(String method, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        if (origin != null) request.addHeader("Origin", origin);
        return request;
    }
}
