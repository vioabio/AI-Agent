package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.security.HitlApproval;
import com.vio.vioaiagent.security.HitlAuditSink;
import com.vio.vioaiagent.security.HitlManager;
import com.vio.vioaiagent.security.InMemoryHitlApprovalRepository;
import com.vio.vioaiagent.run.PokemonAgentRunService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** 持久审批 EXPIRED 必须映射为 HTTP 410，而非通用 409。 */
class HitlControllerConsistencyTest {

    @Test
    void unknownApprovalReturnsNotFound() {
        HitlManager manager = new HitlManager(HitlAuditSink.noop());
        RequestContext.setPrincipalId("user-1");
        try {
            HitlController controller = new HitlController(manager, mock(PokemonAgentRunService.class),
                    SecurityProperties.defaults());

            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> controller.decideRunApproval("approval-unknown", "run-1", true, null));

            assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, error.getStatusCode());
        } finally {
            RequestContext.clear();
            manager.shutdown();
        }
    }

    @Test
    void expiredApprovalReturnsGone() {
        Instant now = Instant.now();
        InMemoryHitlApprovalRepository repository = new InMemoryHitlApprovalRepository();
        String approvalId = "approval-expired-controller";
        repository.createOrGet(new HitlApproval(approvalId, "user-1", "run-1", "write_file", "1.0.0",
                "digest", "MEDIUM", now.minusSeconds(1), HitlApproval.Decision.PENDING,
                null, null, null, null, null, 0, now.minusSeconds(2), now.minusSeconds(2)), now);
        repository.expireDue(now, "system");
        HitlManager manager = new HitlManager(HitlAuditSink.noop());
        manager.setApprovalRepository(repository);
        RequestContext.setPrincipalId("user-1");
        try {
            HitlController controller = new HitlController(manager, mock(PokemonAgentRunService.class),
                    SecurityProperties.defaults());

            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> controller.decideRunApproval(approvalId, "run-1", true, null));

            assertEquals( org.springframework.http.HttpStatus.GONE, error.getStatusCode());
        } finally {
            RequestContext.clear();
            manager.shutdown();
        }
    }
}
