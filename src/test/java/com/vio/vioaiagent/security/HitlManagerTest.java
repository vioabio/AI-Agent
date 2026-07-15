package com.vio.vioaiagent.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HitlManager 测试.
 *
 * @author vio
 */
@DisplayName("HITL 审批管理器")
class HitlManagerTest {

    private HitlManager hitlManager;

    @BeforeEach
    void setUp() {
        hitlManager = new HitlManager();
    }

    @Test
    @DisplayName("SAFE 级别工具应直接通过")
    void shouldApproveSafeTool() {
        ApprovalResult result = hitlManager.approve("web_search", "session-1");
        assertEquals(ApprovalResult.ApprovalAction.APPROVE, result.action());
    }

    @Test
    @DisplayName("HIGH 级别工具首次应被拒绝")
    void shouldDenyHighRiskToolFirstTime() {
        ApprovalResult result = hitlManager.approve("terminal_operation", "session-1");
        assertEquals(ApprovalResult.ApprovalAction.DENY, result.action());
    }

    @Test
    @DisplayName("用户手动批准后 HIGH 级别工具应通过")
    void shouldApproveAfterUserAuthorization() {
        hitlManager.userApprove("terminal_operation", "session-1");
        ApprovalResult result = hitlManager.approve("terminal_operation", "session-1");
        assertEquals(ApprovalResult.ApprovalAction.APPROVE, result.action());
    }

    @Test
    @DisplayName("MEDIUM 级别工具首次自动通过并记住")
    void shouldAutoApproveMediumFirstTime() {
        ApprovalResult result = hitlManager.approve("write_file", "session-1");
        assertEquals(ApprovalResult.ApprovalAction.APPROVE, result.action());

        // 再次调用应直接通过
        ApprovalResult result2 = hitlManager.approve("write_file", "session-1");
        assertEquals(ApprovalResult.ApprovalAction.APPROVE, result2.action());
    }

    @Test
    @DisplayName("未知工具默认应为 SAFE")
    void shouldDefaultToSafeForUnknownTool() {
        assertEquals(DangerLevel.SAFE, hitlManager.getDangerLevel("unknown_tool"));
    }

    @Test
    @DisplayName("清除会话应移除审批记录")
    void shouldClearSessionApprovals() {
        hitlManager.userApprove("terminal_operation", "session-1");
        hitlManager.clearSession("session-1");

        // 清除后 HIGH 工具重新被拒
        ApprovalResult result = hitlManager.approve("terminal_operation", "session-1");
        assertEquals(ApprovalResult.ApprovalAction.DENY, result.action());
    }
}
