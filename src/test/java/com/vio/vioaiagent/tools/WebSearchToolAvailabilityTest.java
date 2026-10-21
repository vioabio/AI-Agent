package com.vio.vioaiagent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.http.HttpTimeoutException;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class WebSearchToolAvailabilityTest {
    @Test
    void absentOrPlaceholderKeyHidesSearchTool() {
        for (String key : new String[]{"", "your-searchapi-key-here"}) {
            ToolRegistration registration = new ToolRegistration();
            ReflectionTestUtils.setField(registration, "searchApiKey", key);
            assertFalse(Arrays.stream(registration.pokemonTools()).anyMatch(t -> t.getToolDefinition().name().equals("searchWeb")));
        }
        ToolRegistration registration = new ToolRegistration();
        ReflectionTestUtils.setField(registration, "searchApiKey", "fixture-key");
        assertTrue(Arrays.stream(registration.pokemonTools()).anyMatch(t -> t.getToolDefinition().name().equals("searchWeb")));
    }

    @Test
    void providerRejectionIsNotMistakenForEmptyResults() {
        WebSearchTool rejected = new WebSearchTool("fixture-key", (q, k) -> new WebSearchTool.SearchResponse(401, "private"));
        assertEquals("SEARCH_PROVIDER_HTTP_401", assertThrows(IllegalStateException.class,
                () -> rejected.searchWeb("杰尼龟")).getMessage());
        WebSearchTool error = new WebSearchTool("fixture-key", (q, k) -> new WebSearchTool.SearchResponse(200, "{\"error\":\"private\"}"));
        assertEquals("SEARCH_PROVIDER_REJECTED", assertThrows(IllegalStateException.class,
                () -> error.searchWeb("杰尼龟")).getMessage());
        WebSearchTool empty = new WebSearchTool("fixture-key", (q, k) -> new WebSearchTool.SearchResponse(200, "{\"organic_results\":[]}"));
        assertTrue(empty.searchWeb("杰尼龟").startsWith("No results"));
    }

    @Test
    void timeoutRemainsAnExplicitFailureWithoutExposingCredentials() {
        WebSearchTool tool = new WebSearchTool("fixture-key", (q, k) -> { throw new HttpTimeoutException("secret=private"); });
        var failure = assertThrows(IllegalStateException.class, () -> tool.searchWeb("杰尼龟"));
        assertTrue(failure.getMessage().startsWith("SEARCH_TIMEOUT"));
        assertFalse(failure.getMessage().contains("private"));
    }
}
