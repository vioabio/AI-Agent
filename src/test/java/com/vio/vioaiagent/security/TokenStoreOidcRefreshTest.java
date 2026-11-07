package com.vio.vioaiagent.security;

import com.vio.vioaiagent.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TokenStoreOidcRefreshTest {
    @Test
    void nextHttpRequestDoesNotInvalidateRunningSnapshotOrResetQuota() {
        TokenStore store = new TokenStore(SecurityProperties.defaults());
        TokenBinding captured = binding(Set.of("trainer"), Set.of("web_search"), 1, Instant.now().plusSeconds(60));
        store.register(captured);
        assertNull(store.consumeRate(captured));
        TokenBinding refreshed = binding(Set.of("trainer"), Set.of("web_search"), 1, Instant.now().plusSeconds(120));
        store.register(refreshed);
        assertNull(store.authorizeTool(captured, "web_search"));
        assertNotNull(store.consumeRate(refreshed), "renewal must keep the stable principal quota counter");
        assertEquals(1, store.size(), "renewal must not accumulate a token per request");
    }

    @Test
    void refreshCannotExtendCapturedRunLifetime() {
        TokenStore store = new TokenStore(SecurityProperties.defaults());
        TokenBinding expired = binding(Set.of("trainer"), Set.of("web_search"), 10, Instant.now().minusSeconds(1));
        store.register(binding(Set.of("trainer"), Set.of("web_search"), 10, Instant.now().plusSeconds(120)));
        assertNotNull(store.authorizeTool(expired, "web_search"));
    }

    @Test
    void changedRolesToolsOrQuotaRejectCapturedSnapshot() {
        TokenStore store = new TokenStore(SecurityProperties.defaults());
        Instant expiration = Instant.now().plusSeconds(120);
        TokenBinding captured = binding(Set.of("trainer"), Set.of("web_search"), 10, expiration);
        store.register(binding(Set.of("operator"), Set.of("web_search"), 10, expiration));
        assertNotNull(store.authorizeTool(captured, "web_search"));
        store.register(binding(Set.of("trainer"), Set.of("other_tool"), 10, expiration));
        assertNotNull(store.authorizeTool(captured, "web_search"));
        store.register(binding(Set.of("trainer"), Set.of("web_search"), 5, expiration));
        assertNotNull(store.authorizeTool(captured, "web_search"));
    }

    @Test
    void revocationAndCurrentExpirationStillRejectOldSnapshot() {
        TokenStore store = new TokenStore(SecurityProperties.defaults());
        TokenBinding captured = binding(Set.of("trainer"), Set.of("web_search"), 10, Instant.now().plusSeconds(60));
        store.register(captured);
        store.revoke(captured.tokenId());
        assertNotNull(store.authorizeTool(captured, "web_search"));
        store.register(binding(Set.of("trainer"), Set.of("web_search"), 10, Instant.now().minusSeconds(1)));
        assertNotNull(store.authorizeTool(captured, "web_search"));
    }

    @Test
    void apiKeyRecordsStillRequireExactEquality() {
        TokenStore store = new TokenStore(SecurityProperties.defaults());
        TokenBinding captured = new TokenBinding("fixture-key", "default", Set.of("web_search"), 1,
                Instant.now().plusSeconds(60));
        store.register(new TokenBinding("fixture-key", "default", Set.of("web_search"), 1,
                Instant.now().plusSeconds(120)));
        assertNotNull(store.authorizeTool(captured, "web_search"));
    }

    private TokenBinding binding(Set<String> roles, Set<String> tools, int limit, Instant expiration) {
        return TokenBinding.oidc("alice", "tenant-a", roles, tools, "v1", limit, expiration);
    }
}
