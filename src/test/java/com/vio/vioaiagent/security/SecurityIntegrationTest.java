package com.vio.vioaiagent.security;

import com.vio.vioaiagent.config.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 安全全链路集成测试 — 验证四层安全体系的协同工作.
 *
 * @author vio
 */
@DisplayName("安全集成测试")
class SecurityIntegrationTest {

    private ApiKeyAuthInterceptor authInterceptor;
    private PathGuard pathGuard;
    private CommandGuard commandGuard;
    private HitlManager hitlManager;
    private ParameterMasker masker;
    private AuditLogger auditLogger;

    @BeforeEach
    void setUp() {
        SecurityProperties props = new SecurityProperties(
                true,
                Map.of("sk-integration-test", "集成测试令牌:default"),
                List.of(System.getProperty("user.dir") + "/tmp"),
                List.of()
        );
        TokenStore tokenStore = new TokenStore(props);
        authInterceptor = new ApiKeyAuthInterceptor(tokenStore, props);
        pathGuard = new PathGuard(props);
        commandGuard = new CommandGuard(props);
        hitlManager = new HitlManager();
        masker = new ParameterMasker();
        auditLogger = new AuditLogger(masker);
    }

    @Test
    @DisplayName("全链路: 认证通过 → 围栏检查 → 审批 → 审计")
    void shouldCompleteFullSecurityChain() throws Exception {
        // 1. 认证
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", "sk-integration-test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(authInterceptor.preHandle(request, response, null));

        // 2. 路径围栏 — 正常路径通过
        String safePath = System.getProperty("user.dir") + "/tmp/file/test.txt";
        assertDoesNotThrow(() -> pathGuard.validate(safePath));

        // 3. 命令围栏 — 正常命令通过
        assertDoesNotThrow(() -> commandGuard.validate("echo hello"));

        // 4. HITL — SAFE 工具直接通过
        ApprovalResult result = hitlManager.approve("web_search", "session-1");
        assertEquals(ApprovalResult.ApprovalAction.APPROVE, result.action());

        // 5. 审计日志 — 记录不抛异常
        assertDoesNotThrow(() -> auditLogger.log(
                "trace-full", "session-1", "user-1",
                "web_search", Map.of("query", "test"),
                "allow", "none", 100
        ));
    }

    @Test
    @DisplayName("认证失败应阻止所有后续操作")
    void shouldBlockAllOnAuthFailure() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(authInterceptor.preHandle(request, response, null));
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("路径穿越 + 危险命令应同时被拦截")
    void shouldBlockBothPathTraversalAndDangerousCommands() {
        assertThrows(SecurityException.class,
                () -> pathGuard.validate("../../etc/shadow"));

        assertThrows(SecurityException.class,
                () -> commandGuard.validate("sudo rm -rf /"));
    }

    @Test
    @DisplayName("审计日志应支持脱敏参数")
    void shouldMaskSensitiveParamsInAudit() {
        Map<String, Object> sensitiveParams = Map.of(
                "query", "test",
                "apiKey", "sk-secret-key-value"
        );

        assertDoesNotThrow(() -> auditLogger.log(
                "trace-audit", "session-1", "user-1",
                "web_search", sensitiveParams,
                "allow", "policy", 50
        ));

        // 验证脱敏生效
        Map<String, Object> masked = masker.mask(sensitiveParams);
        assertEquals(ParameterMasker.REDACTED, masked.get("apiKey"));
        assertEquals("test", masked.get("query"));
    }
}
