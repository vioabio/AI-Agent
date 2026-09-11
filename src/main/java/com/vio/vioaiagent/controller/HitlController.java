package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.Result;
import com.vio.vioaiagent.security.ApprovalResult;
import com.vio.vioaiagent.security.HitlManager;
import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.run.PokemonAgentRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * HITL 审批控制器 — 暴露人工审批 API 给前端。
 *
 * @author vio
 */
@Tag(name = "HITL 审批", description = "高危操作人工审批接口")
@RestController
// application.yml 已设置 context-path=/api；MVC 映射不能再次拼接 /api。
@RequestMapping("/hitl")
public class HitlController {

    private final HitlManager hitlManager;
    private final PokemonAgentRunService runService;
    private final SecurityProperties securityProperties;

    /**
     * 旧版 sessionId 接口无法绑定 user/run/参数摘要，默认关闭。
     * 生产环境只能使用 /hitl/approvals/{approvalId} 正式审批链路。
     */
    @Value("${vio.hitl.legacy-endpoints-enabled:false}")
    private boolean legacyEndpointsEnabled;

    @Autowired
    public HitlController(HitlManager hitlManager, PokemonAgentRunService runService,
                          SecurityProperties securityProperties) {
        this.hitlManager = hitlManager;
        this.runService = runService;
        this.securityProperties = securityProperties == null
                ? SecurityProperties.defaults() : securityProperties;
    }

    /** 兼容旧的程序化构造；默认采用本地安全关闭语义。 */
    public HitlController(HitlManager hitlManager, PokemonAgentRunService runService) {
        this(hitlManager, runService, SecurityProperties.defaults());
    }

    /** 兼容旧的只测试审批接口；正式 Run 决策需要三参构造器注入 Run 服务。 */
    public HitlController(HitlManager hitlManager) {
        this(hitlManager, null, SecurityProperties.defaults());
    }

    @Operation(summary = "用户批准指定工具调用")
    @PostMapping("/approve")
    public Result<String> approve(
            @Parameter(description = "工具名称") @RequestParam String toolName,
            @Parameter(description = "会话 ID") @RequestParam String sessionId) {
        requireLegacyEndpointEnabled();
        hitlManager.userApprove(toolName, sessionId);
        return Result.success("已批准: " + toolName);
    }

    @Operation(summary = "查询指定工具的危险等级")
    @GetMapping("/danger-level")
    public Result<String> getDangerLevel(
            @Parameter(description = "工具名称") @RequestParam String toolName) {
        return Result.success(hitlManager.getDangerLevel(toolName).name());
    }

    @Operation(summary = "审批决策")
    @PostMapping("/decide")
    public Result<ApprovalResult> decide(
            @Parameter(description = "工具名称") @RequestParam String toolName,
            @Parameter(description = "会话 ID") @RequestParam String sessionId) {
        requireLegacyEndpointEnabled();
        return Result.success(hitlManager.approve(toolName, sessionId));
    }

    private void requireLegacyEndpointEnabled() {
        if (!legacyEndpointsEnabled) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "旧版 sessionId 审批接口已停用，请使用绑定 userId/runId/approvalId 的正式接口");
        }
    }

    /** 正式 Run 审批：审批必须绑定同一 userId 与 runId。 */
    @Operation(summary = "处理 Run 审批请求")
    @PostMapping("/approvals/{approvalId}")
    public Result<HitlManager.ApprovalDecision> decideRunApproval(
            @PathVariable String approvalId,
            @RequestParam String runId,
            @RequestParam boolean approved,
            @RequestParam(required = false) String reason) {
        String principal = RequestContext.getPrincipalId();
        Long requestUser = RequestContext.getUserId();
        String userId = principal == null || principal.isBlank()
                ? (requestUser == null ? RequestContext.getUsername() : String.valueOf(requestUser))
                : principal;
        if ((userId == null || userId.isBlank()) && securityProperties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少认证身份");
        }
        if (userId == null || userId.isBlank()) {
            userId = "anonymous";
        }
        // getApproval 会先清理过期审批；过期请求返回 410，前端保留当前 Run
        // 并提示重新获取状态，而不是把一个已失效的审批卡静默清掉。
        HitlManager.PendingApproval pending = hitlManager.getApproval(approvalId);
        if (pending == null) {
            // 未找到审批标识时返回 404；已找到但已过期的持久记录由下方
            // EXPIRED 分支返回 410。这样客户端可以区分“未知 ID”和“过期卡片”。
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "审批不存在");
        }
        // 不泄露其他主体的审批是否存在；Run/用户不匹配统一返回 404。
        if (!pending.userId().equals(userId) || !pending.runId().equals(runId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "审批不存在");
        }
        // EXPIRED 是持久化审批的独立终态；客户端应收到 410 并重新发起
        // 审批，而不是把它误当成可重试的并发冲突（409）。
        if (pending.status() == HitlManager.ApprovalStatus.EXPIRED) {
            throw new ResponseStatusException(HttpStatus.GONE, "审批已过期");
        }
        if (pending.status() != HitlManager.ApprovalStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "审批已被其他请求处理，请刷新 Run");
        }
        HitlManager.ApprovalDecision decision = hitlManager.decide(
                approvalId, userId, runId, approved, reason);
        // 只有审批前已确认属于当前 user/run 的请求才能推进 Run，
        // 防止伪造 approvalId 将别人的 Run 恢复或终止。
        HitlManager.PendingApproval after = hitlManager.getApproval(approvalId);
        if (after != null && after.status() == HitlManager.ApprovalStatus.EXPIRED) {
            throw new ResponseStatusException(HttpStatus.GONE, "审批已过期");
        }
        if (after == null && "审批已过期".equals(decision.reason())) {
            throw new ResponseStatusException(HttpStatus.GONE, "审批已过期");
        }
        if (after != null && after.status() != HitlManager.ApprovalStatus.PENDING
                && after.userId().equals(userId) && after.runId().equals(runId)) {
            // 以审批存储中的最终状态为准；并发请求不能用自己的 approved 参数
            // 反向恢复一个已经被拒绝的 Run。
            boolean allowed = after.status() == HitlManager.ApprovalStatus.APPROVED;
            String resolutionReason = decision.reason() == null ? reason : decision.reason();
            if (runService != null) {
                runService.resolveApproval(runId, userId, allowed, resolutionReason);
            }
        }
        return Result.success(decision);
    }
}
