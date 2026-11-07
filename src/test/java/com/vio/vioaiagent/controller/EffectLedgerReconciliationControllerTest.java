package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.run.EffectLedgerReconciliationService;
import com.vio.vioaiagent.run.InMemoryDurableRunStateRepository;
import com.vio.vioaiagent.run.ToolEffectLedgerEntry;
import com.vio.vioaiagent.run.ToolEffectStatus;
import com.vio.vioaiagent.security.AuditEntry;
import com.vio.vioaiagent.security.PrincipalPolicy;
import com.vio.vioaiagent.security.ToolAuditSink;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EffectLedgerReconciliationControllerTest {
    private final InMemoryDurableRunStateRepository ledger = new InMemoryDurableRunStateRepository();
    private final AtomicReference<AuditEntry> audit = new AtomicReference<>();
    private final ToolAuditSink durableAudit = new ToolAuditSink() {
        @Override public void record(AuditEntry entry) { audit.set(entry); }
        @Override public boolean durable() { return true; }
    };
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new EffectLedgerReconciliationController(
            new EffectLedgerReconciliationService(ledger, durableAudit))).build();

    @BeforeEach void seed() {
        ToolEffectLedgerEntry started = ToolEffectLedgerEntry.started("run-1", 3, "write", "v1", "idem", "hash");
        ledger.reserve(started);
        ledger.complete(started.complete(ToolEffectStatus.UNKNOWN, "uncertain", null, null));
    }
    @AfterEach void clear() { RequestContext.clear(); }

    @Test void anonymousCannotReconcile() throws Exception {
        mvc.perform(request("SUCCEEDED")).andExpect(status().isForbidden());
    }
    @Test void nonOperatorCannotReconcile() throws Exception {
        identity("QUALITY");
        mvc.perform(request("SUCCEEDED")).andExpect(status().isForbidden());
    }
    @Test void operatorReconcileChangesLedgerAndWritesAudit() throws Exception {
        identity("OPERATOR");
        mvc.perform(request("FAILED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.status").value("FAILED"));
        assertEquals(ToolEffectStatus.FAILED, ledger.find("run-1", 3, "write", "v1", "idem", "hash").orElseThrow().status());
        assertEquals("operator-user", audit.get().userId());
        assertEquals("allow", audit.get().outcome());
    }
    @Test void invalidStateDoesNotCallLedger() throws Exception {
        identity("OPERATOR");
        mvc.perform(request("UNKNOWN")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("人工核对参数无效"));
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String status) {
        return post("/admin/effects/reconcile").contentType(MediaType.APPLICATION_JSON).content("""
                {"runId":"run-1","stepNo":3,"toolName":"write","toolVersion":"v1",
                 "idempotencyKey":"idem","parameterHash":"hash","finalStatus":"%s","resultDigest":"result"}
                """.formatted(status));
    }
    private void identity(String role) {
        RequestContext.setPrincipalId("operator-user");
        RequestContext.setPrincipalPolicy(new PrincipalPolicy("operator-user", "tenant-a", Set.of(role), Set.of("admin")));
    }
}
