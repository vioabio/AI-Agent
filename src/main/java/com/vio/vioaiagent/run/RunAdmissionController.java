package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.RunAdmissionProperties;
import java.util.HashMap;
import java.util.Map;

/** Reservations include queued, executing and approval-waiting Runs. */
final class RunAdmissionController {
    private final int capacity;
    private final int principalLimit;
    private final int tenantLimit;
    private final int retryAfter;
    private final Map<Principal, Integer> principals = new HashMap<>();
    private final Map<String, Integer> tenants = new HashMap<>();
    private int reserved;
    private boolean accepting = true;

    RunAdmissionController(RunAdmissionProperties properties) {
        capacity = properties.maxInFlight();
        principalLimit = properties.maxPerPrincipal();
        tenantLimit = properties.maxPerTenant();
        retryAfter = properties.retryAfterSeconds();
    }

    synchronized Permit acquire(String tenant, String principal) {
        if (!accepting) throw rejection("RUN_EXECUTION_UNAVAILABLE", true);
        Principal key = new Principal(tenant, principal);
        if (principals.getOrDefault(key, 0) >= principalLimit) throw rejection("RUN_PRINCIPAL_LIMIT", false);
        if (tenants.getOrDefault(tenant, 0) >= tenantLimit) throw rejection("RUN_TENANT_LIMIT", false);
        if (reserved >= capacity) throw rejection("RUN_CAPACITY_EXCEEDED", false);
        reserved++;
        principals.merge(key, 1, Integer::sum);
        tenants.merge(tenant, 1, Integer::sum);
        return new Permit(key);
    }
    synchronized void close() { accepting = false; }
    synchronized Snapshot snapshot() { return new Snapshot(reserved, capacity, principals.size(), tenants.size(), accepting); }
    private RunAdmissionException rejection(String code, boolean unavailable) { return new RunAdmissionException(code, retryAfter, unavailable); }
    record Snapshot(int reserved, int capacity, int principalScopes, int tenantScopes, boolean accepting) { }
    private record Principal(String tenant, String principal) { }

    final class Permit implements AutoCloseable {
        private final Principal principal;
        private boolean released;
        private Permit(Principal principal) { this.principal = principal; }
        @Override public void close() {
            synchronized (RunAdmissionController.this) {
                if (released) return;
                released = true;
                reserved--;
                decrement(principals, principal);
                decrement(tenants, principal.tenant());
            }
        }
    }
    private static <K> void decrement(Map<K, Integer> counts, K key) {
        int next = counts.getOrDefault(key, 0) - 1;
        if (next <= 0) counts.remove(key); else counts.put(key, next);
    }
}
