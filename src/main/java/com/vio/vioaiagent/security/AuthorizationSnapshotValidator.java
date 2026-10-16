package com.vio.vioaiagent.security;

import com.vio.vioaiagent.config.OidcPrincipalPolicyProperties;
import com.vio.vioaiagent.tools.governance.ToolCapabilityCard;
import com.vio.vioaiagent.tools.governance.ToolCapabilityCatalog;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Revalidates a durable snapshot against the current non-secret policy config. */
@Component
public class AuthorizationSnapshotValidator {
    private final OidcPrincipalPolicyProperties properties;
    private final Clock clock;

    @Autowired
    public AuthorizationSnapshotValidator(OidcPrincipalPolicyProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AuthorizationSnapshotValidator(OidcPrincipalPolicyProperties properties, Clock clock) {
        this.properties = properties == null ? new OidcPrincipalPolicyProperties() : properties;
        this.clock = clock;
    }

    public Validation validate(AuthorizationSnapshot snapshot) {
        if (snapshot == null) return Validation.deny("AUTH_SNAPSHOT_MISSING");
        Instant now = clock.instant();
        if (snapshot.expired(now)) return Validation.deny("AUTH_SNAPSHOT_EXPIRED");
        if (!properties.enabled() || !properties.validationErrors().isEmpty()) {
            return Validation.deny("AUTH_POLICY_UNAVAILABLE");
        }
        if (!properties.policyVersion().equals(snapshot.policyVersion())) {
            return Validation.deny("AUTH_POLICY_VERSION_DRIFT");
        }
        String mappedTenant = properties.principalTenants().get(snapshot.principalId());
        if (mappedTenant != null && !mappedTenant.equals(snapshot.tenantId())) {
            return Validation.deny("AUTH_TENANT_DRIFT");
        }
        Set<String> configuredRoles = rolesForTenant(snapshot.tenantId());
        if (configuredRoles.isEmpty() || !configuredRoles.containsAll(snapshot.roles())) {
            return Validation.deny("AUTH_ROLE_DRIFT");
        }
        Set<String> expectedTools = toolsFor(snapshot.roles());
        if (!expectedTools.equals(snapshot.allowedTools())) {
            return Validation.deny("AUTH_TOOL_POLICY_DRIFT");
        }
        return Validation.allow();
    }

    private Set<String> rolesForTenant(String tenant) {
        var values = properties.tenantRoles().get(tenant);
        if (values == null) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(value.toLowerCase(Locale.ROOT)));
        return Set.copyOf(result);
    }

    private Set<String> toolsFor(Set<String> roles) {
        Set<String> result = new LinkedHashSet<>();
        if (!properties.roleTools().isEmpty()) {
            for (String role : roles) {
                var configured = properties.roleTools().get(role);
                if (configured != null) result.addAll(configured);
            }
            return Set.copyOf(result);
        }
        for (ToolCapabilityCard card : ToolCapabilityCatalog.all().values()) {
            if (card.allowedRoles().stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(roles::contains)) {
                result.add(card.name());
            }
        }
        return Set.copyOf(result);
    }

    public record Validation(boolean allowed, String reason) {
        static Validation allow() { return new Validation(true, ""); }
        static Validation deny(String reason) { return new Validation(false, reason); }
    }
}
