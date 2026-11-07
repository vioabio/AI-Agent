package com.vio.vioaiagent.security;

import com.vio.vioaiagent.config.OidcPrincipalPolicyProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationSnapshotTest {
    @Test
    void snapshotContainsOnlyStableSecretFreeFactsAndIsImmutable() {
        Instant issued = Instant.parse("2026-10-16T00:00:00Z");
        Instant expires = issued.plusSeconds(60);
        TokenBinding binding = TokenBinding.oidc("alice", "tenant-a", Set.of("trainer"),
                Set.of("getTypeChart"), "v7", 10, expires);
        PrincipalPolicy policy = new PrincipalPolicy("alice", "tenant-a", Set.of("trainer"),
                Set.of("getTypeChart"), "v7", expires, binding);
        AuthorizationSnapshot snapshot = AuthorizationSnapshot.from("run-1", policy, issued);

        assertEquals("alice", snapshot.principalId());
        assertEquals("tenant-a", snapshot.tenantId());
        assertEquals(policy.fingerprint(), snapshot.policyFingerprint());
        assertEquals(binding.tokenId(), snapshot.bindingId());
        assertFalse(snapshot.expired(issued.plusSeconds(1)));
        assertTrue(snapshot.expired(expires));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.roles().add("admin"));
    }

    @Test
    void repositoryRejectsImmutableOverwrite() {
        InMemoryAuthorizationSnapshotRepository repository = new InMemoryAuthorizationSnapshotRepository();
        AuthorizationSnapshot first = new AuthorizationSnapshot("run-1", "alice", "tenant-a",
                Set.of("trainer"), Set.of("tool"), "v1", "a".repeat(64), "binding", Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60));
        repository.save(first);
        assertSame(first, repository.save(first));
        AuthorizationSnapshot changed = new AuthorizationSnapshot("run-1", "alice", "tenant-a",
                Set.of("admin"), Set.of("tool"), "v1", "b".repeat(64), "binding", Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60));
        assertThrows(IllegalStateException.class, () -> repository.save(changed));
    }

    @Test
    void validatorRejectsPolicyVersionAndToolDrift() {
        Instant issued = Instant.parse("2026-10-16T00:00:00Z");
        Instant expires = issued.plusSeconds(60);
        TokenBinding binding = TokenBinding.oidc("alice", "tenant-a", Set.of("trainer"),
                Set.of("getTypeChart"), "v7", 10, expires);
        PrincipalPolicy policy = new PrincipalPolicy("alice", "tenant-a", Set.of("trainer"),
                Set.of("getTypeChart"), "v7", expires, binding);
        OidcPrincipalPolicyProperties properties = new OidcPrincipalPolicyProperties();
        properties.setPolicyVersion("v7");
        properties.setTenantRoles(Map.of("tenant-a", java.util.List.of("trainer")));
        properties.setRoleTools(Map.of("trainer", java.util.List.of("getTypeChart")));
        AuthorizationSnapshotValidator validator = new AuthorizationSnapshotValidator(properties,
                Clock.fixed(issued.plusSeconds(1), ZoneOffset.UTC));
        assertTrue(validator.validate(AuthorizationSnapshot.from("run-1", policy, issued)).allowed());
        properties.setPolicyVersion("v8");
        assertEquals("AUTH_POLICY_VERSION_DRIFT", validator.validate(
                AuthorizationSnapshot.from("run-1", policy, issued)).reason());
    }

    @Test
    void revocationIsAnIndependentAppendOnlyFact() {
        InMemoryAuthorizationRevocationRepository repository = new InMemoryAuthorizationRevocationRepository();
        Instant at = Instant.parse("2026-10-16T00:00:00Z");
        AuthorizationRevocation first = repository.revoke("run-1", "user disabled", at);
        AuthorizationRevocation second = repository.revoke("run-1", "later reason", at.plusSeconds(1));
        assertEquals(first, second);
        assertEquals("user disabled", repository.findByRunId("run-1").orElseThrow().reason());
    }
}
